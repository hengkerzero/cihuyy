package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.slider.Slider
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.FloatingControlService
import io.github.jqssun.gpssetter.utils.OrderNotificationListener
import io.github.jqssun.gpssetter.utils.OsrmRouteHelper
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.RouteResult
import io.github.jqssun.gpssetter.utils.RouteWalkService
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.regex.Pattern

typealias CustomLatLng = LatLng

/**
 * MapActivity dengan 2 mode:
 * - NORMAL: GPS spoofing biasa (teleport ke 1 titik)
 * - WALK: Auto walk dari START ke FINISH mengikuti route jalan
 *
 * v2 Changes:
 * - Speed selector: Slider 1-80 km/h (custom)
 * - Tombol play/stop full-width dengan text
 * - Speed dikonversi langsung ke m/s dan dikirim ke service
 * - UI lebih clean dengan MaterialCardView
 */
class MapActivity : BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    companion object {
        private const val TAG = "MapActivity"
        private const val MIN_WALK_DISTANCE_METERS = 50.0
    }

    // === App Mode ===
    private enum class AppMode { NORMAL, WALK }

    // === Walk State ===
    // IDLE: belum ada titik. PLACING: minimal 1 titik ditaruh, masih bisa nambah.
    // READY: rute sudah dibuat & siap dijalankan. WALKING/PAUSED: sedang jalan.
    private enum class WalkState { IDLE, PLACING, READY, WALKING, PAUSED }

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    private var intentLat: Double? = null
    private var intentLng: Double? = null
    private var intentPlaceName: String? = null

    // Walk mode fields
    private var appMode: AppMode = AppMode.NORMAL
    private var walkState: WalkState = WalkState.IDLE
    // Daftar titik (waypoint) yang ditaruh user, urut sesuai tap.
    private val walkWaypoints = mutableListOf<LatLng>()
    private val walkWaypointMarkers = mutableListOf<Marker>()
    private var walkLoopEnabled: Boolean = false
    private var walkPolyline: Polyline? = null
    private var walkRoutePoints: List<LatLng> = emptyList()
    private var routeFetchJob: Job? = null

    // Speed dari slider (km/h)
    private var selectedSpeedKmh: Float = 5f

    // Regex
    private val coordRegex = Pattern.compile("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    private val placeNameRegex = Pattern.compile("\\(([^)]+)\\)")

    // === Order Detection Receiver ===
    private val orderDetectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == OrderNotificationListener.ACTION_ORDER_DETECTED) {
                onOrderDetectedAutoOff()
            }
        }
    }

    // === Floating Control Stop Receiver ===
    // Dikirim FloatingControlService saat user menekan tombol Stop di overlay.
    private val floatingStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingControlService.ACTION_FLOATING_STOP) {
                onFloatingStop()
            }
        }
    }

    // === Broadcast Receiver ===
    private val walkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RouteWalkService.BROADCAST_PROGRESS -> {
                    val progress = intent.getIntExtra(RouteWalkService.EXTRA_PROGRESS, 0)
                    val currentIdx = intent.getIntExtra(RouteWalkService.EXTRA_CURRENT_INDEX, 0)
                    val total = intent.getIntExtra(RouteWalkService.EXTRA_TOTAL_POINTS, 0)
                    updateWalkProgress(progress, currentIdx, total)
                }
                RouteWalkService.BROADCAST_FINISHED -> {
                    onWalkFinished()
                }
                RouteWalkService.BROADCAST_STATE -> {
                    val state = intent.getStringExtra(RouteWalkService.EXTRA_STATE)
                    onWalkStateChanged(state)
                }
            }
        }
    }

    override fun hasMarker(): Boolean {
        return mMarker?.isVisible != true
    }

    private fun updateMarker(it: LatLng) {
        mMarker?.position = it
        mMarker?.isVisible = true
    }

    private fun removeMarker() {
        mMarker?.isVisible = false
    }

    override fun initializeMap() {
        parseIncomingIntent()
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }

    private fun extractCoords(input: String): Pair<Double, Double>? {
        val matcher = coordRegex.matcher(input)
        while (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull() ?: continue
            val lng = matcher.group(2)?.toDoubleOrNull() ?: continue
            if (lat == 0.0 && lng == 0.0) continue
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                return Pair(lat, lng)
            }
        }
        return null
    }

    private fun extractPlaceName(uri: Uri): String? {
        val matcher = placeNameRegex.matcher(uri.toString())
        if (matcher.find()) return matcher.group(1)?.trim()
        val qParam = uri.getQueryParameter("q")
        if (qParam != null && !qParam.matches(Regex("-?\\d+\\.\\d+,-?\\d+.*"))) {
            return qParam.trim()
        }
        return null
    }

    private fun parseIncomingIntent() {
        val uri: Uri? = intent?.data ?: return
        Log.d(TAG, "Incoming intent URI: $uri")
        try {
            val result = extractCoords(uri.toString())
            if (result != null) {
                intentLat = result.first
                intentLng = result.second
            }
            intentPlaceName = extractPlaceName(uri!!)
        } catch (e: Exception) {
            Log.e(TAG, "Gagal parse intent URI: ${e.message}")
        }
    }

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng?.let { latLng ->
                mMap.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(latLng)
                            .zoom(17.0f)
                            .bearing(0f)
                            .tilt(0f)
                            .build()
                    )
                )
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap) {
            if (ActivityCompat.checkSelfPermission(
                    this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                isMyLocationEnabled = true
            } else {
                ActivityCompat.requestPermissions(
                    this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99
                )
            }
            isTrafficEnabled = true
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0, 80, 0, 0)
            mapType = viewModel.mapType

            val zoom = 17.0f

            if (intentLat != null && intentLng != null) {
                lat = intentLat!!
                lon = intentLng!!
            } else {
                lat = viewModel.getLat
                lon = viewModel.getLng
            }

            mLatLng = LatLng(lat, lon)
            mLatLng?.let {
                mMarker = addMarker(
                    MarkerOptions()
                        .position(it)
                        .draggable(false)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                        .visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            if (intentLat != null && intentLng != null) {
                mLatLng?.let { updateMarker(it) }
                offerSaveToFavorite()
            }

            setOnMapClickListener(this@MapActivity)
        }

        // Reconnect UI bila Auto Walk masih berjalan setelah activity dibuat ulang
        // (mis. proses di-kill OS atau opsi dev "Don't keep activities").
        reconcileWalkStateFromService()
    }

    private fun offerSaveToFavorite() {
        addFavoriteDialogWithName(intentPlaceName ?: "")
    }

    override fun onMapClick(latLng: LatLng) {
        when (appMode) {
            AppMode.NORMAL -> handleNormalMapClick(latLng)
            AppMode.WALK -> handleWalkMapClick(latLng)
        }
    }

    private fun handleNormalMapClick(latLng: LatLng) {
        mLatLng = latLng
        updateMarker(latLng)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        lat = latLng.latitude
        lon = latLng.longitude
    }

    private fun handleWalkMapClick(latLng: LatLng) {
        when (walkState) {
            WalkState.IDLE, WalkState.PLACING -> addWalkWaypoint(latLng)
            WalkState.READY -> {
                showToast("Tekan Mulai, Undo, atau Reset untuk ubah rute")
            }
            WalkState.WALKING, WalkState.PAUSED -> {
                showToast("Stop dulu untuk ubah rute")
            }
        }
    }

    /**
     * Tambah satu waypoint ke rute walk. Titik pertama = START (hijau),
     * berikutnya titik biru bernomor. Minimal 2 titik untuk bisa buat rute.
     */
    private fun addWalkWaypoint(latLng: LatLng) {
        // Cegah titik dempet dengan titik sebelumnya
        val last = walkWaypoints.lastOrNull()
        if (last != null && OsrmRouteHelper.distanceBetween(last, latLng) < MIN_WALK_DISTANCE_METERS) {
            showToast("Terlalu dekat dengan titik sebelumnya (min ${MIN_WALK_DISTANCE_METERS.toInt()}m)")
            return
        }

        walkWaypoints.add(latLng)
        val index = walkWaypoints.size
        val hue = if (index == 1) {
            BitmapDescriptorFactory.HUE_GREEN
        } else {
            BitmapDescriptorFactory.HUE_AZURE
        }
        mMap.addMarker(
            MarkerOptions()
                .position(latLng)
                .title(if (index == 1) "START" else "Titik $index")
                .icon(BitmapDescriptorFactory.defaultMarker(hue))
        )?.let { walkWaypointMarkers.add(it) }

        walkState = WalkState.PLACING
        updateWaypointUi()
    }

    /** Perbarui hint + tombol Undo sesuai jumlah titik, lalu refresh tombol aksi tengah. */
    private fun updateWaypointUi() {
        val n = walkWaypoints.size
        binding.btnWalkUndo.visibility = if (n > 0) View.VISIBLE else View.GONE
        binding.walkHintText.text = when {
            n == 0 -> "Tap peta untuk titik START"
            n == 1 -> "Tap titik lagi (bisa banyak), lalu tekan ▶ untuk buat rute"
            else -> "$n titik — tambah lagi, atau tekan ▶ untuk buat rute"
        }
        updateCenterAction()
    }

    /** Hapus titik terakhir yang ditaruh. */
    private fun undoWalkWaypoint() {
        if (walkWaypoints.isEmpty()) return
        walkWaypoints.removeAt(walkWaypoints.lastIndex)
        walkWaypointMarkers.removeAt(walkWaypointMarkers.lastIndex).remove()
        if (walkWaypoints.isEmpty()) walkState = WalkState.IDLE
        updateWaypointUi()
    }

    /**
     * Bangun rute dari semua waypoint. Jika Loop aktif, titik awal ditambahkan
     * lagi di akhir supaya rute kembali ke titik start (muterin kota lalu pulang).
     */
    private fun buildWalkRoute() {
        if (walkWaypoints.size < 2) {
            showToast("Minimal 2 titik untuk membuat rute")
            return
        }
        binding.walkHintText.text = "Mengambil rute..."
        fetchRouteAndDraw()
    }

    private fun fetchRouteAndDraw() {
        routeFetchJob?.cancel()
        routeFetchJob = lifecycleScope.launch {
            try {
                if (walkWaypoints.size < 2) return@launch

                // Susun daftar titik; tambahkan titik awal di akhir bila Loop aktif.
                val points = walkWaypoints.toMutableList()
                if (walkLoopEnabled) points.add(walkWaypoints.first())

                val profile = OsrmRouteHelper.profileForSpeed(selectedSpeedKmh)
                when (val result = OsrmRouteHelper.fetchRoute(points, profile)) {
                    is RouteResult.Success -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        walkState = WalkState.READY
                        val distKm = String.format("%.1f", result.distanceMeters / 1000)
                        val etaMin = (result.distanceMeters / (selectedSpeedKmh / 3.6) / 60).toInt()
                        val loopTxt = if (walkLoopEnabled) " (loop)" else ""
                        binding.walkHintText.text = "Rute$loopTxt siap"
                        showWalkReadyUI()
                    }
                    is RouteResult.Fallback -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        walkState = WalkState.READY
                        binding.walkHintText.text = "Garis lurus (API timeout)"
                        showToast("Timeout rute, fallback garis lurus")
                        showWalkReadyUI()
                    }
                    is RouteResult.Error -> {
                        showToast("Gagal: ${result.message}")
                        walkState = WalkState.PLACING
                        updateWaypointUi()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route fetch error: ${e.message}", e)
                binding.walkHintText.text = "Error: ${e.message}"
                walkState = WalkState.PLACING
                updateWaypointUi()
            }
        }
    }

    private fun drawPolyline(points: List<LatLng>) {
        walkPolyline?.remove()
        walkPolyline = mMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(Color.parseColor("#4285F4"))
                .width(10f)
                .geodesic(true)
        )
        if (points.size >= 2) {
            val builder = LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            try {
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 120))
            } catch (e: Exception) {
                Log.w(TAG, "Zoom to bounds failed: ${e.message}")
            }
        }
    }

    private fun showWalkReadyUI() {
        binding.walkSpeedLayout.visibility = View.VISIBLE
        binding.walkProgressLayout.visibility = View.GONE
        binding.walkStatsRow.visibility = View.VISIBLE
        binding.btnWalkReset.visibility = View.VISIBLE
        updateCenterAction()
        // Update ringkasan jarak/ETA/titik berdasarkan speed saat ini
        updateEtaHint()
    }

    /**
     * Tombol aksi tengah (center_action) bersifat kontekstual:
     * - Normal mode: Play (start GPS) / Stop (matikan GPS) — warna hijau/merah.
     * - Walk PLACING: ▶ untuk Buat Rute (abu/hijau).
     * - Walk READY: ▶ Play untuk mulai jalan.
     * - Walk WALKING: ⏸ Pause. Walk PAUSED: ▶ Resume.
     */
    private fun updateCenterAction() {
        val btn = binding.centerAction
        if (appMode == AppMode.NORMAL) {
            if (PrefManager.isStarted) {
                btn.setIconResource(R.drawable.ic_stop)
                btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.map_fab_stop)
                btn.contentDescription = getString(R.string.location_unset)
            } else {
                btn.setIconResource(R.drawable.ic_play)
                btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.map_fab_start)
                btn.contentDescription = getString(R.string.location_set)
            }
            return
        }
        // WALK mode
        when (walkState) {
            WalkState.WALKING -> {
                btn.setIconResource(R.drawable.ic_baseline_pause_24)
                btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.map_fab_background)
            }
            WalkState.READY, WalkState.PAUSED -> {
                btn.setIconResource(R.drawable.ic_play)
                btn.backgroundTintList = ContextCompat.getColorStateList(this, R.color.map_fab_start)
            }
            else -> {
                // IDLE / PLACING: ▶ untuk buat rute (aktif bila >=2 titik)
                btn.setIconResource(R.drawable.ic_play)
                val enough = walkWaypoints.size >= 2
                btn.backgroundTintList = ContextCompat.getColorStateList(
                    this, if (enough) R.color.map_fab_start else R.color.map_fab_background
                )
            }
        }
    }

    /** Aksi saat tombol tengah ditekan — kontekstual sesuai mode & state. */
    private fun onCenterActionClicked() {
        if (appMode == AppMode.NORMAL) {
            if (PrefManager.isStarted) stopNormalGps() else startNormalGps()
            return
        }
        when (walkState) {
            WalkState.IDLE, WalkState.PLACING -> {
                if (walkWaypoints.size < 2) {
                    showToast("Tap minimal 2 titik di peta dulu")
                } else {
                    buildWalkRoute()
                }
            }
            WalkState.READY -> startWalk()
            WalkState.WALKING -> pauseWalk()
            WalkState.PAUSED -> resumeWalk()
        }
    }

    private fun updateEtaHint() {
        if (walkRoutePoints.size < 2) return
        val totalDist = walkRoutePoints.zipWithNext { a, b ->
            OsrmRouteHelper.distanceBetween(a, b)
        }.sum()
        val speedMs = selectedSpeedKmh / 3.6f
        val etaMin = if (speedMs > 0) (totalDist / speedMs / 60).toInt() else 0

        // Jarak: tampilkan meter di bawah 1 km, selebihnya km.
        val distLabel = if (totalDist < 1000) {
            "${totalDist.toInt()} m"
        } else {
            "${String.format("%.1f", totalDist / 1000)} km"
        }
        // ETA: tampilkan jam+menit bila >= 60 menit.
        val etaLabel = if (etaMin >= 60) "${etaMin / 60}j ${etaMin % 60}m" else "${etaMin} mnt"

        binding.walkStatDistanceValue.text = distLabel
        binding.walkStatEtaValue.text = etaLabel
        binding.walkStatPointsValue.text = walkWaypoints.size.toString()
    }

    // === Walk Controls ===

    /**
     * Pulihkan tampilan mode WALK kalau RouteWalkService masih jalan tapi activity
     * baru dibuat ulang (rotasi sudah ditangani configChanges, ini untuk kasus
     * proses di-kill / "Don't keep activities"). Tanpa ini UI bisa "menyangkut":
     * service jalan terus tapi panel walk hilang.
     */
    private fun reconcileWalkStateFromService() {
        if (!RouteWalkService.liveActive) return
        // Sudah ke-restore sebelumnya
        if (appMode == AppMode.WALK && walkState != WalkState.IDLE) return

        val lats = RouteWalkService.liveRouteLats
        val lngs = RouteWalkService.liveRouteLngs
        if (lats == null || lngs == null || lats.size < 2 || lats.size != lngs.size) return

        Log.d(TAG, "Reconcile: walk masih berjalan, memulihkan UI")

        // Masuk mode WALK (tanpa guard "Stop GPS dulu" — walk memang sedang aktif)
        appMode = AppMode.WALK
        updateModeToggleUi()
        binding.leftFabColumn.visibility = View.GONE
        binding.rightFabColumn.visibility = View.GONE
        binding.walkControlsPanel.visibility = View.VISIBLE

        // Rebuild rute + marker + polyline
        val points = lats.indices.map { LatLng(lats[it], lngs[it]) }
        walkRoutePoints = points

        // Bersihkan marker waypoint lama, lalu pasang marker START & FINISH
        // dari rute yang dipulihkan (detail waypoint tengah tidak kritikal di sini).
        clearWaypointMarkers()
        walkWaypoints.clear()
        walkWaypoints.add(points.first())
        walkWaypoints.add(points.last())
        mMap.addMarker(
            MarkerOptions()
                .position(points.first())
                .title("START")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )?.let { walkWaypointMarkers.add(it) }
        mMap.addMarker(
            MarkerOptions()
                .position(points.last())
                .title("FINISH")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
        )?.let { walkWaypointMarkers.add(it) }
        drawPolyline(points)

        // Sembunyikan kontrol waypoint (sedang berjalan)
        binding.walkWaypointControls.visibility = View.GONE
        binding.btnWalkReset.visibility = View.VISIBLE

        // Pulihkan speed
        selectedSpeedKmh = RouteWalkService.liveSpeedKmh
        try {
            binding.speedSlider.value = selectedSpeedKmh.coerceIn(
                binding.speedSlider.valueFrom, binding.speedSlider.valueTo
            )
        } catch (e: Exception) {
            Log.w(TAG, "Set slider value gagal: ${e.message}")
        }
        binding.speedValueText.text = "${selectedSpeedKmh.toInt()} km/h"
        binding.speedSlider.isEnabled = false

        // Pulihkan progress sesuai state
        val progress = RouteWalkService.liveProgress
        binding.walkSpeedLayout.visibility = View.VISIBLE
        binding.walkProgressLayout.visibility = View.VISIBLE
        binding.walkStatsRow.visibility = View.VISIBLE
        updateEtaHint()
        binding.walkProgressBar.progress = progress
        binding.walkProgressText.text = "$progress%"

        walkState = if (RouteWalkService.livePaused) {
            binding.walkHintText.text = "Dijeda — $progress%"
            WalkState.PAUSED
        } else {
            binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
            WalkState.WALKING
        }
        updateCenterAction()
    }

    private fun startWalk() {
        if (walkState == WalkState.WALKING) {
            Log.w(TAG, "Already walking, ignoring")
            return
        }
        if (walkRoutePoints.size < 2) {
            showToast("Rute tidak valid")
            return
        }

        walkState = WalkState.WALKING

        val speedMs = (selectedSpeedKmh / 3.6).toDouble()

        val intent = Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_START
            putExtra(RouteWalkService.EXTRA_ROUTE_LATS, walkRoutePoints.map { it.latitude }.toDoubleArray())
            putExtra(RouteWalkService.EXTRA_ROUTE_LNGS, walkRoutePoints.map { it.longitude }.toDoubleArray())
            putExtra(RouteWalkService.EXTRA_SPEED_MS, speedMs)
            putExtra(RouteWalkService.EXTRA_SPEED_KMH, selectedSpeedKmh)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start service failed: ${e.message}", e)
            showToast("Gagal memulai: ${e.message}")
            walkState = WalkState.READY
            return
        }

        // Update UI
        binding.walkWaypointControls.visibility = View.GONE
        binding.btnWalkReset.visibility = View.VISIBLE
        binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
        binding.walkProgressLayout.visibility = View.VISIBLE
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"
        // Disable slider saat walking
        binding.speedSlider.isEnabled = false
        updateCenterAction()

        showToast("Auto Walk ${selectedSpeedKmh.toInt()} km/h dimulai!")
    }

    private fun pauseWalk() {
        if (walkState != WalkState.WALKING) return
        walkState = WalkState.PAUSED

        startService(Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_PAUSE
        })

        binding.walkHintText.text = "Dijeda"
        updateCenterAction()
    }

    private fun resumeWalk() {
        if (walkState != WalkState.PAUSED) return
        walkState = WalkState.WALKING

        startService(Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_RESUME
        })

        binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
        updateCenterAction()
    }

    private fun stopWalk() {
        try {
            startService(Intent(this, RouteWalkService::class.java).apply {
                action = RouteWalkService.ACTION_STOP
            })
        } catch (e: Exception) {
            Log.w(TAG, "Stop error: ${e.message}")
        }
        resetWalkState()
    }

    /** Hapus semua marker waypoint dari peta. */
    private fun clearWaypointMarkers() {
        walkWaypointMarkers.forEach { it.remove() }
        walkWaypointMarkers.clear()
    }

    private fun resetWalkState() {
        walkState = WalkState.IDLE
        walkWaypoints.clear()
        walkRoutePoints = emptyList()
        routeFetchJob?.cancel()

        clearWaypointMarkers()
        walkPolyline?.remove()
        walkPolyline = null

        // Reset UI
        binding.walkHintText.text = "Tap peta untuk titik START"
        binding.walkWaypointControls.visibility = View.VISIBLE
        binding.btnWalkUndo.visibility = View.GONE
        binding.btnWalkReset.visibility = View.GONE
        binding.walkSpeedLayout.visibility = View.GONE
        binding.walkProgressLayout.visibility = View.GONE
        binding.walkStatsRow.visibility = View.GONE
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"
        binding.speedSlider.isEnabled = true
        updateWaypointUi()
        updateCenterAction()
    }

    // === Broadcast handlers ===

    private fun updateWalkProgress(progress: Int, currentIdx: Int, total: Int) {
        binding.walkProgressBar.progress = progress
        binding.walkProgressText.text = "$progress%"
    }

    private fun onWalkFinished() {
        walkState = WalkState.IDLE
        binding.walkHintText.text = "Selesai! Tap untuk rute baru"
        binding.walkProgressBar.progress = 100
        binding.walkProgressText.text = "100%"
        binding.speedSlider.isEnabled = true
        showToast("Auto Walk selesai!")

        clearWaypointMarkers()
        walkWaypoints.clear()
        walkPolyline?.remove()
        walkPolyline = null
        walkRoutePoints = emptyList()

        binding.walkSpeedLayout.visibility = View.GONE
        binding.walkProgressLayout.visibility = View.GONE
        binding.walkStatsRow.visibility = View.GONE
        binding.walkWaypointControls.visibility = View.VISIBLE
        binding.btnWalkUndo.visibility = View.GONE
        binding.btnWalkReset.visibility = View.GONE
        updateWaypointUi()
        updateCenterAction()

        // Force reset GPS state agar tidak stuck "Stop GPS dulu"
        // Service sudah set isStarted=false, tapi untuk safety kita juga set disini
        viewModel.update(false, PrefManager.getLat, PrefManager.getLng)
    }

    private fun onWalkStateChanged(state: String?) {
        when (state) {
            RouteWalkService.STATE_PAUSED -> {
                if (walkState != WalkState.PAUSED) {
                    walkState = WalkState.PAUSED
                    binding.walkHintText.text = "Dijeda"
                    updateCenterAction()
                }
            }
            RouteWalkService.STATE_WALKING -> {
                if (walkState != WalkState.WALKING) {
                    walkState = WalkState.WALKING
                    binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
                    updateCenterAction()
                }
            }
            RouteWalkService.STATE_ERROR -> {
                showToast("Walk error!")
                resetWalkState()
            }
            RouteWalkService.STATE_FINISHED -> {
                onWalkFinished()
            }
        }
    }

    // === Mode Switching ===

    private fun switchToNormalMode() {
        if (walkState == WalkState.WALKING || walkState == WalkState.PAUSED) {
            showToast("Stop walk dulu")
            updateModeToggleUi()
            return
        }
        if (appMode == AppMode.NORMAL) return

        appMode = AppMode.NORMAL
        resetWalkState()

        binding.walkControlsPanel.visibility = View.GONE

        // Tampilkan kembali kedua kolom FAB
        binding.leftFabColumn.visibility = View.VISIBLE
        binding.rightFabColumn.visibility = View.VISIBLE

        binding.deleteMarker.visibility = View.VISIBLE
        binding.addfavorite.visibility = View.VISIBLE
        binding.favoriteList.visibility = View.VISIBLE
        updateModeToggleUi()
        // Tombol tengah jadi Play/Stop GPS sesuai status
        updateCenterAction()

        Log.d(TAG, "Mode: NORMAL (gpsActive=${PrefManager.isStarted})")
    }

    private fun switchToWalkMode() {
        // Cek langsung dari PrefManager (bukan viewModel yang bisa stale)
        if (PrefManager.isStarted) {
            showToast("Stop GPS dulu")
            updateModeToggleUi()
            return
        }
        if (appMode == AppMode.WALK) return

        appMode = AppMode.WALK

        // Sembunyikan kedua kolom FAB (tombol tengah tetap, jadi aksi Walk)
        binding.leftFabColumn.visibility = View.GONE
        binding.rightFabColumn.visibility = View.GONE
        binding.walkControlsPanel.visibility = View.VISIBLE
        updateModeToggleUi()

        resetWalkState()
        Log.d(TAG, "Mode: WALK")
    }

    /** Sinkronkan tombol toggle (Normal/Walk) dengan mode aktif tanpa memicu listener. */
    private fun updateModeToggleUi() {
        val walkActive = appMode == AppMode.WALK
        if (binding.chipWalk.isChecked != walkActive) binding.chipWalk.isChecked = walkActive
        if (binding.chipNormal.isChecked == walkActive) binding.chipNormal.isChecked = !walkActive
    }

    /**
     * Hapus marker yang sedang aktif di peta.
     * Jika GPS sedang aktif (spoofing jalan), matikan dulu agar konsisten.
     */
    private fun onDeleteMarkerClicked() {
        if (hasMarker()) {
            showToast(getString(R.string.no_marker_to_remove))
            return
        }
        if (PrefManager.isStarted) {
            mLatLng?.let { viewModel.update(false, it.latitude, it.longitude) }
            updateCenterAction()
            cancelNotification()
        }
        removeMarker()
        showToast(getString(R.string.marker_removed))
    }

    /**
     * Refresh peta: muat ulang map type & posisi kamera ke marker/lokasi terakhir.
     */
    private fun refreshMaps() {
        if (!::mMap.isInitialized) {
            showToast(getString(R.string.maps_refreshed))
            return
        }
        val markerWasVisible = mMarker?.isVisible == true
        mMap.mapType = viewModel.mapType
        mMap.clear()
        mMarker = null
        // Bangun ulang marker normal
        mLatLng = LatLng(lat, lon)
        mLatLng?.let {
            mMarker = mMap.addMarker(
                MarkerOptions()
                    .position(it)
                    .draggable(false)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                    .visible(markerWasVisible)
            )
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 17.0f))
        }
        showToast(getString(R.string.maps_refreshed))
    }

    override fun getActivityInstance(): BaseMapActivity = this

    @SuppressLint("MissingPermission")
    override fun setupButtons() {
        // === Normal mode ===
        binding.favoriteList.setOnClickListener { openFavoriteListDialog() }
        binding.addfavorite.setOnClickListener { addFavoriteDialog() }
        binding.getlocation.setOnClickListener { getLastLocation() }

        // Hapus marker yang sedang aktif di peta
        binding.deleteMarker.setOnClickListener { onDeleteMarkerClicked() }

        // Refresh / reload peta
        binding.refreshMaps.setOnClickListener { refreshMaps() }

        // Tombol pengaturan: buka dialog pengaturan ringkas
        binding.settingsButton.setOnClickListener {
            openMapSettingsDialog()
        }

        // Long press getlocation = force reset semua state (solusi darurat)
        binding.getlocation.setOnLongClickListener {
            forceResetAllState()
            true
        }

        // === Tombol aksi tengah (Play/Stop untuk Normal & Walk) ===
        binding.centerAction.setOnClickListener { onCenterActionClicked() }
        updateCenterAction()

        // === Mode toggle (Normal / Walk) via dua tombol checkable di bottom bar ===
        // Tombol tengah (Play/Stop) berada di antara keduanya. Selection diatur manual
        // karena MaterialButtonToggleGroup tidak bisa menampung tombol non-toggle di tengah.
        updateModeToggleUi()
        binding.chipNormal.setOnClickListener {
            if (appMode != AppMode.NORMAL) switchToNormalMode() else updateModeToggleUi()
        }
        binding.chipWalk.setOnClickListener {
            if (appMode != AppMode.WALK) switchToWalkMode() else updateModeToggleUi()
        }

        // === Walk: Speed Slider ===
        binding.speedSlider.value = 5f
        binding.speedValueText.text = "5 km/h"
        selectedSpeedKmh = 5f

        binding.speedSlider.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            selectedSpeedKmh = value
            val label = when {
                value <= 6 -> "${value.toInt()} km/h"
                value <= 12 -> "${value.toInt()} km/h"
                value <= 25 -> "${value.toInt()} km/h"
                else -> "${value.toInt()} km/h"
            }
            binding.speedValueText.text = label
            // Update ETA jika rute sudah dibuat
            if (walkState == WalkState.READY) {
                updateEtaHint()
            }
        })

        // === Walk: Loop / Undo / Reset ===
        binding.switchWalkLoop.setOnCheckedChangeListener { _, isChecked ->
            walkLoopEnabled = isChecked
            // Jika rute sudah dibuat, bangun ulang agar loop ikut berubah
            if (walkState == WalkState.READY) {
                buildWalkRoute()
            }
        }
        // Tap di mana saja pada baris Loop ikut men-toggle switch.
        binding.walkLoopRow.setOnClickListener {
            binding.switchWalkLoop.toggle()
        }

        binding.btnWalkUndo.setOnClickListener {
            when (walkState) {
                WalkState.PLACING, WalkState.IDLE -> undoWalkWaypoint()
                WalkState.READY -> {
                    // Kembali ke mode menaruh titik, hapus titik terakhir
                    walkState = WalkState.PLACING
                    walkPolyline?.remove()
                    walkPolyline = null
                    walkRoutePoints = emptyList()
                    binding.walkSpeedLayout.visibility = View.GONE
                    binding.walkStatsRow.visibility = View.GONE
                    binding.btnWalkReset.visibility = View.GONE
                    undoWalkWaypoint()
                }
                else -> showToast("Tidak bisa undo saat berjalan")
            }
        }

        binding.btnWalkReset.setOnClickListener {
            when (walkState) {
                WalkState.WALKING, WalkState.PAUSED -> {
                    stopWalk()
                    showToast("Walk dihentikan")
                }
                else -> {
                    resetWalkState()
                    showToast("Rute direset")
                }
            }
        }

        // Register receiver
        registerWalkReceiver()
    }

    /** Mulai spoofing GPS di mode Normal. */
    private fun startNormalGps() {
        viewModel.update(true, lat, lon)
        mLatLng?.let { updateMarker(it) }
        updateCenterAction()
        lifecycleScope.launch {
            mLatLng?.getAddress(getActivityInstance())?.let { address ->
                address.collect { value -> showStartNotification(value) }
            }
        }
        showToast(getString(R.string.location_set))
    }

    /** Matikan spoofing GPS di mode Normal. */
    private fun stopNormalGps() {
        mLatLng?.let { viewModel.update(false, it.latitude, it.longitude) }
        removeMarker()
        updateCenterAction()
        cancelNotification()
        hideFloatingControl()
        showToast(getString(R.string.location_unset))
    }

    private fun registerWalkReceiver() {
        val filter = IntentFilter().apply {
            addAction(RouteWalkService.BROADCAST_PROGRESS)
            addAction(RouteWalkService.BROADCAST_FINISHED)
            addAction(RouteWalkService.BROADCAST_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(walkReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(walkReceiver, filter)
        }

        // Register order detected receiver
        val orderFilter = IntentFilter(OrderNotificationListener.ACTION_ORDER_DETECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(orderDetectedReceiver, orderFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(orderDetectedReceiver, orderFilter)
        }

        // Register floating control stop receiver
        val floatingFilter = IntentFilter(FloatingControlService.ACTION_FLOATING_STOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(floatingStopReceiver, floatingFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(floatingStopReceiver, floatingFilter)
        }
    }

    // === Floating Control ===

    /**
     * Tampilkan floating control overlay (jika izin overlay ada & GPS Normal aktif).
     * Dipanggil saat app masuk background supaya user tetap bisa refresh/stop dari
     * luar app tanpa harus buka MapActivity lagi.
     */
    private fun showFloatingControl() {
        if (appMode != AppMode.NORMAL || !PrefManager.isStarted) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        try {
            startService(
                Intent(this, FloatingControlService::class.java).apply {
                    action = FloatingControlService.ACTION_SHOW
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Show floating gagal: ${e.message}")
        }
    }

    /** Sembunyikan floating control overlay. */
    private fun hideFloatingControl() {
        try {
            stopService(Intent(this, FloatingControlService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "Hide floating gagal: ${e.message}")
        }
    }

    /**
     * Dipanggil saat user menekan Stop di floating overlay.
     * Hook sudah dimatikan oleh service; di sini cukup sinkronkan UI activity.
     */
    private fun onFloatingStop() {
        removeMarker()
        updateCenterAction()
        cancelNotification()
        hideFloatingControl()
    }

    // === Auto-Off on Order ===

    /**
     * Dipanggil saat orderan terdeteksi dan GPS sudah dimatikan oleh service.
     * Update UI agar sinkron.
     */
    private fun onOrderDetectedAutoOff() {
        Log.i(TAG, "Order detected! UI updating...")
        showToast("Orderan masuk! GPS fake dimatikan otomatis")

        // Update UI normal mode
        removeMarker()
        updateCenterAction()
        cancelNotification()
        hideFloatingControl()

        // Jika sedang walk mode, reset walk state
        if (appMode == AppMode.WALK) {
            resetWalkState()
        }
    }

    /**
     * Force reset semua state: GPS, walk, UI.
     * Dipanggil via long-press tombol lokasi sebagai "emergency reset".
     * Berguna saat state jadi stuck (tombol hilang, mode ga bisa switch, dll).
     */
    private fun forceResetAllState() {
        // 1. Stop walk service jika masih jalan
        try {
            startService(Intent(this, RouteWalkService::class.java).apply {
                action = RouteWalkService.ACTION_STOP
            })
        } catch (_: Exception) {}

        // 2. Force reset PrefManager — GPS off
        PrefManager.update(false, PrefManager.getLat, PrefManager.getLng)

        // 3. Reset walk state
        walkState = WalkState.IDLE
        walkWaypoints.clear()
        walkRoutePoints = emptyList()
        routeFetchJob?.cancel()
        clearWaypointMarkers()
        walkPolyline?.remove()
        walkPolyline = null

        // 4. Force mode ke NORMAL
        appMode = AppMode.NORMAL
        updateModeToggleUi()

        // 5. Reset semua UI visibility
        binding.walkControlsPanel.visibility = View.GONE
        binding.walkWaypointControls.visibility = View.VISIBLE
        binding.btnWalkUndo.visibility = View.GONE
        binding.btnWalkReset.visibility = View.GONE
        binding.walkSpeedLayout.visibility = View.GONE
        binding.walkProgressLayout.visibility = View.GONE
        binding.walkStatsRow.visibility = View.GONE
        binding.speedSlider.isEnabled = true
        binding.walkHintText.text = "Tap peta untuk titik START"
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"

        // 6. Show tombol normal
        binding.leftFabColumn.visibility = View.VISIBLE
        binding.rightFabColumn.visibility = View.VISIBLE
        binding.deleteMarker.visibility = View.VISIBLE
        binding.addfavorite.visibility = View.VISIBLE
        binding.favoriteList.visibility = View.VISIBLE
        updateCenterAction()

        // 7. Remove marker
        removeMarker()
        cancelNotification()
        hideFloatingControl()

        showToast("State direset! Semua kembali normal.")
        Log.d(TAG, "Force reset all state complete")
    }

    override fun onStart() {
        super.onStart()
        // App kembali ke depan: sembunyikan floating control.
        hideFloatingControl()
    }

    override fun onStop() {
        super.onStop()
        // App masuk background: kalau GPS Normal masih aktif, tampilkan floating
        // control agar user bisa refresh/stop dari luar app.
        if (!isFinishing) {
            showFloatingControl()
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(walkReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(orderDetectedReceiver)
        } catch (_: Exception) {
        }
        try {
            unregisterReceiver(floatingStopReceiver)
        } catch (_: Exception) {
        }
        hideFloatingControl()
        routeFetchJob?.cancel()
        super.onDestroy()
    }
}
