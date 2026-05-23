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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.OsrmRouteHelper
import io.github.jqssun.gpssetter.utils.RouteResult
import io.github.jqssun.gpssetter.utils.RouteWalkService
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.regex.Pattern

typealias CustomLatLng = LatLng

/**
 * MapActivity dengan 2 mode:
 * - NORMAL: GPS spoofing biasa (teleport ke 1 titik)
 * - WALK: Auto walk dari START ke FINISH mengikuti route jalan
 *
 * Worst-case handling:
 * - Switch mode dikunci saat GPS normal aktif
 * - Switch mode dikunci saat walk masih berjalan/pause
 * - Tap peta diabaikan saat sedang walking
 * - Double tap play dicegah
 * - Jarak start-finish minimal 50 meter
 * - Fetch route lama dibatalkan saat reset
 * - Progress state pause disync dari service
 * - Receiver unregister di onDestroy untuk prevent memory leak
 */
class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    companion object {
        private const val TAG = "MapActivity"
        private const val MIN_WALK_DISTANCE_METERS = 50.0
    }

    // === App Mode ===
    private enum class AppMode { NORMAL, WALK }

    // === Walk State ===
    private enum class WalkState { IDLE, START_PLACED, FINISH_PLACED, WALKING, PAUSED }

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    private var intentLat: Double? = null
    private var intentLng: Double? = null
    private var intentPlaceName: String? = null

    // Walk mode fields
    private var appMode: AppMode = AppMode.NORMAL
    private var walkState: WalkState = WalkState.IDLE
    private var walkStartLatLng: LatLng? = null
    private var walkFinishLatLng: LatLng? = null
    private var walkStartMarker: Marker? = null
    private var walkFinishMarker: Marker? = null
    private var walkPolyline: Polyline? = null
    private var walkRoutePoints: List<LatLng> = emptyList()
    private var selectedSpeedMs: Double = RouteWalkService.SPEED_WALK
    private var routeFetchJob: Job? = null

    // Regex untuk ekstrak koordinat: angka negatif/positif dengan desimal
    private val coordRegex = Pattern.compile("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
    // Regex untuk ekstrak nama tempat dari geo:0,0?q=lat,lng(Nama Tempat)
    private val placeNameRegex = Pattern.compile("\\(([^)]+)\\)")

    // === Broadcast Receiver untuk progress dari RouteWalkService ===
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
        if (!mMarker?.isVisible!!) {
            return true
        }
        return false
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

    /**
     * Ekstrak koordinat dari string apapun menggunakan regex.
     */
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

    /**
     * Ekstrak nama tempat dari URI.
     */
    private fun extractPlaceName(uri: Uri): String? {
        val matcher = placeNameRegex.matcher(uri.toString())
        if (matcher.find()) {
            return matcher.group(1)?.trim()
        }
        val qParam = uri.getQueryParameter("q")
        if (qParam != null && !qParam.matches(Regex("-?\\d+\\.\\d+,-?\\d+.*"))) {
            return qParam.trim()
        }
        return null
    }

    private fun parseIncomingIntent() {
        val uri: Uri? = intent?.data
        if (uri == null) return

        Log.d("MapActivity", "Incoming intent URI: $uri")

        try {
            val result = extractCoords(uri.toString())
            if (result != null) {
                intentLat = result.first
                intentLng = result.second
                Log.d("MapActivity", "Parsed OK: lat=$intentLat lng=$intentLng")
            } else {
                Log.d("MapActivity", "Tidak ada koordinat valid dari URI")
            }

            intentPlaceName = extractPlaceName(uri)
            Log.d("MapActivity", "Place name: $intentPlaceName")
        } catch (e: Exception) {
            Log.e("MapActivity", "Gagal parse intent URI: ${e.message}")
        }
    }

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(17.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
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
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                setMyLocationEnabled(true)
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
            }
            setTrafficEnabled(true)
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
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions()
                        .position(it!!)
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
            if (viewModel.isStarted) {
                mMarker?.let {
                    // marker visible handled elsewhere
                }
            }
        }
    }

    private fun offerSaveToFavorite() {
        val name = intentPlaceName ?: ""
        addFavoriteDialogWithName(name)
    }

    override fun onMapClick(latLng: LatLng) {
        when (appMode) {
            AppMode.NORMAL -> handleNormalMapClick(latLng)
            AppMode.WALK -> handleWalkMapClick(latLng)
        }
    }

    private fun handleNormalMapClick(latLng: LatLng) {
        mLatLng = latLng
        mMarker?.let {
            mLatLng.let {
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
    }

    /**
     * Handle tap peta di Walk mode.
     * - IDLE: set START
     * - START_PLACED: set FINISH, fetch route
     * - FINISH_PLACED/WALKING/PAUSED: ignore
     */
    private fun handleWalkMapClick(latLng: LatLng) {
        when (walkState) {
            WalkState.IDLE -> {
                // Place start marker
                walkStartLatLng = latLng
                walkStartMarker?.remove()
                walkStartMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("START")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                )
                walkState = WalkState.START_PLACED
                binding.walkHintText.text = "Tap peta untuk titik FINISH"
                Log.d(TAG, "Start placed at: $latLng")
            }
            WalkState.START_PLACED -> {
                // Validasi jarak minimum
                val distance = OsrmRouteHelper.distanceBetween(walkStartLatLng!!, latLng)
                if (distance < MIN_WALK_DISTANCE_METERS) {
                    showToast("Jarak terlalu dekat! Minimal ${MIN_WALK_DISTANCE_METERS.toInt()} meter")
                    return
                }

                // Place finish marker
                walkFinishLatLng = latLng
                walkFinishMarker?.remove()
                walkFinishMarker = mMap.addMarker(
                    MarkerOptions()
                        .position(latLng)
                        .title("FINISH")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                )
                walkState = WalkState.FINISH_PLACED
                binding.walkHintText.text = "Mengambil rute..."

                // Fetch route
                fetchRouteAndDraw()
            }
            WalkState.FINISH_PLACED -> {
                // Ignore, user harus play atau reset dulu
                showToast("Tekan Play untuk mulai, atau Stop untuk reset")
            }
            WalkState.WALKING, WalkState.PAUSED -> {
                // Ignore tap saat walking
                showToast("Walk sedang berjalan, stop dulu untuk ubah rute")
            }
        }
    }

    private fun fetchRouteAndDraw() {
        // Cancel previous fetch if any
        routeFetchJob?.cancel()

        routeFetchJob = lifecycleScope.launch {
            try {
                val start = walkStartLatLng ?: return@launch
                val finish = walkFinishLatLng ?: return@launch

                val result = OsrmRouteHelper.fetchRoute(start, finish)

                when (result) {
                    is RouteResult.Success -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        val distKm = String.format("%.1f", result.distanceMeters / 1000)
                        binding.walkHintText.text = "Rute: ${distKm} km (${result.points.size} titik)"
                        showWalkReadyUI()
                    }
                    is RouteResult.Fallback -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        binding.walkHintText.text = "⚠️ Fallback garis lurus: ${result.reason}"
                        showToast("Timeout rute, menggunakan garis lurus")
                        showWalkReadyUI()
                    }
                    is RouteResult.Error -> {
                        binding.walkHintText.text = "❌ Gagal: ${result.message}"
                        showToast("Gagal ambil rute: ${result.message}")
                        // Reset ke START_PLACED agar user bisa coba lagi
                        walkFinishMarker?.remove()
                        walkFinishLatLng = null
                        walkState = WalkState.START_PLACED
                        binding.walkHintText.text = "Tap peta untuk titik FINISH (coba lagi)"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route fetch exception: ${e.message}", e)
                binding.walkHintText.text = "Error: ${e.message}"
                walkState = WalkState.START_PLACED
            }
        }
    }

    private fun drawPolyline(points: List<LatLng>) {
        walkPolyline?.remove()
        walkPolyline = mMap.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(Color.parseColor("#4285F4"))
                .width(8f)
                .geodesic(true)
        )
        // Zoom to show full route
        if (points.size >= 2) {
            val builder = com.google.android.gms.maps.model.LatLngBounds.Builder()
            points.forEach { builder.include(it) }
            try {
                val bounds = builder.build()
                mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to zoom to bounds: ${e.message}")
            }
        }
    }

    private fun showWalkReadyUI() {
        binding.walkSpeedLayout.visibility = View.VISIBLE
        binding.walkActionButtons.visibility = View.VISIBLE
        binding.walkProgressLayout.visibility = View.GONE
    }

    // === Walk Controls ===

    private fun startWalk() {
        if (walkState == WalkState.WALKING) {
            Log.w(TAG, "Already walking, ignoring double play")
            return
        }

        if (walkRoutePoints.size < 2) {
            showToast("Rute tidak valid")
            return
        }

        walkState = WalkState.WALKING

        // Prepare intent
        val intent = Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_START
            putExtra(RouteWalkService.EXTRA_ROUTE_LATS, walkRoutePoints.map { it.latitude }.toDoubleArray())
            putExtra(RouteWalkService.EXTRA_ROUTE_LNGS, walkRoutePoints.map { it.longitude }.toDoubleArray())
            putExtra(RouteWalkService.EXTRA_SPEED_MS, selectedSpeedMs)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start walk service: ${e.message}", e)
            showToast("Gagal memulai walk: ${e.message}")
            walkState = WalkState.FINISH_PLACED
            return
        }

        // Update UI
        binding.walkHintText.text = "🚶 Walking..."
        binding.walkProgressLayout.visibility = View.VISIBLE
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)

        showToast("Auto Walk dimulai!")
    }

    private fun pauseWalk() {
        if (walkState != WalkState.WALKING) return
        walkState = WalkState.PAUSED

        val intent = Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_PAUSE
        }
        startService(intent)

        binding.walkHintText.text = "⏸️ Dijeda"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
    }

    private fun resumeWalk() {
        if (walkState != WalkState.PAUSED) return
        walkState = WalkState.WALKING

        val intent = Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_RESUME
        }
        startService(intent)

        binding.walkHintText.text = "🚶 Walking..."
        binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)
    }

    private fun stopWalk() {
        val intent = Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_STOP
        }
        try {
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Stop service error (may already be stopped): ${e.message}")
        }

        resetWalkState()
    }

    private fun resetWalkState() {
        walkState = WalkState.IDLE
        walkStartLatLng = null
        walkFinishLatLng = null
        walkRoutePoints = emptyList()
        routeFetchJob?.cancel()

        // Remove markers and polyline
        walkStartMarker?.remove()
        walkStartMarker = null
        walkFinishMarker?.remove()
        walkFinishMarker = null
        walkPolyline?.remove()
        walkPolyline = null

        // Reset UI
        binding.walkHintText.text = "Tap peta untuk titik START"
        binding.walkSpeedLayout.visibility = View.GONE
        binding.walkActionButtons.visibility = View.GONE
        binding.walkProgressLayout.visibility = View.GONE
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
    }

    // === Broadcast handlers ===

    private fun updateWalkProgress(progress: Int, currentIdx: Int, total: Int) {
        binding.walkProgressBar.progress = progress
        binding.walkProgressText.text = "$progress% ($currentIdx/$total)"
    }

    private fun onWalkFinished() {
        walkState = WalkState.IDLE
        binding.walkHintText.text = "✅ Selesai! Tap untuk rute baru"
        binding.walkProgressBar.progress = 100
        binding.walkProgressText.text = "100%"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
        showToast("Auto Walk selesai!")

        // Bersihkan markers dan polyline setelah delay
        walkStartMarker?.remove()
        walkFinishMarker?.remove()
        walkPolyline?.remove()
        walkStartMarker = null
        walkFinishMarker = null
        walkPolyline = null
        walkRoutePoints = emptyList()

        // Hide action buttons, keep progress visible briefly
        binding.walkActionButtons.visibility = View.GONE
        binding.walkSpeedLayout.visibility = View.GONE
    }

    private fun onWalkStateChanged(state: String?) {
        when (state) {
            RouteWalkService.STATE_PAUSED -> {
                if (walkState != WalkState.PAUSED) {
                    walkState = WalkState.PAUSED
                    binding.walkHintText.text = "⏸️ Dijeda"
                    binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
                }
            }
            RouteWalkService.STATE_WALKING -> {
                if (walkState != WalkState.WALKING) {
                    walkState = WalkState.WALKING
                    binding.walkHintText.text = "🚶 Walking..."
                    binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)
                }
            }
            RouteWalkService.STATE_ERROR -> {
                showToast("Walk error! Service berhenti")
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
            showToast("Stop walk dulu sebelum pindah mode")
            binding.chipWalk.isChecked = true
            return
        }
        if (appMode == AppMode.NORMAL) return

        appMode = AppMode.NORMAL
        resetWalkState()

        // Show normal UI, hide walk UI
        binding.walkControlsPanel.visibility = View.GONE
        binding.startButton.visibility = if (viewModel.isStarted) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (viewModel.isStarted) View.VISIBLE else View.GONE
        binding.addfavorite.visibility = View.VISIBLE
        binding.favoriteList.visibility = View.VISIBLE

        Log.d(TAG, "Switched to NORMAL mode")
    }

    private fun switchToWalkMode() {
        // Prevent switch if normal GPS is active
        if (viewModel.isStarted) {
            showToast("Stop GPS dulu sebelum pindah ke Walk mode")
            binding.chipNormal.isChecked = true
            return
        }
        if (appMode == AppMode.WALK) return

        appMode = AppMode.WALK

        // Hide normal UI, show walk UI
        binding.startButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE
        binding.addfavorite.visibility = View.GONE
        binding.favoriteList.visibility = View.GONE
        binding.walkControlsPanel.visibility = View.VISIBLE

        resetWalkState()

        Log.d(TAG, "Switched to WALK mode")
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons() {
        // === Normal mode buttons ===
        binding.favoriteList.setOnClickListener {
            openFavoriteListDialog()
        }
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect { value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            mLatLng.let {
                viewModel.update(false, it!!.latitude, it.longitude)
            }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }

        // === Mode toggle ===
        binding.chipNormal.setOnClickListener { switchToNormalMode() }
        binding.chipWalk.setOnClickListener { switchToWalkMode() }

        // === Walk mode buttons ===
        binding.btnWalkPlay.setOnClickListener {
            when (walkState) {
                WalkState.FINISH_PLACED -> startWalk()
                WalkState.WALKING -> pauseWalk()
                WalkState.PAUSED -> resumeWalk()
                else -> {
                    showToast("Pilih titik START dan FINISH dulu")
                }
            }
        }

        binding.btnWalkStop.setOnClickListener {
            when (walkState) {
                WalkState.WALKING, WalkState.PAUSED -> {
                    stopWalk()
                    showToast("Walk dihentikan")
                }
                WalkState.FINISH_PLACED, WalkState.START_PLACED -> {
                    resetWalkState()
                    showToast("Rute direset")
                }
                else -> {
                    showToast("Belum ada yang perlu dihentikan")
                }
            }
        }

        // === Speed selector ===
        binding.speedWalk.setOnClickListener { selectedSpeedMs = RouteWalkService.SPEED_WALK }
        binding.speedRun.setOnClickListener { selectedSpeedMs = RouteWalkService.SPEED_RUN }
        binding.speedBike.setOnClickListener { selectedSpeedMs = RouteWalkService.SPEED_BIKE }
        binding.speedCar.setOnClickListener { selectedSpeedMs = RouteWalkService.SPEED_CAR }

        // Register broadcast receiver
        registerWalkReceiver()
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
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(walkReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered: ${e.message}")
        }
        routeFetchJob?.cancel()
        super.onDestroy()
    }
}
