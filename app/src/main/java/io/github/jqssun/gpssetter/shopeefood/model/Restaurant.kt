package io.github.jqssun.gpssetter.shopeefood.model

/**
 * Data class untuk merepresentasikan restoran dari ShopeeFood API.
 */
data class Restaurant(
    val merchantId: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distanceText: String,
    val photoUrl: String? = null
)
