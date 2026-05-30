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
            WalkState.IDLE -> {
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
                Log.d(TAG, "Start placed: $latLng")
            }
            WalkState.START_PLACED -> {
                val distance = OsrmRouteHelper.distanceBetween(walkStartLatLng!!, latLng)
                if (distance < MIN_WALK_DISTANCE_METERS) {
                    showToast("Jarak terlalu dekat! Minimal ${MIN_WALK_DISTANCE_METERS.toInt()}m")
                    return
                }

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
                fetchRouteAndDraw()
            }
            WalkState.FINISH_PLACED -> {
                showToast("Tekan Mulai, atau Reset untuk ubah rute")
            }
            WalkState.WALKING, WalkState.PAUSED -> {
                showToast("Stop dulu untuk ubah rute")
            }
        }
    }

    private fun fetchRouteAndDraw() {
        routeFetchJob?.cancel()
        routeFetchJob = lifecycleScope.launch {
            try {
                val start = walkStartLatLng ?: return@launch
                val finish = walkFinishLatLng ?: return@launch

                when (val result = OsrmRouteHelper.fetchRoute(start, finish)) {
                    is RouteResult.Success -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        val distKm = String.format("%.1f", result.distanceMeters / 1000)
                        val etaMin = (result.distanceMeters / (selectedSpeedKmh / 3.6) / 60).toInt()
                        binding.walkHintText.text = "Rute: ${distKm} km — ~${etaMin} menit"
                        showWalkReadyUI()
                    }
                    is RouteResult.Fallback -> {
                        walkRoutePoints = result.points
                        drawPolyline(result.points)
                        binding.walkHintText.text = "Garis lurus (API timeout)"
                        showToast("Timeout rute, fallback garis lurus")
                        showWalkReadyUI()
                    }
                    is RouteResult.Error -> {
                        showToast("Gagal: ${result.message}")
                        walkFinishMarker?.remove()
                        walkFinishLatLng = null
                        walkState = WalkState.START_PLACED
                        binding.walkHintText.text = "Tap FINISH lagi"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Route fetch error: ${e.message}", e)
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
        binding.walkActionButtons.visibility = View.VISIBLE
        binding.walkProgressLayout.visibility = View.GONE
        // Update ETA di hint berdasarkan speed saat ini
        updateEtaHint()
    }

    private fun updateEtaHint() {
        if (walkRoutePoints.size < 2) return
        val totalDist = walkRoutePoints.zipWithNext { a, b ->
            OsrmRouteHelper.distanceBetween(a, b)
        }.sum()
        val speedMs = selectedSpeedKmh / 3.6f
        val etaMin = if (speedMs > 0) (totalDist / speedMs / 60).toInt() else 0
        val distKm = String.format("%.1f", totalDist / 1000)
        binding.walkHintText.text = "Rute: ${distKm} km — ~${etaMin} mnt @ ${selectedSpeedKmh.toInt()} km/h"
    }

    // === Walk Controls ===

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
            walkState = WalkState.FINISH_PLACED
            return
        }

        // Update UI
        binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
        binding.walkProgressLayout.visibility = View.VISIBLE
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"
        binding.btnWalkPlay.text = "Jeda"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)
        binding.btnWalkStop.text = "Stop"
        // Disable slider saat walking
        binding.speedSlider.isEnabled = false

        showToast("Auto Walk ${selectedSpeedKmh.toInt()} km/h dimulai!")
    }

    private fun pauseWalk() {
        if (walkState != WalkState.WALKING) return
        walkState = WalkState.PAUSED

        startService(Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_PAUSE
        })

        binding.walkHintText.text = "Dijeda"
        binding.btnWalkPlay.text = "Lanjut"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
    }

    private fun resumeWalk() {
        if (walkState != WalkState.PAUSED) return
        walkState = WalkState.WALKING

        startService(Intent(this, RouteWalkService::class.java).apply {
            action = RouteWalkService.ACTION_RESUME
        })

        binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
        binding.btnWalkPlay.text = "Jeda"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)
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

    private fun resetWalkState() {
        walkState = WalkState.IDLE
        walkStartLatLng = null
        walkFinishLatLng = null
        walkRoutePoints = emptyList()
        routeFetchJob?.cancel()

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
        binding.btnWalkPlay.text = "Mulai"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
        binding.btnWalkStop.text = "Reset"
        binding.speedSlider.isEnabled = true
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
        binding.btnWalkPlay.text = "Mulai"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
        binding.speedSlider.isEnabled = true
        showToast("Auto Walk selesai!")

        walkStartMarker?.remove()
        walkFinishMarker?.remove()
        walkPolyline?.remove()
        walkStartMarker = null
        walkFinishMarker = null
        walkPolyline = null
        walkRoutePoints = emptyList()

        binding.walkActionButtons.visibility = View.GONE
        binding.walkSpeedLayout.visibility = View.GONE

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
                    binding.btnWalkPlay.text = "Lanjut"
                    binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
                }
            }
            RouteWalkService.STATE_WALKING -> {
                if (walkState != WalkState.WALKING) {
                    walkState = WalkState.WALKING
                    binding.walkHintText.text = "Walking ${selectedSpeedKmh.toInt()} km/h..."
                    binding.btnWalkPlay.text = "Jeda"
                    binding.btnWalkPlay.setIconResource(R.drawable.ic_baseline_pause_24)
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
            binding.chipWalk.isChecked = true
            return
        }
        if (appMode == AppMode.NORMAL) return

        appMode = AppMode.NORMAL
        resetWalkState()

        binding.walkControlsPanel.visibility = View.GONE

        // Cek langsung dari PrefManager (fresh state)
        val gpsActive = PrefManager.isStarted
        binding.startButton.visibility = if (gpsActive) View.GONE else View.VISIBLE
        binding.stopButton.visibility = if (gpsActive) View.VISIBLE else View.GONE
        binding.addfavorite.visibility = View.VISIBLE
        binding.favoriteList.visibility = View.VISIBLE
        binding.modeToggleCard.visibility = View.VISIBLE

        Log.d(TAG, "Mode: NORMAL (gpsActive=$gpsActive)")
    }

    private fun switchToWalkMode() {
        // Cek langsung dari PrefManager (bukan viewModel yang bisa stale)
        if (PrefManager.isStarted) {
            showToast("Stop GPS dulu")
            binding.chipNormal.isChecked = true
            return
        }
        if (appMode == AppMode.WALK) return

        appMode = AppMode.WALK

        binding.startButton.visibility = View.GONE
        binding.stopButton.visibility = View.GONE
        binding.addfavorite.visibility = View.GONE
        binding.favoriteList.visibility = View.GONE
        binding.walkControlsPanel.visibility = View.VISIBLE

        resetWalkState()
        Log.d(TAG, "Mode: WALK")
    }

    override fun getActivityInstance(): BaseMapActivity = this

    @SuppressLint("MissingPermission")
    override fun setupButtons() {
        // === Normal mode ===
        binding.favoriteList.setOnClickListener { openFavoriteListDialog() }
        binding.addfavorite.setOnClickListener { addFavoriteDialog() }
        binding.getlocation.setOnClickListener { getLastLocation() }

        // Long press getlocation = force reset semua state (solusi darurat)
        binding.getlocation.setOnLongClickListener {
            forceResetAllState()
            true
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng?.let { updateMarker(it) }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect { value -> showStartNotification(value) }
                }
            }
            showToast(getString(R.string.location_set))
        }

        binding.stopButton.setOnClickListener {
            mLatLng?.let { viewModel.update(false, it.latitude, it.longitude) }
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }

        // === Mode toggle ===
        binding.chipNormal.setOnClickListener { switchToNormalMode() }
        binding.chipWalk.setOnClickListener { switchToWalkMode() }

        // === Walk: Speed Slider ===
        binding.speedSlider.value = 5f
        binding.speedValueText.text = "5 km/h"
        selectedSpeedKmh = 5f

        binding.speedSlider.addOnChangeListener(Slider.OnChangeListener { _, value, _ ->
            selectedSpeedKmh = value
            val label = when {
                value <= 6 -> "${value.toInt()} km/h (Jalan)"
                value <= 12 -> "${value.toInt()} km/h (Lari)"
                value <= 25 -> "${value.toInt()} km/h (Sepeda)"
                else -> "${value.toInt()} km/h (Kendaraan)"
            }
            binding.speedValueText.text = label
            // Update ETA jika rute sudah ada
            if (walkState == WalkState.FINISH_PLACED) {
                updateEtaHint()
            }
        })

        // === Walk: Play/Pause button ===
        binding.btnWalkPlay.setOnClickListener {
            when (walkState) {
                WalkState.FINISH_PLACED -> startWalk()
                WalkState.WALKING -> pauseWalk()
                WalkState.PAUSED -> resumeWalk()
                else -> showToast("Pilih START dan FINISH dulu")
            }
        }

        // === Walk: Stop/Reset button ===
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
                else -> showToast("Belum ada rute")
            }
        }

        // Register receiver
        registerWalkReceiver()

        // Setup Auto-Off toggle
        setupAutoOffToggle()
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
    }

    // === Auto-Off on Order ===

    private fun setupAutoOffToggle() {
        // Load saved state
        binding.switchAutoOffOrder.isChecked = PrefManager.isAutoOffOnOrder

        binding.switchAutoOffOrder.setOnCheckedChangeListener { _, isChecked ->
            PrefManager.isAutoOffOnOrder = isChecked
            if (isChecked) {
                // Cek apakah notification listener permission sudah granted
                if (!isNotificationListenerEnabled()) {
                    showToast("Aktifkan izin Notification Access untuk fitur ini")
                    requestNotificationListenerPermission()
                } else {
                    showToast("Auto-Off aktif: GPS mati otomatis saat dapat orderan")
                }
            } else {
                showToast("Auto-Off dinonaktifkan")
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val packageName = packageName
        val flat = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        return flat != null && flat.contains(packageName)
    }

    private fun requestNotificationListenerPermission() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open notification listener settings: ${e.message}")
            showToast("Buka Settings > Apps > Notification Access secara manual")
        }
    }

    /**
     * Dipanggil saat orderan terdeteksi dan GPS sudah dimatikan oleh service.
     * Update UI agar sinkron.
     */
    private fun onOrderDetectedAutoOff() {
        Log.i(TAG, "Order detected! UI updating...")
        showToast("Orderan masuk! GPS fake dimatikan otomatis")

        // Update UI normal mode
        removeMarker()
        binding.stopButton.visibility = View.GONE
        binding.startButton.visibility = View.VISIBLE
        cancelNotification()

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
        walkStartLatLng = null
        walkFinishLatLng = null
        walkRoutePoints = emptyList()
        routeFetchJob?.cancel()
        walkStartMarker?.remove()
        walkStartMarker = null
        walkFinishMarker?.remove()
        walkFinishMarker = null
        walkPolyline?.remove()
        walkPolyline = null

        // 4. Force mode ke NORMAL
        appMode = AppMode.NORMAL
        binding.chipNormal.isChecked = true

        // 5. Reset semua UI visibility
        binding.walkControlsPanel.visibility = View.GONE
        binding.walkSpeedLayout.visibility = View.GONE
        binding.walkActionButtons.visibility = View.GONE
        binding.walkProgressLayout.visibility = View.GONE
        binding.speedSlider.isEnabled = true
        binding.btnWalkPlay.text = "Mulai"
        binding.btnWalkPlay.setIconResource(R.drawable.ic_play)
        binding.walkHintText.text = "Tap peta untuk titik START"
        binding.walkProgressBar.progress = 0
        binding.walkProgressText.text = "0%"

        // 6. Show tombol normal
        binding.startButton.visibility = View.VISIBLE
        binding.stopButton.visibility = View.GONE
        binding.addfavorite.visibility = View.VISIBLE
        binding.favoriteList.visibility = View.VISIBLE
        binding.modeToggleCard.visibility = View.VISIBLE

        // 7. Remove marker
        removeMarker()
        cancelNotification()

        showToast("State direset! Semua kembali normal.")
        Log.d(TAG, "Force reset all state complete")
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
        routeFetchJob?.cancel()
        super.onDestroy()
    }
}
