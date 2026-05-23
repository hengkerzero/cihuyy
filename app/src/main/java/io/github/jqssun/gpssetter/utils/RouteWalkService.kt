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
import kotlin.math.cos
import kotlin.random.Random

/**
 * Foreground Service untuk menggerakkan GPS secara bertahap mengikuti route.
 *
 * === PERBAIKAN UTAMA v2 ===
 * Masalah sebelumnya: speed hanya 14 km/h meski pilih "Mobil" (40 km/h).
 * Root cause:
 *   1. UPDATE_INTERVAL minimum 1000ms terlalu besar → titik dekat dipaksa 1 detik
 *   2. Interpolasi 10m per step → jarak step tidak match dengan waktu
 *   3. GPS noise berlebihan menambah jarak liar
 *   4. Brake factor 0.4 terlalu agresif
 *
 * Solusi:
 *   - Gunakan time-based movement: setiap tick (200ms), hitung berapa meter
 *     yang HARUS ditempuh = speed * dt, lalu geser posisi sepanjang route.
 *   - Tidak ada minimum interval yang memaksa speed turun.
 *   - Noise dikurangi dan proporsional speed.
 *   - Brake hanya di belokan sangat tajam (>90°), faktor 0.6 bukan 0.4.
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
        const val EXTRA_SPEED_KMH = "speed_kmh"

        const val STATE_WALKING = "walking"
        const val STATE_PAUSED = "paused"
        const val STATE_FINISHED = "finished"
        const val STATE_ERROR = "error"

        // Tick interval — setiap 200ms update posisi
        // Ini cukup smooth untuk GPS mock dan cukup ringan untuk battery
        private const val TICK_INTERVAL_MS = 200L

        // GPS noise: sangat kecil, hanya untuk realism
        private const val GPS_NOISE_BASE_METERS = 1.0

        // Belokan tajam threshold dan brake
        private const val SHARP_TURN_ANGLE = 90.0   // derajat
        private const val BRAKE_FACTOR = 0.6        // speed * 0.6 saat belokan tajam

        // Minimum speed (m/s) — 1 km/h
        private const val MIN_SPEED_MS = 0.28
    }

    private var routePoints: List<LatLng> = emptyList()
    private var speedMs: Double = 1.4  // default 5 km/h
    private var speedKmh: Float = 5f

    // Progress tracking: posisi di antara segment
    private var currentSegmentIndex: Int = 0
    private var distanceAlongSegment: Double = 0.0 // meter dari titik currentSegmentIndex
    private var totalRouteDistance: Double = 0.0
    private var distanceTraveled: Double = 0.0

    private var isWalking: Boolean = false
    private var isPaused: Boolean = false

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var walkJob: Job? = null

    // Pre-computed segment distances
    private var segmentDistances: DoubleArray = doubleArrayOf()

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
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
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
        speedMs = intent.getDoubleExtra(EXTRA_SPEED_MS, 1.4).coerceAtLeast(MIN_SPEED_MS)
        speedKmh = intent.getFloatExtra(EXTRA_SPEED_KMH, 5f)

        if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size) {
            Log.e(TAG, "Invalid route data: lats=${lats?.size}, lngs=${lngs?.size}")
            broadcastState(STATE_ERROR)
            stopSelf()
            return
        }

        routePoints = lats.zip(lngs).map { (lat, lng) -> LatLng(lat, lng) }

        // Pre-compute segment distances
        segmentDistances = DoubleArray(routePoints.size - 1) { i ->
            OsrmRouteHelper.distanceBetween(routePoints[i], routePoints[i + 1])
        }
        totalRouteDistance = segmentDistances.sum()

        currentSegmentIndex = 0
        distanceAlongSegment = 0.0
        distanceTraveled = 0.0
        isWalking = true
        isPaused = false

        Log.d(TAG, "Starting walk: ${routePoints.size} points, totalDist=${totalRouteDistance}m, speed=${speedMs} m/s (${speedKmh} km/h)")

        try {
            startForeground(NOTIFICATION_ID, buildNotification("Auto Walk ${speedKmh.toInt()} km/h"))
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
        val progress = if (totalRouteDistance > 0) ((distanceTraveled / totalRouteDistance) * 100).toInt() else 0
        updateNotification("Dijeda — $progress%")
        Log.d(TAG, "Paused at segment $currentSegmentIndex, traveled ${distanceTraveled}m")
    }

    private fun handleResume() {
        if (!isWalking || !isPaused) return
        isPaused = false
        broadcastState(STATE_WALKING)
        updateNotification("Auto Walk ${speedKmh.toInt()} km/h")
        startWalking()
        Log.d(TAG, "Resumed")
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
                walkLoop()
            } catch (e: CancellationException) {
                // Normal pause/stop
            } catch (e: Exception) {
                Log.e(TAG, "Walk error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    broadcastState(STATE_ERROR)
                }
            }
        }
    }

    /**
     * Core movement loop — time-based.
     *
     * Setiap tick (200ms):
     * 1. Hitung jarak yang harus ditempuh = speed * (tick/1000)
     * 2. Maju sepanjang route sejumlah jarak itu
     * 3. Update GPS position
     * 4. Broadcast progress
     *
     * Ini menjamin speed ACTUAL sesuai dengan yang dipilih user.
     */
    private suspend fun walkLoop() {
        val tickSeconds = TICK_INTERVAL_MS / 1000.0
        var lastNotifUpdate = 0L

        while (isWalking && !isPaused && currentSegmentIndex < segmentDistances.size) {

            // Hitung effective speed (brake di belokan tajam)
            val effectiveSpeed = calculateEffectiveSpeed()

            // Jarak yang ditempuh tick ini
            val moveDistance = effectiveSpeed * tickSeconds

            // Advance along route
            advanceAlongRoute(moveDistance)

            // Hitung posisi interpolasi di segment saat ini
            if (currentSegmentIndex >= segmentDistances.size) break // sudah selesai

            val segDist = segmentDistances[currentSegmentIndex]
            val fraction = if (segDist > 0) (distanceAlongSegment / segDist).coerceIn(0.0, 1.0) else 0.0

            val from = routePoints[currentSegmentIndex]
            val to = routePoints[currentSegmentIndex + 1]

            val currentLat = from.latitude + (to.latitude - from.latitude) * fraction
            val currentLng = from.longitude + (to.longitude - from.longitude) * fraction

            // Tambah noise GPS kecil (proporsional dengan speed — lebih cepat = lebih kecil)
            val (noisyLat, noisyLng) = addGpsNoise(currentLat, currentLng)

            // Update GPS mock
            PrefManager.update(true, noisyLat, noisyLng)

            // Broadcast progress
            val progress = if (totalRouteDistance > 0) {
                ((distanceTraveled / totalRouteDistance) * 100).toInt().coerceIn(0, 100)
            } else 0

            broadcastProgress(progress, currentSegmentIndex, routePoints.size)

            // Update notification setiap 3 detik
            val now = System.currentTimeMillis()
            if (now - lastNotifUpdate > 3000) {
                val remaining = totalRouteDistance - distanceTraveled
                val etaSeconds = if (effectiveSpeed > 0) remaining / effectiveSpeed else 0.0
                val etaMin = (etaSeconds / 60).toInt()
                updateNotification("${speedKmh.toInt()} km/h — $progress% — ETA ${etaMin} mnt")
                lastNotifUpdate = now
            }

            delay(TICK_INTERVAL_MS)
        }

        // Selesai — set posisi final tanpa noise
        if (isWalking && currentSegmentIndex >= segmentDistances.size) {
            val finalPoint = routePoints.last()
            PrefManager.update(true, finalPoint.latitude, finalPoint.longitude)
            Log.d(TAG, "Walk completed!")
            broadcastProgress(100, routePoints.size, routePoints.size)

            withContext(Dispatchers.Main) {
                handleStop()
            }
        }
    }

    /**
     * Geser posisi sepanjang route sejauh [distance] meter.
     * Bisa melewati beberapa segment sekaligus jika distance besar.
     */
    private fun advanceAlongRoute(distance: Double) {
        var remaining = distance
        distanceTraveled += distance

        while (remaining > 0 && currentSegmentIndex < segmentDistances.size) {
            val segDist = segmentDistances[currentSegmentIndex]
            val leftInSegment = segDist - distanceAlongSegment

            if (remaining < leftInSegment) {
                // Masih di segment yang sama
                distanceAlongSegment += remaining
                remaining = 0.0
            } else {
                // Pindah ke segment berikutnya
                remaining -= leftInSegment
                currentSegmentIndex++
                distanceAlongSegment = 0.0
            }
        }
    }

    /**
     * Hitung speed efektif berdasarkan sudut belokan di titik saat ini.
     * Hanya brake di belokan sangat tajam (>90°).
     */
    private fun calculateEffectiveSpeed(): Double {
        if (currentSegmentIndex <= 0 || currentSegmentIndex >= segmentDistances.size - 1) {
            return speedMs
        }

        val prev = routePoints[currentSegmentIndex - 1]
        val current = routePoints[currentSegmentIndex]
        val next = routePoints[currentSegmentIndex + 1]

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
     * GPS noise yang minimal dan proporsional speed.
     * Speed tinggi → noise kecil (karena GPS IRL juga lebih stabil saat gerak cepat).
     * Speed rendah → noise sedikit lebih besar (GPS IRL lebih goyang saat diam/pelan).
     */
    private fun addGpsNoise(lat: Double, lng: Double): Pair<Double, Double> {
        // Noise radius: 1m di speed rendah, 0.3m di speed tinggi
        val noiseFactor = (1.0 - (speedMs / 22.0).coerceAtMost(0.7)) // 22 m/s = 80 km/h
        val noiseRadius = GPS_NOISE_BASE_METERS * noiseFactor

        val noiseLat = (Random.nextDouble() - 0.5) * 2 * (noiseRadius / 111_000.0)
        val noiseLng = (Random.nextDouble() - 0.5) * 2 * (noiseRadius / (111_000.0 * cos(Math.toRadians(lat))))

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
            .setSmallIcon(R.drawable.ic_baseline_directions_walk_24)
            .setContentTitle("Auto Walk")
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
        sendBroadcast(Intent(BROADCAST_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_CURRENT_INDEX, currentIdx)
            putExtra(EXTRA_TOTAL_POINTS, total)
            setPackage(packageName)
        })
    }

    private fun broadcastState(state: String) {
        sendBroadcast(Intent(BROADCAST_STATE).apply {
            putExtra(EXTRA_STATE, state)
            setPackage(packageName)
        })
    }

    private fun broadcastFinished() {
        sendBroadcast(Intent(BROADCAST_FINISHED).apply {
            setPackage(packageName)
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        walkJob?.cancel()
        serviceScope.cancel()
        isWalking = false
    }
}
