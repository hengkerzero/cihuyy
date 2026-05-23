package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
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
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    // Koordinat dari intent luar (geo: URI / maps link)
    private var intentLat: Double? = null
    private var intentLng: Double? = null

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
        // Parse intent URI sebelum map diinisialisasi
        parseIncomingIntent()

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }

    /**
     * Parse URI dari intent luar:
     * - geo:lat,lng
     * - geo:lat,lng?q=label
     * - geo:0,0?q=lat,lng(label)
     * - https://maps.google.com/...
     * - https://goo.gl/maps/...
     */
    private fun parseIncomingIntent() {
        val uri: Uri? = intent?.data
        if (uri == null) return

        Log.d("MapActivity", "Incoming intent URI: $uri")

        try {
            when (uri.scheme) {
                "geo" -> {
                    val ssp = uri.schemeSpecificPart
                    val coords = ssp.split("?").first().split(",")
                    val parsedLat = coords.getOrNull(0)?.toDoubleOrNull()
                    val parsedLng = coords.getOrNull(1)?.toDoubleOrNull()

                    if (parsedLat != null && parsedLng != null &&
                        !(parsedLat == 0.0 && parsedLng == 0.0)) {
                        intentLat = parsedLat
                        intentLng = parsedLng
                    } else {
                        val query = uri.getQueryParameter("q")
                        if (query != null) {
                            val qCoords = query.split(",")
                            intentLat = qCoords.getOrNull(0)?.toDoubleOrNull()
                            intentLng = qCoords.getOrNull(1)?.split("(")?.firstOrNull()?.toDoubleOrNull()
                        }
                    }
                }
                "https", "http" -> {
                    val qParam = uri.getQueryParameter("q")
                    if (qParam != null) {
                        val parts = qParam.split(",")
                        intentLat = parts.getOrNull(0)?.toDoubleOrNull()
                        intentLng = parts.getOrNull(1)?.toDoubleOrNull()
                    }

                    if (intentLat == null) {
                        val atSign = uri.toString().substringAfter("@", "")
                        if (atSign.isNotEmpty()) {
                            val parts = atSign.split(",")
                            intentLat = parts.getOrNull(0)?.toDoubleOrNull()
                            intentLng = parts.getOrNull(1)?.toDoubleOrNull()
                        }
                    }
                }
            }

            if (intentLat != null && intentLng != null) {
                Log.d("MapActivity", "Parsed location from intent: lat=$intentLat, lon=$intentLng")
            } else {
                Log.d("MapActivity", "Tidak ada koordinat valid dari intent, pakai lokasi tersimpan")
            }
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
        with(mMap){
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                setMyLocationEnabled(true)
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99)
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            mapType = viewModel.mapType

            val zoom = 17.0f

            // Prioritaskan koordinat dari intent, fallback ke koordinat tersimpan
            if (intentLat != null && intentLng != null) {
                lat = intentLat!!
                lon = intentLng!!
                Log.d("MapActivity", "onMapReady: pakai koordinat intent lat=$lat lon=$lon")
            } else {
                lat = viewModel.getLat
                lon = viewModel.getLng
                Log.d("MapActivity", "onMapReady: pakai koordinat tersimpan lat=$lat lon=$lon")
            }

            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            // Jika dari intent luar, langsung tampilkan marker di koordinat tujuan
            if (intentLat != null && intentLng != null) {
                mLatLng?.let { updateMarker(it) }
                showToast(getString(R.string.location_set))
            }

            setOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
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
    override fun setupButtons(){
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
                    address.collect{ value ->
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
