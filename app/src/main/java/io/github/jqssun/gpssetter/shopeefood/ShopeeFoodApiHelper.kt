package io.github.jqssun.gpssetter.shopeefood

import android.util.Log
import io.github.jqssun.gpssetter.shopeefood.model.Restaurant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Helper untuk melakukan pencarian restoran via ShopeeFood Web API.
 *
 * PENTING: API ini menggunakan endpoint web Shopee dan memerlukan
 * header browser yang tepat agar tidak diblokir anti-bot.
 *
 * Endpoint: https://shopee.co.id/api/v4/food/search_merchants
 */
object ShopeeFoodApiHelper {

    private const val TAG = "ShopeeFoodApi"
    private const val BASE_URL = "https://shopee.co.id/api/v4/food/search_merchants"
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    // Headers yang diperlukan untuk bypass anti-bot Shopee
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val REFERER = "https://shopee.co.id/now-food"
    private const val X_REQUESTED_WITH = "XMLHttpRequest"
    private const val ACCEPT = "application/json"

    /**
     * Cari restoran berdasarkan keyword dan lokasi anchor (lat/lng).
     *
     * @param keyword Kata kunci pencarian (nama resto, jenis makanan, dll)
     * @param latitude Latitude titik pencarian (pusat peta / lokasi user)
     * @param longitude Longitude titik pencarian
     * @param offset Offset untuk pagination (default 0)
     * @param limit Jumlah hasil per halaman (default 20)
     * @return Result berisi list Restaurant atau error message
     */
    suspend fun searchRestaurants(
        keyword: String,
        latitude: Double,
        longitude: Double,
        offset: Int = 0,
        limit: Int = 20
    ): ShopeeFoodResult {
        return withContext(Dispatchers.IO) {
            try {
                val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
                val urlStr = "$BASE_URL?keyword=$encodedKeyword" +
                        "&latitude=$latitude" +
                        "&longitude=$longitude" +
                        "&offset=$offset" +
                        "&limit=$limit"

                Log.d(TAG, "Searching: $urlStr")

                val url = URL(urlStr)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"

                // Set headers penting (anti-bot bypass)
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Referer", REFERER)
                connection.setRequestProperty("X-Requested-With", X_REQUESTED_WITH)
                connection.setRequestProperty("Accept", ACCEPT)
                connection.setRequestProperty("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7")

                try {
                    val responseCode = connection.responseCode

                    if (responseCode == 403) {
                        return@withContext ShopeeFoodResult.Error(
                            "Diblokir oleh Shopee (403 Forbidden). " +
                                    "Coba lagi nanti atau gunakan VPN."
                        )
                    }

                    if (responseCode == 429) {
                        return@withContext ShopeeFoodResult.Error(
                            "Terlalu banyak request (429). Coba lagi beberapa saat."
                        )
                    }

                    if (responseCode != 200) {
                        return@withContext ShopeeFoodResult.Error(
                            "HTTP Error: $responseCode"
                        )
                    }

                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    parseResponse(responseBody)
                } finally {
                    connection.disconnect()
                }
            } catch (e: java.net.SocketTimeoutException) {
                ShopeeFoodResult.Error("Timeout koneksi ke Shopee: ${e.message}")
            } catch (e: java.net.UnknownHostException) {
                ShopeeFoodResult.Error("Tidak ada koneksi internet")
            } catch (e: java.io.IOException) {
                ShopeeFoodResult.Error("Koneksi gagal: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error", e)
                ShopeeFoodResult.Error("Error: ${e.message}")
            }
        }
    }

    /**
     * Parse JSON response dari ShopeeFood API.
     *
     * Struktur response (simplified):
     * {
     *   "reply": {
     *     "search_result": {
     *       "merchants": [
     *         {
     *           "merchant_id": "...",
     *           "name": "...",
     *           "address": "...",
     *           "location": { "latitude": ..., "longitude": ... },
     *           "distance_text": "...",
     *           "photo_url": "..."
     *         }
     *       ]
     *     }
     *   }
     * }
     */
    private fun parseResponse(responseBody: String): ShopeeFoodResult {
        return try {
            val json = JSONObject(responseBody)

            // Cek error dari API
            val error = json.optInt("error", 0)
            if (error != 0) {
                val errorMsg = json.optString("error_msg", "Unknown API error")
                return ShopeeFoodResult.Error("Shopee API error: $errorMsg")
            }

            // Navigate ke merchants array
            val reply = json.optJSONObject("reply")
                ?: return ShopeeFoodResult.Error("Response tidak memiliki 'reply'")

            val searchResult = reply.optJSONObject("search_result")
                ?: return ShopeeFoodResult.Error("Tidak ada search_result dalam response")

            val merchants = searchResult.optJSONArray("merchants")
            if (merchants == null || merchants.length() == 0) {
                return ShopeeFoodResult.Empty
            }

            val restaurants = mutableListOf<Restaurant>()
            for (i in 0 until merchants.length()) {
                val merchant = merchants.getJSONObject(i)

                val merchantId = merchant.optString("merchant_id", "")
                val name = merchant.optString("name", "Unknown")
                val address = merchant.optString("address", "-")
                val distanceText = merchant.optString("distance_text", "-")
                val photoUrl = merchant.optString("photo_url", null)

                // Parse location object
                val location = merchant.optJSONObject("location")
                val lat = location?.optDouble("latitude", 0.0) ?: 0.0
                val lng = location?.optDouble("longitude", 0.0) ?: 0.0

                // Skip jika koordinat invalid
                if (lat == 0.0 && lng == 0.0) continue

                restaurants.add(
                    Restaurant(
                        merchantId = merchantId,
                        name = name,
                        address = address,
                        latitude = lat,
                        longitude = lng,
                        distanceText = distanceText,
                        photoUrl = photoUrl
                    )
                )
            }

            if (restaurants.isEmpty()) {
                ShopeeFoodResult.Empty
            } else {
                Log.d(TAG, "Found ${restaurants.size} restaurants")
                ShopeeFoodResult.Success(restaurants)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error", e)
            ShopeeFoodResult.Error("Gagal parse response: ${e.message}")
        }
    }
}

/**
 * Sealed class untuk hasil pencarian ShopeeFood.
 */
sealed class ShopeeFoodResult {
    data class Success(val restaurants: List<Restaurant>) : ShopeeFoodResult()
    object Empty : ShopeeFoodResult()
    data class Error(val message: String) : ShopeeFoodResult()
}
