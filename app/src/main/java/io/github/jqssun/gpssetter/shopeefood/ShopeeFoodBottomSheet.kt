package io.github.jqssun.gpssetter.shopeefood

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bottom Sheet untuk pencarian restoran ShopeeFood.
 *
 * Flow:
 * 1. User ketik keyword
 * 2. Tekan search / enter
 * 3. API dipanggil
 * 4. Hasil ditampilkan sebagai list
 * 5. User tap salah satu → callback ke activity dengan lat/lng
 */
class ShopeeFoodBottomSheet : BottomSheetDialogFragment() {

    /**
     * Callback saat user memilih restoran.
     * Parameter: latitude, longitude, nama restoran
     */
    var onRestaurantSelected: ((Double, Double, String) -> Unit)? = null

    private lateinit var searchEditText: TextInputEditText
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var statusText: TextView
    private lateinit var recyclerView: RecyclerView

    private val adapter = RestaurantAdapter { restaurant ->
        // User tap restoran → kirim koordinat ke callback
        onRestaurantSelected?.invoke(
            restaurant.latitude,
            restaurant.longitude,
            restaurant.name
        )
        dismiss()
    }

    private var searchJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_shopeefood, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchEditText = view.findViewById(R.id.search_edit_text)
        progressBar = view.findViewById(R.id.search_progress)
        statusText = view.findViewById(R.id.search_status_text)
        recyclerView = view.findViewById(R.id.search_results_rv)

        // Setup RecyclerView
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        // Search on IME action
        searchEditText.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch(v.text.toString().trim())
                true
            } else false
        }
    }

    override fun onStart() {
        super.onStart()
        // Expand bottom sheet to half screen
        val behavior = BottomSheetBehavior.from(requireView().parent as View)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7).toInt()
    }

    private fun performSearch(keyword: String) {
        if (keyword.isBlank()) {
            statusText.text = "Ketik nama restoran dulu"
            statusText.visibility = View.VISIBLE
            return
        }

        // Cancel previous search
        searchJob?.cancel()

        // Get current user location for distance sorting
        val userLat = PrefManager.getLat
        val userLng = PrefManager.getLng

        // Show loading
        progressBar.visibility = View.VISIBLE
        statusText.text = "Mencari \"$keyword\"..."
        statusText.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        searchJob = lifecycleScope.launch {
            try {
                val response = ShopeeFoodApi.searchRestaurants(keyword, userLat, userLng)

                progressBar.visibility = View.GONE

                if (response.restaurants.isEmpty()) {
                    statusText.text = "Tidak ada restoran ditemukan untuk \"$keyword\""
                    statusText.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    statusText.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.submitList(response.restaurants)
                }
            } catch (e: Exception) {
                progressBar.visibility = View.GONE
                statusText.text = "Gagal mencari: ${e.message}"
                statusText.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            }
        }
    }

    companion object {
        const val TAG = "ShopeeFoodBottomSheet"
    }
}
