package io.github.jqssun.gpssetter.shopeefood

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.jqssun.gpssetter.databinding.ActivityShopeefoodSearchBinding
import io.github.jqssun.gpssetter.utils.PrefManager
import org.json.JSONObject

/**
 * ShopeeFood Search Activity menggunakan WebView approach.
 *
 * Konsep:
 * - Load halaman ShopeeFood Indonesia (shopee.co.id) di WebView
 * - User browse & cari restoran seperti biasa di Shopee
 * - App intercept response API via XHR hooking (JavaScript injection)
 * - Saat data restoran terdeteksi (ada latitude/longitude), tampilkan tombol Teleport
 * - User tap Teleport → GPS palsu pindah ke koordinat restoran
 *
 * Ini mirip cara modder ShopeeFood Driver yang intercept traffic,
 * tapi tanpa perlu root / proxy eksternal. Cukup inject JS di WebView.
 */
class ShopeeFoodSearchActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShopeeFoodWV"
        private const val SHOPEE_FOOD_URL = "https://shopee.co.id/now-food"
        const val EXTRA_TELEPORT_LAT = "extra_teleport_lat"
        const val EXTRA_TELEPORT_LNG = "extra_teleport_lng"
        const val EXTRA_TELEPORT_NAME = "extra_teleport_name"
    }

    private lateinit var binding: ActivityShopeefoodSearchBinding

    // Data restoran yang terdeteksi saat ini
    private var currentRestoName: String? = null
    private var currentRestoAddress: String? = null
    private var currentRestoLat: Double = 0.0
    private var currentRestoLng: Double = 0.0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShopeefoodSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupWebView()
        setupTeleportButton()

        // Load ShopeeFood
        binding.webviewShopee.loadUrl(SHOPEE_FOOD_URL)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarShopee)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbarShopee.setNavigationOnClickListener {
            if (binding.webviewShopee.canGoBack()) {
                binding.webviewShopee.goBack()
            } else {
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webviewShopee

        // Enable cookies (Shopee butuh cookies untuk session)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowContentAccess = true
            allowFileAccess = false
            // Mimic mobile browser
            userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        // JavaScript interface untuk menerima data dari injected JS
        webView.addJavascriptInterface(ShopeeJsBridge(), "CihuyyBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.layoutLoading.visibility = View.VISIBLE
                Log.d(TAG, "Loading: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.layoutLoading.visibility = View.GONE

                // Inject XHR interceptor setiap halaman selesai load
                injectXhrInterceptor(view)

                // Cek URL apakah sudah masuk halaman restoran
                checkIfRestaurantPage(url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Tetap di dalam WebView untuk semua URL Shopee
                if (url.contains("shopee.co.id")) {
                    return false
                }
                // Block external links
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 80) {
                    binding.layoutLoading.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Inject JavaScript yang menghook XMLHttpRequest dan fetch()
     * untuk menangkap response API yang mengandung data restoran.
     *
     * Ketika response berisi latitude/longitude, kirim ke native via CihuyyBridge.
     */
    private fun injectXhrInterceptor(webView: WebView?) {
        val js = """
            (function() {
                if (window.__cihuyyInjected) return;
                window.__cihuyyInjected = true;
                
                // Hook fetch()
                var originalFetch = window.fetch;
                window.fetch = function() {
                    return originalFetch.apply(this, arguments).then(function(response) {
                        var url = response.url || '';
                        // Intercept API responses yang mungkin berisi data merchant/restoran
                        if (url.indexOf('food') !== -1 || url.indexOf('merchant') !== -1 || url.indexOf('store') !== -1 || url.indexOf('shop') !== -1) {
                            response.clone().text().then(function(body) {
                                try {
                                    window.CihuyyBridge.onApiResponse(url, body);
                                } catch(e) {}
                            });
                        }
                        return response;
                    });
                };
                
                // Hook XMLHttpRequest
                var originalOpen = XMLHttpRequest.prototype.open;
                var originalSend = XMLHttpRequest.prototype.send;
                
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__cihuyyUrl = url;
                    return originalOpen.apply(this, arguments);
                };
                
                XMLHttpRequest.prototype.send = function() {
                    var xhr = this;
                    var originalOnReady = xhr.onreadystatechange;
                    xhr.onreadystatechange = function() {
                        if (xhr.readyState === 4 && xhr.status === 200) {
                            var url = xhr.__cihuyyUrl || '';
                            if (url.indexOf('food') !== -1 || url.indexOf('merchant') !== -1 || url.indexOf('store') !== -1 || url.indexOf('shop') !== -1) {
                                try {
                                    window.CihuyyBridge.onApiResponse(url, xhr.responseText);
                                } catch(e) {}
                            }
                        }
                        if (originalOnReady) originalOnReady.apply(this, arguments);
                    };
                    
                    this.addEventListener('load', function() {
                        var url = xhr.__cihuyyUrl || '';
                        if (url.indexOf('food') !== -1 || url.indexOf('merchant') !== -1 || url.indexOf('store') !== -1 || url.indexOf('shop') !== -1) {
                            try {
                                window.CihuyyBridge.onApiResponse(url, xhr.responseText);
                            } catch(e) {}
                        }
                    });
                    
                    return originalSend.apply(this, arguments);
                };
                
                console.log('[Cihuyy] XHR/Fetch interceptor injected');
            })();
        """.trimIndent()

        webView?.evaluateJavascript(js, null)
    }

    /**
     * Cek dari URL apakah user sudah masuk ke halaman detail restoran.
     * URL pattern: shopee.co.id/now-food/shop/{id} atau /universal-link/now-food/shop/{id}
     */
    private fun checkIfRestaurantPage(url: String?) {
        if (url == null) return
        if (url.contains("now-food/shop/") || url.contains("now-food/store/")) {
            // User masuk halaman restoran, coba extract info dari DOM
            extractRestaurantFromDom()
        }
    }

    /**
     * Inject JS untuk mengambil info restoran dari DOM halaman.
     * ShopeeFood web biasanya menampilkan nama & alamat di elemen tertentu.
     * Juga coba ambil dari meta tags / structured data.
     */
    private fun extractRestaurantFromDom() {
        val js = """
            (function() {
                var name = '';
                var address = '';
                var lat = 0;
                var lng = 0;
                
                // Coba ambil dari berbagai sumber di DOM
                
                // 1. Meta tags (og:title, etc)
                var ogTitle = document.querySelector('meta[property="og:title"]');
                if (ogTitle) name = ogTitle.getAttribute('content') || '';
                
                // 2. Title element
                if (!name) {
                    var titleEl = document.querySelector('h1') || document.querySelector('[class*="name"]') || document.querySelector('[class*="title"]');
                    if (titleEl) name = titleEl.innerText || '';
                }
                
                // 3. Address dari DOM
                var addrEl = document.querySelector('[class*="address"]') || document.querySelector('[class*="location"]');
                if (addrEl) address = addrEl.innerText || '';
                
                // 4. Coba ambil latitude/longitude dari page source / script tags
                var scripts = document.querySelectorAll('script');
                for (var i = 0; i < scripts.length; i++) {
                    var text = scripts[i].innerText || scripts[i].textContent || '';
                    
                    // Cari pattern latitude/longitude di JSON data
                    var latMatch = text.match(/"latitude"\s*:\s*([-\d.]+)/);
                    var lngMatch = text.match(/"longitude"\s*:\s*([-\d.]+)/);
                    if (latMatch && lngMatch) {
                        lat = parseFloat(latMatch[1]);
                        lng = parseFloat(lngMatch[1]);
                        break;
                    }
                    
                    // Pattern alternatif
                    var latMatch2 = text.match(/"lat"\s*:\s*([-\d.]+)/);
                    var lngMatch2 = text.match(/"lng"\s*:\s*([-\d.]+)/) || text.match(/"lon"\s*:\s*([-\d.]+)/);
                    if (latMatch2 && lngMatch2) {
                        lat = parseFloat(latMatch2[1]);
                        lng = parseFloat(lngMatch2[1]);
                        break;
                    }
                }
                
                // 5. Cek __NEXT_DATA__ atau __INITIAL_STATE__ (SSR frameworks)
                if (lat === 0 && window.__NEXT_DATA__) {
                    var json = JSON.stringify(window.__NEXT_DATA__);
                    var latM = json.match(/"latitude"\s*:\s*([-\d.]+)/);
                    var lngM = json.match(/"longitude"\s*:\s*([-\d.]+)/);
                    if (latM && lngM) {
                        lat = parseFloat(latM[1]);
                        lng = parseFloat(lngM[1]);
                    }
                }
                
                if (name || lat !== 0) {
                    window.CihuyyBridge.onRestaurantDetected(name, address, lat, lng);
                }
            })();
        """.trimIndent()

        binding.webviewShopee.evaluateJavascript(js, null)
    }

    private fun setupTeleportButton() {
        binding.btnTeleport.setOnClickListener {
            if (currentRestoLat != 0.0 && currentRestoLng != 0.0) {
                confirmTeleport()
            } else {
                Toast.makeText(this, "Koordinat belum terdeteksi", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmTeleport() {
        val name = currentRestoName ?: "Restoran"
        MaterialAlertDialogBuilder(this)
            .setTitle("Teleport ke $name?")
            .setMessage(
                "Nama: $name\n" +
                "Alamat: ${currentRestoAddress ?: "-"}\n" +
                "Koordinat: $currentRestoLat, $currentRestoLng\n\n" +
                "GPS palsu akan dipindahkan ke lokasi restoran ini."
            )
            .setPositiveButton("Teleport") { _, _ ->
                doTeleport()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doTeleport() {
        PrefManager.update(
            start = true,
            la = currentRestoLat,
            ln = currentRestoLng
        )

        Toast.makeText(this, "GPS dipindahkan ke: ${currentRestoName}", Toast.LENGTH_LONG).show()

        val resultIntent = intent.apply {
            putExtra(EXTRA_TELEPORT_LAT, currentRestoLat)
            putExtra(EXTRA_TELEPORT_LNG, currentRestoLng)
            putExtra(EXTRA_TELEPORT_NAME, currentRestoName ?: "ShopeeFood")
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    /**
     * Tampilkan card info restoran di bottom screen.
     */
    private fun showRestaurantCard(name: String, address: String, lat: Double, lng: Double) {
        currentRestoName = name
        currentRestoAddress = address
        currentRestoLat = lat
        currentRestoLng = lng

        runOnUiThread {
            binding.tvRestoName.text = name.ifEmpty { "Restoran Terdeteksi" }
            binding.tvRestoAddress.text = address.ifEmpty { "-" }
            binding.tvRestoCoords.text = "Koordinat: $lat, $lng"
            binding.cardRestaurantInfo.visibility = View.VISIBLE
            binding.cardHint.visibility = View.GONE
        }
    }

    private fun hideRestaurantCard() {
        runOnUiThread {
            binding.cardRestaurantInfo.visibility = View.GONE
            binding.cardHint.visibility = View.VISIBLE
            currentRestoLat = 0.0
            currentRestoLng = 0.0
        }
    }

    /**
     * JavaScript Interface — dipanggil dari JS yang di-inject ke WebView.
     * Ini bridge antara JavaScript di halaman Shopee dengan kode native Android.
     */
    inner class ShopeeJsBridge {

        /**
         * Dipanggil saat XHR/fetch interceptor menangkap response API.
         * Kita parse JSON-nya untuk mencari data restoran (lat/lng).
         */
        @JavascriptInterface
        fun onApiResponse(url: String, body: String) {
            Log.d(TAG, "API intercepted: $url (${body.length} chars)")
            try {
                parseApiForRestaurant(body)
            } catch (e: Exception) {
                Log.w(TAG, "Parse error for $url: ${e.message}")
            }
        }

        /**
         * Dipanggil saat DOM scraping berhasil menemukan data restoran.
         */
        @JavascriptInterface
        fun onRestaurantDetected(name: String, address: String, lat: Double, lng: Double) {
            Log.d(TAG, "Restaurant detected from DOM: $name @ $lat,$lng")
            if (lat != 0.0 && lng != 0.0 && lat in -90.0..90.0 && lng in -180.0..180.0) {
                showRestaurantCard(name, address, lat, lng)
            }
        }
    }

    /**
     * Parse JSON response dari API untuk mencari data merchant/restoran.
     * Cari key: latitude, longitude, name, address di berbagai level nesting.
     */
    private fun parseApiForRestaurant(body: String) {
        if (body.length < 10) return

        try {
            // Cari pattern koordinat di response body
            val latRegex = Regex(""""latitude"\s*:\s*([-\d.]+)""")
            val lngRegex = Regex(""""longitude"\s*:\s*([-\d.]+)""")
            val nameRegex = Regex(""""name"\s*:\s*"([^"]+)"""")
            val addressRegex = Regex(""""address"\s*:\s*"([^"]+)"""")

            val latMatch = latRegex.find(body)
            val lngMatch = lngRegex.find(body)

            if (latMatch != null && lngMatch != null) {
                val lat = latMatch.groupValues[1].toDoubleOrNull() ?: return
                val lng = lngMatch.groupValues[1].toDoubleOrNull() ?: return

                // Validasi koordinat masuk akal (Indonesia region: -11 to 6 lat, 95 to 141 lng)
                if (lat < -11 || lat > 6 || lng < 95 || lng > 141) return

                val name = nameRegex.find(body)?.groupValues?.get(1) ?: ""
                val address = addressRegex.find(body)?.groupValues?.get(1) ?: ""

                Log.d(TAG, "Restaurant found in API: $name @ $lat, $lng")
                showRestaurantCard(name, address, lat, lng)
            }
        } catch (e: Exception) {
            Log.w(TAG, "parseApiForRestaurant error: ${e.message}")
        }
    }

    @Deprecated("Use onBackPressedDispatcher")
    override fun onBackPressed() {
        if (binding.webviewShopee.canGoBack()) {
            binding.webviewShopee.goBack()
            // Reset card saat navigasi mundur
            hideRestaurantCard()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        binding.webviewShopee.destroy()
        super.onDestroy()
    }
}
