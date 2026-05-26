package io.github.jqssun.gpssetter.shopeefood

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.databinding.ActivityShopeefoodSearchBinding
import io.github.jqssun.gpssetter.shopeefood.adapter.RestaurantAdapter
import io.github.jqssun.gpssetter.shopeefood.model.Restaurant
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.launch

/**
 * Activity untuk mencari restoran dari ShopeeFood dan teleport GPS ke lokasi yang dipilih.
 *
 * Flow:
 * 1. User ketik keyword (nama resto / jenis makanan)
 * 2. App query ke ShopeeFood API berdasarkan lokasi saat ini di peta
 * 3. Tampilkan daftar hasil
 * 4. User tap salah satu restoran
 * 5. Konfirmasi dialog → Teleport GPS ke koordinat restoran tersebut
 */
class ShopeeFoodSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShopeefoodSearchBinding
    private lateinit var adapter: RestaurantAdapter

    // Anchor location: posisi terakhir dari peta / GPS
    private var anchorLat: Double = 0.0
    private var anchorLng: Double = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopeefoodSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ambil lokasi anchor dari PrefManager (lokasi terakhir di peta)
        anchorLat = PrefManager.getLat
        anchorLng = PrefManager.getLng

        setupToolbar()
        setupRecyclerView()
        setupSearch()
        updateLocationInfo()
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

    private fun setupSearch() {
        // Tombol Cari
        binding.btnSearch.setOnClickListener {
            performSearch()
        }

        // IME Action Search (tekan Enter di keyboard)
        binding.etSearchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                return@setOnEditorActionListener true
            }
            false
        }
    }

    private fun updateLocationInfo() {
        binding.tvSearchInfo.text = "Pencarian dari: ${String.format("%.4f", anchorLat)}, ${String.format("%.4f", anchorLng)}"
    }

    private fun performSearch() {
        val keyword = binding.etSearchKeyword.text.toString().trim()
        if (keyword.isEmpty()) {
            Toast.makeText(this, "Masukkan kata kunci pencarian", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearchKeyword.windowToken, 0)

        // Show loading
        showLoading(true)
        showEmpty(false)

        lifecycleScope.launch {
            val result = ShopeeFoodApiHelper.searchRestaurants(
                keyword = keyword,
                latitude = anchorLat,
                longitude = anchorLng
            )

            showLoading(false)

            when (result) {
                is ShopeeFoodResult.Success -> {
                    adapter.submitList(result.restaurants)
                    binding.rvRestaurants.visibility = View.VISIBLE
                    showEmpty(false)
                }
                is ShopeeFoodResult.Empty -> {
                    adapter.submitList(emptyList())
                    binding.rvRestaurants.visibility = View.GONE
                    showEmpty(true, "Tidak ada restoran ditemukan untuk \"$keyword\"")
                }
                is ShopeeFoodResult.Error -> {
                    adapter.submitList(emptyList())
                    binding.rvRestaurants.visibility = View.GONE
                    showEmpty(true, result.message)
                    Toast.makeText(this@ShopeeFoodSearchActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Ketika user tap restoran dari daftar.
     * Tampilkan konfirmasi dialog, lalu teleport GPS ke koordinat restoran.
     */
    private fun onRestaurantClicked(restaurant: Restaurant) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Teleport ke Restoran?")
            .setMessage(
                "Nama: ${restaurant.name}\n" +
                        "Alamat: ${restaurant.address}\n" +
                        "Jarak: ${restaurant.distanceText}\n\n" +
                        "Koordinat: ${restaurant.latitude}, ${restaurant.longitude}\n\n" +
                        "GPS palsu akan dipindahkan ke lokasi restoran ini."
            )
            .setPositiveButton("Teleport") { _, _ ->
                teleportToRestaurant(restaurant)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * === FAKE GPS INTEGRATION POINT ===
     *
     * Di sinilah GPS palsu diaktifkan ke koordinat restoran yang dipilih.
     * Menggunakan PrefManager.update() yang sama seperti fitur teleport biasa.
     */
    private fun teleportToRestaurant(restaurant: Restaurant) {
        // Update GPS palsu ke lokasi restoran
        PrefManager.update(
            start = true,
            la = restaurant.latitude,
            ln = restaurant.longitude
        )

        Toast.makeText(
            this,
            "GPS dipindahkan ke: ${restaurant.name}",
            Toast.LENGTH_LONG
        ).show()

        // Set result agar MapActivity tahu ada perubahan lokasi
        val resultIntent = intent.apply {
            putExtra(EXTRA_TELEPORT_LAT, restaurant.latitude)
            putExtra(EXTRA_TELEPORT_LNG, restaurant.longitude)
            putExtra(EXTRA_TELEPORT_NAME, restaurant.name)
        }
        setResult(RESULT_OK, resultIntent)

        // Kembali ke MapActivity
        finish()
    }

    private fun showLoading(show: Boolean) {
        binding.progressSearch.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showEmpty(show: Boolean, message: String? = null) {
        binding.layoutEmpty.visibility = if (show) View.VISIBLE else View.GONE
        if (message != null) {
            binding.tvEmptyMessage.text = message
        }
    }

    companion object {
        const val EXTRA_TELEPORT_LAT = "extra_teleport_lat"
        const val EXTRA_TELEPORT_LNG = "extra_teleport_lng"
        const val EXTRA_TELEPORT_NAME = "extra_teleport_name"
    }
}
