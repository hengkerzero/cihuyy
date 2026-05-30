package io.github.jqssun.gpssetter.utils

import android.util.Log
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Helper untuk mengambil route dari OSRM public API.
 * Mengembalikan list of LatLng yang mengikuti jalan asli.
 *
 * Worst-case handling:
 * - Timeout 30 detik (configurable)
 * - Retry 2x jika gagal
 * - Fallback ke garis lurus jika semua retry gagal
 * - Validasi response sebelum parsing
 * - Handle empty/malformed geometry
 */
object OsrmRouteHelper {

    private const val TAG = "OsrmRouteHelper"
    private const val BASE_URL = "https://router.project-osrm.org/route/v1"
    private const val TIMEOUT_MS = 30_000L
    private const val MAX_RETRIES = 2
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 20_000

    /**
     * Pilih OSRM routing profile berdasarkan kecepatan yang dipilih user.
     * - <= 12 km/h  -> foot   (jalan / lari)
     * - <= 25 km/h  -> bike   (sepeda)
     * - > 25 km/h   -> driving (motor / mobil)
     */
    fun profileForSpeed(speedKmh: Float): String = when {
        speedKmh <= 12f -> "foot"
        speedKmh <= 25f -> "bike"
        else -> "driving"
    }

    /**
     * Fetch route dari OSRM API.
     * @param start titik awal
     * @param finish titik akhir
     * @param profile OSRM profile (foot/bike/driving)
     * @return Result berisi list LatLng route, atau error message
     */
    suspend fun fetchRoute(start: LatLng, finish: LatLng, profile: String = "foot"): RouteResult {
        return withContext(Dispatchers.IO) {
            var lastError: String? = null

            for (attempt in 0..MAX_RETRIES) {
                if (attempt > 0) {
                    Log.d(TAG, "Retry attempt $attempt")
                    kotlinx.coroutines.delay(1000L * attempt) // backoff
                }

                val result = withTimeoutOrNull(TIMEOUT_MS) {
                    tryFetchRoute(start, finish, profile)
                }

                when {
                    result == null -> {
                        lastError = "Timeout mengambil rute (attempt ${attempt + 1})"
                        Log.w(TAG, lastError)
                    }
                    result is RouteResult.Success -> return@withContext result
                    result is RouteResult.Error -> {
                        lastError = result.message
                        Log.w(TAG, "Fetch error: $lastError")
                    }
                }
            }

            // Profile selain "foot" mungkin tidak tersedia di server publik OSRM.
            // Coba sekali lagi pakai "foot" sebelum menyerah ke garis lurus.
            if (profile != "foot") {
                Log.w(TAG, "Profile '$profile' gagal, fallback coba 'foot'")
                val footResult = withTimeoutOrNull(TIMEOUT_MS) {
                    tryFetchRoute(start, finish, "foot")
                }
                if (footResult is RouteResult.Success) return@withContext footResult
            }

            // Semua percobaan gagal -> fallback garis lurus
            Log.w(TAG, "All retries failed, falling back to straight line")
            val fallbackPoints = generateStraightLineFallback(start, finish)
            RouteResult.Fallback(fallbackPoints, lastError ?: "Unknown error")
        }
    }

    private suspend fun tryFetchRoute(start: LatLng, finish: LatLng, profile: String): RouteResult {
        return try {
            // OSRM format: /route/v1/{profile}/lng1,lat1;lng2,lat2
            val urlStr = "$BASE_URL/$profile/${start.longitude},${start.latitude};${finish.longitude},${finish.latitude}?overview=full&geometries=geojson&steps=false"
            Log.d(TAG, "Fetching route: $urlStr")

            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "Cihuyy-GPS-App/1.0")

            try {
                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    return RouteResult.Error("HTTP $responseCode dari OSRM server")
                }

                val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                parseOsrmResponse(responseBody)
            } finally {
                connection.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            RouteResult.Error("Connection timeout: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            RouteResult.Error("Tidak ada koneksi internet: ${e.message}")
        } catch (e: java.io.IOException) {
            RouteResult.Error("IO Error: ${e.message}")
        } catch (e: Exception) {
            RouteResult.Error("Unexpected error: ${e.message}")
        }
    }

    private fun parseOsrmResponse(responseBody: String): RouteResult {
        return try {
            val json = JSONObject(responseBody)
            val code = json.optString("code", "")

            if (code != "Ok") {
                return RouteResult.Error("OSRM error: $code - ${json.optString("message", "unknown")}")
            }

            val routes = json.optJSONArray("routes")
            if (routes == null || routes.length() == 0) {
                return RouteResult.Error("Tidak ada route ditemukan")
            }

            val route = routes.getJSONObject(0)
            val geometry = route.optJSONObject("geometry")
                ?: return RouteResult.Error("Geometry kosong")

            val coordinates = geometry.optJSONArray("coordinates")
                ?: return RouteResult.Error("Coordinates kosong")

            if (coordinates.length() < 2) {
                return RouteResult.Error("Route terlalu pendek (< 2 titik)")
            }

            val points = mutableListOf<LatLng>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                val lng = coord.getDouble(0)
                val lat = coord.getDouble(1)

                // Validasi range koordinat
                if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                    points.add(LatLng(lat, lng))
                }
            }

            if (points.size < 2) {
                return RouteResult.Error("Route valid kurang dari 2 titik setelah filter")
            }

            val distance = route.optDouble("distance", 0.0)
            val duration = route.optDouble("duration", 0.0)

            Log.d(TAG, "Route OK: ${points.size} points, ${distance}m, ${duration}s")
            RouteResult.Success(points, distance, duration)
        } catch (e: Exception) {
            RouteResult.Error("Parse error: ${e.message}")
        }
    }

    /**
     * Fallback: buat titik-titik garis lurus antara start dan finish.
     * Interval ±10 meter agar gerakan tetap smooth.
     */
    private fun generateStraightLineFallback(start: LatLng, finish: LatLng): List<LatLng> {
        val points = mutableListOf<LatLng>()
        val totalDistance = distanceBetween(start, finish)
        val stepDistance = 10.0 // meter

        val steps = (totalDistance / stepDistance).toInt().coerceIn(2, 5000)

        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val lat = start.latitude + (finish.latitude - start.latitude) * fraction
            val lng = start.longitude + (finish.longitude - start.longitude) * fraction
            points.add(LatLng(lat, lng))
        }

        return points
    }

    /**
     * Hitung jarak antara 2 titik dalam meter (Haversine formula).
     */
    fun distanceBetween(a: LatLng, b: LatLng): Double {
        val R = 6371000.0 // radius bumi dalam meter
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val sinLat = Math.sin(dLat / 2)
        val sinLng = Math.sin(dLng / 2)
        val aVal = sinLat * sinLat +
                Math.cos(Math.toRadians(a.latitude)) *
                Math.cos(Math.toRadians(b.latitude)) *
                sinLng * sinLng
        val c = 2 * Math.atan2(Math.sqrt(aVal), Math.sqrt(1 - aVal))
        return R * c
    }

    /**
     * Hitung bearing (sudut arah) dari titik A ke titik B.
     */
    fun bearingBetween(a: LatLng, b: LatLng): Double {
        val dLng = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val y = Math.sin(dLng) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLng)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    /**
     * Encode route jadi String ringkas "lat,lng;lat,lng;..." untuk disimpan
     * di SharedPreferences (dipakai persist sesi Auto Walk).
     */
    fun encodeRoute(points: List<LatLng>): String =
        points.joinToString(";") { "${it.latitude},${it.longitude}" }

    /**
     * Decode String hasil [encodeRoute] kembali jadi list LatLng.
     * Segmen yang tidak valid akan diabaikan.
     */
    fun decodeRoute(encoded: String?): List<LatLng> {
        if (encoded.isNullOrBlank()) return emptyList()
        return encoded.split(";").mapNotNull { seg ->
            val parts = seg.split(",")
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lng = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return@mapNotNull null
            LatLng(lat, lng)
        }
    }
}

sealed class RouteResult {
    data class Success(val points: List<LatLng>, val distanceMeters: Double, val durationSeconds: Double) : RouteResult()
    data class Fallback(val points: List<LatLng>, val reason: String) : RouteResult()
    data class Error(val message: String) : RouteResult()
}
