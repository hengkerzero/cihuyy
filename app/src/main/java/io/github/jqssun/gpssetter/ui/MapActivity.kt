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
import java.util.regex.Pattern

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    private var intentLat: Double? = null
    private var intentLng: Double? = null

    // Regex untuk ekstrak koordinat: angka negatif/positif dengan desimal
    private val coordRegex = Pattern.compile("(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")

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
     * Handle semua format:
     * - geo:lat,lng
     * - geo:0,0?q=lat,lng(nama tempat, dengan koma sekalipun)
     * - https://maps.google.com/...@lat,lng,zoom
     * - https://maps.google.com/?q=lat,lng
     */
    private fun extractCoords(input: String): Pair<Double, Double>? {
        val matcher = coordRegex.matcher(input)
        while (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull() ?: continue
            val lng = matcher.group(2)?.toDoubleOrNull() ?: continue
            // Skip kalau lat/lng = 0,0 (placeholder Google Maps)
            if (lat == 0.0 && lng == 0.0) continue
            // Validasi range koordinat
            if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                return Pair(lat, lng)
            }
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

            // Prioritaskan koordinat dari intent, fallback ke koordinat tersimpan
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

            // Langsung tampilkan marker jika dari intent
            if (intentLat != null && intentLng != null) {
                mLatLng?.let { updateMarker(it) }
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
    }

    override fun onMapClick(latLng: LatLng) {
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
