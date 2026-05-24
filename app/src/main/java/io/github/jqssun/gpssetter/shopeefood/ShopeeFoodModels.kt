package io.github.jqssun.gpssetter.shopeefood

/**
 * Data classes untuk response ShopeeFood Indonesia API.
 * ShopeeFood Indonesia menggunakan backend Foody/DeliveryNow.
 */

/**
 * Hasil pencarian restoran yang ditampilkan di UI.
 */
data class RestaurantResult(
    val id: Long,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val photoUrl: String?,
    val rating: Float,
    val distanceKm: Float,
    val priceRange: String?,
    val isOpen: Boolean
)

/**
 * Response wrapper dari API search.
 */
data class SearchResponse(
    val restaurants: List<RestaurantResult>,
    val totalCount: Int,
    val hasMore: Boolean
)

/**
 * State untuk UI search.
 */
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(val results: List<RestaurantResult>) : SearchState()
    data class Error(val message: String) : SearchState()
    object Empty : SearchState()
}
