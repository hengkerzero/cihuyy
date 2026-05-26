package io.github.jqssun.gpssetter.shopeefood

/**
 * File ini tidak lagi digunakan karena kita beralih ke pendekatan WebView.
 * Data restoran sekarang diambil langsung dari halaman ShopeeFood via JavaScript injection.
 *
 * Lihat ShopeeFoodSearchActivity.kt untuk implementasi WebView-based approach.
 */

// Retained for backward compat - sealed class masih dipakai di beberapa tempat
sealed class ShopeeFoodResult {
    data class Success(val restaurants: List<io.github.jqssun.gpssetter.shopeefood.model.Restaurant>) : ShopeeFoodResult()
    object Empty : ShopeeFoodResult()
    data class Error(val message: String) : ShopeeFoodResult()
}
