package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.MapsDeepLinkHandler
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity : BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    // ─── Deep Link ────────────────────────────────────────────────────────────

    /**
     * Dipanggil saat Activity pertama kali dibuka VIA intent (termasuk deep link).
     * onCreate() sudah dipanggil sebelumnya di BaseMapActivity, jadi kita override
     * onNewIntent() untuk menangani kasus Activity sudah berjalan (singleTop/singleTask).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { uri -> handleDeepLink(uri) }
    }

    /**
     * Handle deep link dari GMaps saat Activity baru dibuka (bukan resume).
     * Dipanggil dari onMapReady() setelah peta siap.
     */
    private fun handleDeepLinkOnStart() {
        intent?.data?.let { uri -> handleDeepLink(uri) }
    }

    private fun handleDeepLink(uri: Uri) {
        val parsed = MapsDeepLinkHandler.parse(uri) ?: run {
            showToast("Format link GMaps tidak dikenali")
            return
        }
        moveToDeepLinkLocation(parsed.lat, parsed.lng, parsed.label)
    }

    /**
     * Pindahkan kamera + marker ke koordinat dari deep link.
     */
    private fun moveToDeepLinkLocation(targetLat: Double, targetLng: Double, label: String?) {
        if (!::mMap.isInitialized) return
        val target = LatLng(targetLat, targetLng)
        lat = targetLat
        lon = targetLng
        mLatLng = target
        mMarker?.apply {
            position = target
            isVisible = true
            title = label ?: "Lokasi dari GMaps"
            showInfoWindow()
        }
        mMap.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(target)
                    .zoom(17.0f)
                    .bearing(0f)
                    .tilt(0f)
                    .build()
            )
        )
        val labelText = if (!label.isNullOrBlank()) "\"$label\"" else "koordinat $targetLat, $targetLng"
        showToast("📍 Lokasi dari GMaps: $labelText")
    }

    // ─── Existing code (tidak diubah) ────────────────────────────────────────

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
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                mMap.animateCamera(
                    CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                            .target(latLng!!)
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
                    this@MapActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                setMyLocationEnabled(true)
            } else {
                ActivityCompat.requestPermissions(
                    this@MapActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    99
                )
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0, 80, 0, 0)
            mapType = viewModel.mapType

            val zoom = 17.0f
            lat = viewModel.getLat
            lon = viewModel.getLng
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

            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted) {
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }

        // Setelah peta siap, cek apakah ada deep link dari GMaps
        handleDeepLinkOnStart()
    }

    override fun onMapClick(latLng: LatLng) {
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
                lat = it.latitude
                lon = it.longitude
            }
        }
    }

    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons() {
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
    }
}
