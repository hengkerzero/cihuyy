package io.github.jqssun.gpssetter.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * Xposed Hook untuk intercept OkHttp response di app Shopee (com.shopee.id).
 *
 * Cara kerja:
 * 1. Hook okhttp3.Response.body() dan okhttp3.internal.http.RealResponseBody
 * 2. Filter response yang URL-nya mengandung keyword food/merchant
 * 3. Cari JSON yang mengandung latitude/longitude (data restoran)
 * 4. Simpan ke /sdcard/Android/data/io.github.jqssun.gpssetter/shopeefood_cache.json
 * 5. App Cihuyy baca file tersebut untuk tampilkan data restoran
 *
 * File output format (JSON array):
 * [
 *   {"name":"Resto A","address":"Jl. X","lat":-6.xxx,"lng":106.xxx},
 *   ...
 * ]
 */
object ShopeeHook {

    private const val TAG = "CihuyyShopeeHook"
    private const val CACHE_DIR = "/sdcard/Android/data/io.github.jqssun.gpssetter/files"
    private const val CACHE_FILE = "shopeefood_cache.json"

    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.shopee.id") return

        XposedBridge.log("[$TAG] Hooking Shopee OkHttp for food data...")

        // Pastikan folder cache ada
        try {
            File(CACHE_DIR).mkdirs()
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Cannot create cache dir: ${e.message}")
        }

        hookOkHttpInterceptor(lpparam)
    }

    /**
     * Hook OkHttp Interceptor Chain.
     * Kita hook method proceed() di RealInterceptorChain untuk tangkap response.
     * Alternatif: hook Response.Builder.build() untuk tangkap semua response.
     */
    private fun hookOkHttpInterceptor(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Strategy 1: Hook okhttp3.internal.connection.RealCall$AsyncCall.run()
        // Strategy 2: Hook okhttp3.Response peekBody / body.string
        // Strategy 3 (paling reliable): Hook Interceptor.intercept()

        // Kita pakai approach: hook okhttp3.Response.Builder.build()
        // Ini dipanggil setiap kali response dibuat, termasuk dari network
        try {
            val responseBuilderClass = XposedHelpers.findClass(
                "okhttp3.Response\$Builder", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                responseBuilderClass,
                "build",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val response = param.result ?: return
                            processResponse(response, lpparam.classLoader)
                        } catch (e: Exception) {
                            // Silent - jangan crash app Shopee
                        }
                    }
                }
            )
            XposedBridge.log("[$TAG] Hooked okhttp3.Response.Builder.build()")
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Failed to hook Response.Builder: ${e.message}")
            // Fallback: coba hook versi obfuscated
            hookObfuscatedOkHttp(lpparam)
        }
    }

    /**
     * Fallback hook untuk Shopee yang mungkin obfuscate OkHttp class names.
     * Cari class yang punya method dengan signature mirip Response.Builder.
     */
    private fun hookObfuscatedOkHttp(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Shopee kadang pakai custom OkHttp atau rename package
            // Coba hook di level lebih rendah: java.net.HttpURLConnection
            val urlConnectionClass = XposedHelpers.findClass(
                "java.net.HttpURLConnection", lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                urlConnectionClass,
                "getInputStream",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val conn = param.thisObject
                            val url = XposedHelpers.callMethod(conn, "getURL")?.toString() ?: return
                            if (isFoodRelatedUrl(url)) {
                                XposedBridge.log("[$TAG] Food URL detected (URLConnection): $url")
                            }
                        } catch (_: Exception) {}
                    }
                }
            )
            XposedBridge.log("[$TAG] Fallback: Hooked HttpURLConnection.getInputStream()")
        } catch (e: Exception) {
            XposedBridge.log("[$TAG] Fallback hook also failed: ${e.message}")
        }
    }

    /**
     * Process OkHttp Response object.
     * Extract URL, cek apakah food-related, lalu parse body untuk data restoran.
     */
    private fun processResponse(response: Any, classLoader: ClassLoader) {
        try {
            // Get request URL
            val request = XposedHelpers.callMethod(response, "request") ?: return
            val url = XposedHelpers.callMethod(request, "url")?.toString() ?: return

            // Filter: hanya proses URL yang terkait food/merchant
            if (!isFoodRelatedUrl(url)) return

            XposedBridge.log("[$TAG] Intercepted food URL: $url")

            // Get response body tanpa consume (pakai peekBody)
            val body: String? = try {
                // peekBody(byteCount) mengembalikan ResponseBody tanpa consume stream asli
                val peekBody = XposedHelpers.callMethod(response, "peekBody", 1024L * 512L) // 512KB max
                XposedHelpers.callMethod(peekBody, "string") as? String
            } catch (e: Exception) {
                XposedBridge.log("[$TAG] peekBody failed: ${e.message}")
                null
            }

            if (body.isNullOrEmpty()) return
            if (body.length < 50) return // terlalu pendek, bukan JSON valid

            // Parse dan simpan jika ada data restoran
            parseAndSaveFoodData(url, body)

        } catch (e: Exception) {
            // Silent - jangan ganggu app Shopee
        }
    }

    /**
     * Cek apakah URL terkait ShopeeFood.
     */
    private fun isFoodRelatedUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return lowerUrl.contains("food") ||
                lowerUrl.contains("merchant") ||
                lowerUrl.contains("now/") ||
                lowerUrl.contains("restaurant") ||
                lowerUrl.contains("store/nearby") ||
                lowerUrl.contains("search_merchant")
    }

    /**
     * Parse response body dan extract data restoran.
     * Simpan ke file cache yang bisa dibaca app Cihuyy.
     */
    private fun parseAndSaveFoodData(url: String, body: String) {
        try {
            // Cari semua restoran dalam response menggunakan regex
            // Pattern: objek yang punya "latitude" dan "longitude" bersamaan
            val merchants = mutableListOf<String>() // JSON objects sebagai string

            // Pattern 1: {"name":"...","address":"...","location":{"latitude":...,"longitude":...}}
            // Pattern 2: {"name":"...","address":"...","latitude":...,"longitude":...}

            val latPattern = Regex(""""latitude"\s*:\s*([-\d.]+)""")
            val lngPattern = Regex(""""longitude"\s*:\s*([-\d.]+)""")
            val namePattern = Regex(""""name"\s*:\s*"([^"]{2,80})"""")
            val addressPattern = Regex(""""address"\s*:\s*"([^"]{2,200})"""")

            val latMatches = latPattern.findAll(body).toList()
            val lngMatches = lngPattern.findAll(body).toList()

            if (latMatches.isEmpty() || lngMatches.isEmpty()) return

            // Ambil semua nama yang ditemukan
            val names = namePattern.findAll(body).map { it.groupValues[1] }.toList()
            val addresses = addressPattern.findAll(body).map { it.groupValues[1] }.toList()

            val count = minOf(latMatches.size, lngMatches.size)
            if (count == 0) return

            val jsonArray = StringBuilder("[")
            var added = 0

            for (i in 0 until count) {
                val lat = latMatches[i].groupValues[1].toDoubleOrNull() ?: continue
                val lng = lngMatches[i].groupValues[1].toDoubleOrNull() ?: continue

                // Validasi koordinat Indonesia: lat -11~6, lng 95~141
                if (lat < -11.0 || lat > 6.0) continue
                if (lng < 95.0 || lng > 141.0) continue

                val name = names.getOrNull(i) ?: "Restoran ${i + 1}"
                val address = addresses.getOrNull(i) ?: ""

                if (added > 0) jsonArray.append(",")
                jsonArray.append("""{"name":"${escapeJson(name)}","address":"${escapeJson(address)}","lat":$lat,"lng":$lng}""")
                added++

                if (added >= 50) break // max 50 results
            }

            jsonArray.append("]")

            if (added == 0) return

            // Tulis ke file
            val cacheFile = File(CACHE_DIR, CACHE_FILE)
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText(jsonArray.toString())

            // Tulis metadata (timestamp + source URL)
            val metaFile = File(CACHE_DIR, "shopeefood_meta.txt")
            metaFile.writeText("${System.currentTimeMillis()}\n$url\n$added items")

            XposedBridge.log("[$TAG] Saved $added restaurants to cache")

        } catch (e: Exception) {
            XposedBridge.log("[$TAG] parseAndSaveFoodData error: ${e.message}")
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
