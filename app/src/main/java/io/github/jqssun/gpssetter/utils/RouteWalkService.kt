package io.github.jqssun.gpssetter.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.maps.model.LatLng
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.ui.MapActivity
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.random.Random

/**
 * Foreground Service untuk menggerakkan GPS secara bertahap mengikuti route.
 *
 * Worst-case handling:
 * - Service crash: onDestroy membersihkan state, broadcast WALK_FINISHED
 * - Empty route list: langsung selesai, tidak crash
 * - Pause/Resume: state disimpan, tidak hilang saat pause
 * - Double start: dicegah dengan flag isWalking
 * - Speed 0: dicegah minimum speed 0.5 m/s
 * - Memory leak: coroutine dibatalkan di onDestroy
 * - ANR: semua delay di background thread
 * - GPS noise: ditambahkan untuk realism
 * - Sharp turns: speed dikurangi saat belokan tajam
 */
class RouteWalkService : Service() {

    companion object {
        const val TAG = "RouteWalkService"
        const val CHANNEL_ID = "route_walk_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START = "io.github.jqssun.gpssetter.WALK_START"
        const val ACTION_PAUSE = "io.github.jqssun.gpssetter.WALK_PAUSE"
        const val ACTION_RESUME = "io.github.jqssun.gpssetter.WALK_RESUME"
        const val ACTION_STOP = "io.github.jqssun.gpssetter.WALK_STOP"

        const val BROADCAST_PROGRESS = "io.github.jqssun.gpssetter.WALK_PROGRESS"
        const val BROADCAST_FINISHED = "io.github.jqssun.gpssetter.WALK_FINISHED"
        const val BROADCAST_STATE = "io.github.jqssun.gpssetter.WALK_STATE"

        const val EXTRA_ROUTE_LATS = "route_lats"
        const val EXTRA_ROUTE_LNGS = "route_lngs"
        const val EXTRA_SPEED_MS = "speed_ms"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_CURRENT_INDEX = "current_index"
        const val EXTRA_TOTAL_POINTS = "total_points"
        const val EXTRA_STATE = "state"

        const val STATE_WALKING = "walking"
        const val STATE_PAUSED = "paused"
        const val STATE_FINISHED = "finished"
        const val STATE_ERROR = "error"

        // Speed presets (m/s)
        const val SPEED_WALK = 1.4      // ~5 km/h
        const val SPEED_RUN = 3.0       // ~11 km/h
        const val SPEED_BIKE = 5.5      // ~20 km/h
        const val SPEED_CAR = 11.0      // ~40 km/h

        private const val MIN_SPEED = 0.5
        private const val GPS_NOISE_METERS = 2.0 // noise radius for realism
        private const val SHARP_TURN_ANGLE = 60.0 // degrees
        private const val BRAKE_FACTOR = 0.4 // speed reduction at sharp turns
        private const val UPDATE_INTERVAL_MS = 1000L // minimum interval between GPS updates
    }

    private var routePoints: List<LatLng> = emptyList()
    private var speedMs: Double = SPEED_WALK
    private var currentIndex: Int = 0
    private var isWalking: Boolean = false
    private var isPaused: Boolean = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var walkJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        if (isWalking) {
            Log.w(TAG, "Already walking, ignoring duplicate start")
            return
        }

        val lats = intent.getDoubleArrayExtra(EXTRA_ROUTE_LATS)
        val lngs = intent.getDoubleArrayExtra(EXTRA_ROUTE_LNGS)
        speedMs = intent.getDoubleExtra(EXTRA_SPEED_MS, SPEED_WALK).coerceAtLeast(MIN_SPEED)

        if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size) {
            Log.e(TAG, "Invalid route data: lats=${lats?.size}, lngs=${lngs?.size}")
            broadcastState(STATE_ERROR)
            stopSelf()
            return
        }

        routePoints = lats.zip(lngs).map { (lat, lng) -> LatLng(lat, lng) }
        currentIndex = 0
        isWalking = true
        isPaused = false

        Log.d(TAG, "Starting walk: ${routePoints.size} points, speed=${speedMs} m/s")

        // Start foreground immediately to prevent crash
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Auto Walk dimulai..."))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground: ${e.message}")
            broadcastState(STATE_ERROR)
            stopSelf()
            return
        }

        broadcastState(STATE_WALKING)
        startWalking()
    }

    private fun handlePause() {
        if (!isWalking || isPaused) return
        isPaused = true
        walkJob?.cancel()
        broadcastState(STATE_PAUSED)
        updateNotification("Auto Walk dijeda (${currentIndex}/${routePoints.size})")
        Log.d(TAG, "Paused at index $currentIndex")
    }

    private fun handleResume() {
        if (!isWalking || !isPaused) return
        isPaused = false
        broadcastState(STATE_WALKING)
        updateNotification("Auto Walk berjalan...")
        startWalking()
        Log.d(TAG, "Resumed from index $currentIndex")
    }

    private fun handleStop() {
        Log.d(TAG, "Stopping walk")
        isWalking = false
        isPaused = false
        walkJob?.cancel()
        broadcastState(STATE_FINISHED)
        broadcastFinished()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startWalking() {
        walkJob?.cancel()
        walkJob = serviceScope.launch {
            try {
                walkAlongRoute()
            } catch (e: CancellationException) {
                Log.d(TAG, "Walk cancelled (pause/stop)")
            } catch (e: Exception) {
                Log.e(TAG, "Walk error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    broadcastState(STATE_ERROR)
                }
            }
        }
    }

    private suspend fun walkAlongRoute() {
        while (currentIndex < routePoints.size - 1 && isWalking && !isPaused) {
            val current = routePoints[currentIndex]
            val next = routePoints[currentIndex + 1]

            val distance = OsrmRouteHelper.distanceBetween(current, next)

            // Hitung effective speed (kurangi saat belokan tajam)
            val effectiveSpeed = calculateEffectiveSpeed(currentIndex)

            // Hitung delay berdasarkan jarak dan speed
            val travelTimeMs = if (effectiveSpeed > 0) {
                ((distance / effectiveSpeed) * 1000).toLong().coerceAtLeast(UPDATE_INTERVAL_MS)
            } else {
                UPDATE_INTERVAL_MS
            }

            // Interpolasi antara titik jika jarak besar (> 20m)
            if (distance > 20.0) {
                val steps = (distance / 10.0).toInt().coerceIn(1, 50)
                val stepDelay = travelTimeMs / steps

                for (step in 1..steps) {
                    if (!isWalking || isPaused) return

                    val fraction = step.toDouble() / steps
                    val interpLat = current.latitude + (next.latitude - current.latitude) * fraction
                    val interpLng = current.longitude + (next.longitude - current.longitude) * fraction

                    // Tambah GPS noise untuk realism (hanya di speed rendah)
                    val (noisyLat, noisyLng) = addGpsNoise(interpLat, interpLng)

                    PrefManager.update(true, noisyLat, noisyLng)
                    delay(stepDelay.coerceAtLeast(200L))
                }
            } else {
                // Titik dekat, langsung pindah
                val (noisyLat, noisyLng) = addGpsNoise(next.latitude, next.longitude)
                PrefManager.update(true, noisyLat, noisyLng)
                delay(travelTimeMs.coerceAtLeast(500L))
            }

            currentIndex++

            // Broadcast progress
            val progress = ((currentIndex.toFloat() / (routePoints.size - 1)) * 100).toInt()
            broadcastProgress(progress, currentIndex, routePoints.size)

            // Update notification setiap 10 titik
            if (currentIndex % 10 == 0) {
                updateNotification("Auto Walk: $progress% (${currentIndex}/${routePoints.size})")
            }
        }

        // Selesai
        if (isWalking && currentIndex >= routePoints.size - 1) {
            // Set final position tanpa noise
            val finalPoint = routePoints.last()
            PrefManager.update(true, finalPoint.latitude, finalPoint.longitude)

            Log.d(TAG, "Walk completed at final position")
            broadcastProgress(100, routePoints.size, routePoints.size)

            withContext(Dispatchers.Main) {
                handleStop()
            }
        }
    }

    /**
     * Hitung effective speed berdasarkan sudut belokan.
     * Jika belokan tajam (> 60°), speed dikurangi.
     */
    private fun calculateEffectiveSpeed(index: Int): Double {
        if (index <= 0 || index >= routePoints.size - 1) return speedMs

        val prev = routePoints[index - 1]
        val current = routePoints[index]
        val next = routePoints[index + 1]

        val bearingIn = OsrmRouteHelper.bearingBetween(prev, current)
        val bearingOut = OsrmRouteHelper.bearingBetween(current, next)

        var angleDiff = abs(bearingOut - bearingIn)
        if (angleDiff > 180) angleDiff = 360 - angleDiff

        return if (angleDiff > SHARP_TURN_ANGLE) {
            speedMs * BRAKE_FACTOR
        } else {
            speedMs
        }
    }

    /**
     * Tambah noise GPS kecil untuk realism.
     * Noise lebih besar saat speed rendah (seperti jalan kaki).
     */
    private fun addGpsNoise(lat: Double, lng: Double): Pair<Double, Double> {
        val noiseRadius = if (speedMs <= SPEED_WALK) GPS_NOISE_METERS else GPS_NOISE_METERS * 0.5

        // Convert noise meter ke derajat (aproksimasi)
        val noiseLat = (Random.nextDouble() - 0.5) * 2 * (noiseRadius / 111000.0)
        val noiseLng = (Random.nextDouble() - 0.5) * 2 * (noiseRadius / (111000.0 * Math.cos(Math.toRadians(lat))))

        return Pair(lat + noiseLat, lng + noiseLng)
    }

    // === Notification ===

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Auto Walk",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi saat Auto Walk aktif"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MapActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, RouteWalkService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("🚶 Auto Walk")
            .setContentText(text)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stop, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    // === Broadcasts ===

    private fun broadcastProgress(progress: Int, currentIdx: Int, total: Int) {
        val intent = Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_CURRENT_INDEX, currentIdx)
            putExtra(EXTRA_TOTAL_POINTS, total)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastState(state: String) {
        val intent = Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastFinished() {
        val intent = Intent(BROADCAST_FINISHED).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        walkJob?.cancel()
        serviceScope.cancel()
        isWalking = false
        // Pastikan broadcast finished jika belum dikirim
        broadcastFinished()
    }
}
