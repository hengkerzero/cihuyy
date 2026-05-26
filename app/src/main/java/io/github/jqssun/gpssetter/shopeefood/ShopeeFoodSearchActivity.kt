package io.github.jqssun.gpssetter.shopeefood

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.databinding.ActivityShopeefoodSearchBinding
import io.github.jqssun.gpssetter.shopeefood.adapter.RestaurantAdapter
import io.github.jqssun.gpssetter.shopeefood.model.Restaurant
import io.github.jqssun.gpssetter.utils.PrefManager
import org.json.JSONArray
import java.io.File

/**
 * ShopeeFood Search Activity — membaca data restoran yang di-hook dari app Shopee.
 *
 * Flow:
 * 1. User tap "ShopeeFood" di Cihuyy
 * 2. Activity ini terbuka, cek apakah sudah ada cache data restoran
 * 3. Jika belum ada: buka app Shopee ke halaman ShopeeFood, minta user browse/cari restoran
 * 4. Xposed hook di proses Shopee akan intercept response API dan simpan ke file cache
 * 5. FileObserver detect perubahan file → load data → tampilkan di RecyclerView
 * 6. User tap restoran → konfirmasi → teleport GPS
 *
 * Data disimpan oleh ShopeeHook.kt ke:
 * /sdcard/Android/data/io.github.jqssun.gpssetter/files/shopeefood_cache.json
 */
class ShopeeFoodSearchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShopeeFoodSearch"
        private const val CACHE_DIR = "/sdcard/Android/data/io.github.jqssun.gpssetter/files"
        private const val CACHE_FILE = "shopeefood_cache.json"
        private const val META_FILE = "shopeefood_meta.txt"
        const val EXTRA_TELEPORT_LAT = "extra_teleport_lat"
        const val EXTRA_TELEPORT_LNG = "extra_teleport_lng"
        const val EXTRA_TELEPORT_NAME = "extra_teleport_name"
    }

    private lateinit var binding: ActivityShopeefoodSearchBinding
    private lateinit var adapter: RestaurantAdapter
    private var fileObserver: FileObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopeefoodSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        ensureCacheDir()

        // Load data yang sudah ada (kalau ada)
        loadCachedData()

        // Mulai watch file untuk perubahan real-time
        startFileObserver()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarShopee)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbarShopee.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        adapter = RestaurantAdapter { restaurant ->
            onRestaurantClicked(restaurant)
        }
        binding.rvRestaurants.layoutManager = LinearLayoutManager(this)
        binding.rvRestaurants.adapter = adapter
    }

    private fun setupButtons() {
        // Tombol buka Shopee ShopeeFood
        binding.btnOpenShopee.setOnClickListener {
            openShopeeFood()
        }

        // Tombol refresh data
        binding.btnRefresh.setOnClickListener {
            loadCachedData()
            Toast.makeText(this, "Data di-refresh", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureCacheDir() {
        try {
            File(CACHE_DIR).mkdirs()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create cache dir: ${e.message}")
        }
    }

    /**
     * Buka app Shopee langsung ke halaman ShopeeFood.
     * Deep link: shopee://now-food atau intent ke activity Shopee.
     */
    private fun openShopeeFood() {
        try {
            // Coba deep link dulu
            val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("shopee://now-food"))
            deepLink.setPackage("com.shopee.id")
            deepLink.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (deepLink.resolveActivity(packageManager) != null) {
                startActivity(deepLink)
                Toast.makeText(this, "Buka ShopeeFood... Cari restoran lalu kembali ke sini", Toast.LENGTH_LONG).show()
            } else {
                // Fallback: buka app Shopee biasa
                val launchIntent = packageManager.getLaunchIntentForPackage("com.shopee.id")
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    Toast.makeText(this, "Buka Shopee → masuk ShopeeFood → cari restoran", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "App Shopee tidak terinstall!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open Shopee: ${e.message}")
            Toast.makeText(this, "Gagal buka Shopee: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Load data restoran dari file cache yang ditulis oleh Xposed hook.
     */
    private fun loadCachedData() {
        try {
            val cacheFile = File(CACHE_DIR, CACHE_FILE)
            if (!cacheFile.exists()) {
                showEmptyState("Belum ada data.\n\nTap \"Buka ShopeeFood\" → cari restoran di Shopee → data otomatis muncul di sini.")
                return
            }

            val json = cacheFile.readText()
            if (json.isBlank() || json == "[]") {
                showEmptyState("Cache kosong. Buka ShopeeFood dan cari restoran.")
                return
            }

            val restaurants = parseJsonToRestaurants(json)
            if (restaurants.isEmpty()) {
                showEmptyState("Tidak ada restoran valid di cache.")
                return
            }

            // Update UI
            adapter.submitList(restaurants)
            binding.rvRestaurants.visibility = View.VISIBLE
            binding.layoutEmpty.visibility = View.GONE

            // Update info meta
            val metaFile = File(CACHE_DIR, META_FILE)
            if (metaFile.exists()) {
                val metaLines = metaFile.readLines()
                val timestamp = metaLines.getOrNull(0)?.toLongOrNull() ?: 0
                val ago = getTimeAgo(timestamp)
                binding.tvDataInfo.text = "${restaurants.size} restoran • diperbarui $ago"
                binding.tvDataInfo.visibility = View.VISIBLE
            } else {
                binding.tvDataInfo.text = "${restaurants.size} restoran ditemukan"
                binding.tvDataInfo.visibility = View.VISIBLE
            }

            Log.d(TAG, "Loaded ${restaurants.size} restaurants from cache")

        } catch (e: Exception) {
            Log.e(TAG, "Error loading cache: ${e.message}", e)
            showEmptyState("Error membaca data: ${e.message}")
        }
    }

    /**
     * Parse JSON array string ke list of Restaurant.
     */
    private fun parseJsonToRestaurants(json: String): List<Restaurant> {
        val list = mutableListOf<Restaurant>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name", "")
                val address = obj.optString("address", "")
                val lat = obj.optDouble("lat", 0.0)
                val lng = obj.optDouble("lng", 0.0)

                if (lat == 0.0 && lng == 0.0) continue
                if (name.isBlank()) continue

                list.add(
                    Restaurant(
                        merchantId = "${lat}_${lng}_$i",
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lng,
                        distanceText = formatDistance(lat, lng)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error: ${e.message}")
        }
        return list
    }

    /**
     * Hitung jarak dari lokasi GPS saat ini ke restoran.
     */
    private fun formatDistance(lat: Double, lng: Double): String {
        val myLat = PrefManager.getLat
        val myLng = PrefManager.getLng
        val dist = haversine(myLat, myLng, lat, lng)
        return if (dist < 1000) {
            "${dist.toInt()} m"
        } else {
            String.format("%.1f km", dist / 1000)
        }
    }

    private fun haversine(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    private fun getTimeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60_000 -> "baru saja"
            diff < 3600_000 -> "${diff / 60_000} menit lalu"
            diff < 86400_000 -> "${diff / 3600_000} jam lalu"
            else -> "${diff / 86400_000} hari lalu"
        }
    }

    /**
     * Watch file cache untuk perubahan real-time.
     * Saat Xposed hook menulis data baru, kita langsung update UI.
     */
    private fun startFileObserver() {
        try {
            val dir = File(CACHE_DIR)
            dir.mkdirs()

            fileObserver = object : FileObserver(dir.path, CLOSE_WRITE or MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == CACHE_FILE) {
                        Log.d(TAG, "Cache file updated, reloading...")
                        handler.post { loadCachedData() }
                    }
                }
            }
            fileObserver?.startWatching()
            Log.d(TAG, "FileObserver started on $CACHE_DIR")
        } catch (e: Exception) {
            Log.w(TAG, "FileObserver failed: ${e.message}")
        }
    }

    private fun onRestaurantClicked(restaurant: Restaurant) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Teleport ke ${restaurant.name}?")
            .setMessage(
                "Alamat: ${restaurant.address.ifEmpty { "-" }}\n" +
                "Jarak: ${restaurant.distanceText}\n" +
                "Koordinat: ${restaurant.latitude}, ${restaurant.longitude}\n\n" +
                "GPS palsu akan dipindahkan ke lokasi restoran ini."
            )
            .setPositiveButton("Teleport") { _, _ ->
                doTeleport(restaurant)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doTeleport(restaurant: Restaurant) {
        PrefManager.update(true, restaurant.latitude, restaurant.longitude)

        Toast.makeText(this, "GPS → ${restaurant.name}", Toast.LENGTH_LONG).show()

        val resultIntent = intent.apply {
            putExtra(EXTRA_TELEPORT_LAT, restaurant.latitude)
            putExtra(EXTRA_TELEPORT_LNG, restaurant.longitude)
            putExtra(EXTRA_TELEPORT_NAME, restaurant.name)
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showEmptyState(message: String) {
        binding.rvRestaurants.visibility = View.GONE
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.tvEmptyMessage.text = message
        binding.tvDataInfo.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Refresh data setiap kembali dari Shopee
        loadCachedData()
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        super.onDestroy()
    }
}
