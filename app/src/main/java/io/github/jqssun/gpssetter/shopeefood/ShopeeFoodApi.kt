package io.github.jqssun.gpssetter.shopeefood

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ShopeeFood Indonesia API client.
 *
 * ShopeeFood Indonesia menggunakan backend yang sama dengan Foody/NowFood.
 * Endpoint: https://gappapi.deliverynow.vn
 * Country: Indonesia (ID / country_id=3)
 *
 * Endpoint search:
 * GET /api/delivery/search_deliveries?keyword=...&lat=...&lng=...
 *
 * Jika API utama gagal, fallback ke endpoint alternatif.
 */
object ShopeeFoodApi {

    private const val TAG = "ShopeeFoodApi"

    // Base URL untuk ShopeeFood Indonesia (via Foody/DeliveryNow backend)
    private const val BASE_URL = "https://gappapi.deliverynow.vn"

    // Headers yang dibutuhkan
    private const val HEADER_CLIENT_TYPE = "x-foody-client-type"
    private const val HEADER_CLIENT_ID = "x-foody-client-id"
    private const val HEADER_API_VERSION = "x-foody-api-version"
    private const val HEADER_APP_TYPE = "x-foody-app-type"
    private const val HEADER_CLIENT_LANGUAGE = "x-foody-client-language"
    private const val HEADER_CLIENT_VERSION = "x-foody-client-version"

    // Values
    private const val CLIENT_TYPE = "1"          // 1 = Android
    private const val CLIENT_ID = ""             // Kosong untuk guest
    private const val API_VERSION = "1"
    private const val APP_TYPE = "1004"          // ShopeeFood Indonesia
    private const val CLIENT_LANGUAGE = "id"     // Bahasa Indonesia
    private const val CLIENT_VERSION = "3.30.0"
    private const val COUNTRY_ID = "3"           // Indonesia = 3

    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    /**
     * Search restoran di ShopeeFood Indonesia berdasarkan keyword.
     *
     * @param keyword kata kunci pencarian (misal: "KFC", "McDonald", "Geprek")
     * @param lat latitude lokasi user (untuk mengurutkan berdasarkan jarak)
     * @param lng longitude lokasi user
     * @return SearchResponse berisi list restoran
     */
    suspend fun searchRestaurants(
        keyword: String,
        lat: Double,
        lng: Double
    ): SearchResponse {
        return withContext(Dispatchers.IO) {
            try {
                val result = trySearch(keyword, lat, lng)
                result
            } catch (e: Exception) {
                Log.e(TAG, "Search failed: ${e.message}", e)
                SearchResponse(emptyList(), 0, false)
            }
        }
    }

    private fun trySearch(keyword: String, lat: Double, lng: Double): SearchResponse {
        val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")

        // Endpoint search delivery
        val urlStr = "$BASE_URL/api/delivery/search_deliveries" +
                "?keyword=$encodedKeyword" +
                "&lat=$lat" +
                "&lng=$lng" +
                "&country_id=$COUNTRY_ID" +
                "&fopiRequest=true"

        Log.d(TAG, "Searching: $urlStr")

        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT
        connection.readTimeout = READ_TIMEOUT
        connection.requestMethod = "GET"

        // Set required headers
        connection.setRequestProperty(HEADER_CLIENT_TYPE, CLIENT_TYPE)
        connection.setRequestProperty(HEADER_CLIENT_ID, CLIENT_ID)
        connection.setRequestProperty(HEADER_API_VERSION, API_VERSION)
        connection.setRequestProperty(HEADER_APP_TYPE, APP_TYPE)
        connection.setRequestProperty(HEADER_CLIENT_LANGUAGE, CLIENT_LANGUAGE)
        connection.setRequestProperty(HEADER_CLIENT_VERSION, CLIENT_VERSION)
        connection.setRequestProperty("User-Agent", "ShopeeFood/${CLIENT_VERSION} Android")
        connection.setRequestProperty("Accept", "application/json")

        try {
            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode != 200) {
                val errorBody = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                } catch (_: Exception) { "Cannot read error" }
                Log.w(TAG, "HTTP $responseCode: $errorBody")
                return SearchResponse(emptyList(), 0, false)
            }

            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            return parseSearchResponse(responseBody, lat, lng)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSearchResponse(body: String, userLat: Double, userLng: Double): SearchResponse {
        return try {
            val json = JSONObject(body)
            val reply = json.optJSONObject("reply") ?: json

            // Coba parse dari format "search_result"
            val searchResult = reply.optJSONArray("search_result")
                ?: reply.optJSONArray("delivery_infos")
                ?: reply.optJSONArray("restaurants")

            if (searchResult == null || searchResult.length() == 0) {
                Log.d(TAG, "No results found in response")
                return SearchResponse(emptyList(), 0, false)
            }

            val restaurants = mutableListOf<RestaurantResult>()

            for (i in 0 until searchResult.length()) {
                val item = searchResult.getJSONObject(i)

                // Parse nested restaurant info (beberapa format punya nested "delivery_detail" / "restaurant_info")
                val deliveryDetail = item.optJSONObject("delivery_detail")
                    ?: item.optJSONObject("restaurant_info")
                    ?: item

                val restaurant = parseRestaurant(deliveryDetail, userLat, userLng)
                if (restaurant != null) {
                    restaurants.add(restaurant)
                }
            }

            Log.d(TAG, "Parsed ${restaurants.size} restaurants")
            SearchResponse(
                restaurants = restaurants,
                totalCount = restaurants.size,
                hasMore = restaurants.size >= 20
            )
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            SearchResponse(emptyList(), 0, false)
        }
    }

    private fun parseRestaurant(json: JSONObject, userLat: Double, userLng: Double): RestaurantResult? {
        return try {
            val id = json.optLong("id", json.optLong("restaurant_id", 0))
            val name = json.optString("name", json.optString("restaurant_name", ""))

            if (name.isBlank()) return null

            val address = json.optString("address", json.optString("full_address", ""))

            // Koordinat bisa di level atas atau nested di "position"
            val position = json.optJSONObject("position")
            val lat = position?.optDouble("latitude", 0.0)
                ?: json.optDouble("latitude", json.optDouble("lat", 0.0))
            val lng = position?.optDouble("longitude", 0.0)
                ?: json.optDouble("longitude", json.optDouble("lng", 0.0))

            if (lat == 0.0 && lng == 0.0) return null

            // Photo
            val photos = json.optJSONArray("photos")
            val photoObj = json.optJSONObject("photo")
            val photoUrl = when {
                photos != null && photos.length() > 0 -> {
                    val firstPhoto = photos.getJSONObject(0)
                    firstPhoto.optString("value", firstPhoto.optString("url", ""))
                }
                photoObj != null -> photoObj.optString("value", photoObj.optString("url", ""))
                else -> json.optString("photo_url", json.optString("logo_url", ""))
            }

            // Rating
            val rating = json.optJSONObject("rating")?.optDouble("avg", 0.0)?.toFloat()
                ?: json.optDouble("avg_rating", json.optDouble("rating", 0.0)).toFloat()

            // Distance
            val distanceMeters = json.optDouble("distance", 0.0)
            val distanceKm = if (distanceMeters > 0) {
                (distanceMeters / 1000.0).toFloat()
            } else {
                // Hitung manual dari koordinat
                calculateDistanceKm(userLat, userLng, lat, lng)
            }

            // Price range
            val priceRange = json.optString("price_range", null)
                ?: json.optJSONObject("price_range")?.optString("text", null)

            // Status buka/tutup
            val isOpen = json.optInt("is_open", 1) == 1
                || json.optBoolean("is_available", true)

            RestaurantResult(
                id = id,
                name = name,
                address = address,
                latitude = lat,
                longitude = lng,
                photoUrl = photoUrl.takeIf { it.isNotBlank() },
                rating = rating,
                distanceKm = distanceKm,
                priceRange = priceRange,
                isOpen = isOpen
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse restaurant: ${e.message}")
            null
        }
    }

    private fun calculateDistanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val R = 6371.0 // km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }
}
