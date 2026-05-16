package io.github.jqssun.gpssetter.utils

import android.net.Uri

/**
 * Utility untuk parsing berbagai format URL Google Maps.
 * Format yang didukung:
 *   - geo:-6.96,110.41
 *   - geo:-6.96,110.41?q=Label
 *   - https://maps.google.com/?q=-6.96,110.41
 *   - https://maps.google.com/maps/@-6.96,110.41,17z
 *   - https://www.google.com/maps/place/NamaLokasi/@-6.96,110.41,17z
 *   - https://goo.gl/maps/xxx  (short link — koordinat diambil dari @)
 *   - https://maps.app.goo.gl/xxx
 */
object MapsDeepLinkHandler {

    data class ParsedLocation(
        val lat: Double,
        val lng: Double,
        val label: String? = null
    )

    /**
     * Parse Uri dari intent GMaps.
     * Kembalikan ParsedLocation jika berhasil, null jika format tidak dikenali.
     */
    fun parse(uri: Uri): ParsedLocation? {
        val uriStr = uri.toString()

        // === Format 1: geo:-6.96,110.41 atau geo:-6.96,110.41?q=Label ===
        if (uri.scheme == "geo") {
            return parseGeoScheme(uri)
        }

        // === Format 2: ?q=-6.96,110.41 ===
        val q = uri.getQueryParameter("q")
        if (!q.isNullOrBlank()) {
            val coordsFromQ = parseCoordString(q)
            if (coordsFromQ != null) return coordsFromQ
            // q berisi teks (nama tempat), bukan koordinat — tidak bisa di-parse
        }

        // === Format 3: URL dengan @lat,lng,zoom ===
        // Contoh: https://maps.google.com/maps/@-6.96,110.41,17z
        val atIdx = uriStr.indexOf('@')
        if (atIdx != -1) {
            val afterAt = uriStr.substring(atIdx + 1)
            val parts = afterAt.split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull()
            val lng = parts.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lng != null) {
                // Coba ambil label dari path /place/NamaLokasi/
                val label = extractPlaceLabel(uri)
                return ParsedLocation(lat, lng, label)
            }
        }

        return null
    }

    // --- private helpers ---

    private fun parseGeoScheme(uri: Uri): ParsedLocation? {
        // schemeSpecificPart = "-6.9667,110.4167" atau "-6.9667,110.4167?q=Label"
        val specific = uri.schemeSpecificPart ?: return null
        val coordPart = specific.split("?").first()
        val parts = coordPart.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        val label = uri.getQueryParameter("q")
        return ParsedLocation(lat, lng, label)
    }

    private fun parseCoordString(s: String): ParsedLocation? {
        // "-6.9667,110.4167" atau "-6.9667, 110.4167"
        val parts = s.trim().split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
        return ParsedLocation(lat, lng)
    }

    private fun extractPlaceLabel(uri: Uri): String? {
        // Path: /maps/place/Nama+Lokasi/@...
        val path = uri.path ?: return null
        val placeIdx = path.indexOf("/place/")
        if (placeIdx == -1) return null
        return path.substring(placeIdx + 7)
            .split("/").firstOrNull()
            ?.replace("+", " ")
            ?.replace("%20", " ")
            ?.takeIf { it.isNotBlank() }
    }
}
