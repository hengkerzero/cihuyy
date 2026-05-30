package io.github.jqssun.gpssetter.utils

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService yang mendeteksi notifikasi orderan dari ShopeeFood Driver.
 *
 * Ketika driver dapat orderan baru, notifikasi dari app ShopeeFood Driver muncul.
 * Service ini akan mendeteksi notifikasi tersebut dan otomatis mematikan fake GPS
 * agar posisi kembali ke real location saat menerima order.
 *
 * Fitur ini bisa di-toggle on/off oleh user via setting di MapActivity.
 */
class OrderNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "OrderNotifListener"

        // Package names ShopeeFood Driver (bisa ada beberapa varian)
        private val SHOPEE_DRIVER_PACKAGES = listOf(
            "com.shopee.foody.driver.id",  // ShopeeFood Driver Indonesia
            "com.shopee.driver",           // Varian lain
            "com.shopee.food.driver",      // Varian lain
            "com.garena.game.shopee.driver" // Varian regional
        )

        // Keywords yang mengindikasikan ada orderan masuk
        private val ORDER_KEYWORDS = listOf(
            "pesanan baru",
            "order baru",
            "new order",
            "pesanan masuk",
            "ada pesanan",
            "anda dapat",
            "anda mendapat",
            "orderan baru",
            "pickup",
            "ambil pesanan"
        )

        // Broadcast action untuk notify MapActivity
        const val ACTION_ORDER_DETECTED = "io.github.jqssun.gpssetter.ACTION_ORDER_DETECTED"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName ?: return

        // Cek apakah fitur auto-off diaktifkan
        if (!PrefManager.isAutoOffOnOrder) return

        // Cek apakah GPS fake sedang aktif
        if (!PrefManager.isStarted) return

        // Cek apakah notifikasi dari ShopeeFood Driver
        if (!isShopeeDriverPackage(packageName)) return

        // Ambil text dari notifikasi
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()?.lowercase() ?: ""
        val text = extras.getCharSequence("android.text")?.toString()?.lowercase() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()?.lowercase() ?: ""

        val allText = "$title $text $bigText"

        Log.d(TAG, "Notif dari $packageName: title='$title', text='$text'")

        // Cek apakah notifikasi ini berisi keyword orderan
        val isOrderNotification = ORDER_KEYWORDS.any { keyword ->
            allText.contains(keyword)
        }

        if (isOrderNotification) {
            Log.i(TAG, "ORDER DETECTED! Auto-off GPS fake...")
            autoOffGps()
        }
    }

    private fun isShopeeDriverPackage(packageName: String): Boolean {
        return SHOPEE_DRIVER_PACKAGES.any { packageName.contains(it, ignoreCase = true) }
    }

    private fun autoOffGps() {
        // 1. Matikan fake GPS
        PrefManager.update(
            start = false,
            la = PrefManager.getLat,
            ln = PrefManager.getLng
        )

        // 2. Cancel notifikasi GPS aktif
        NotificationsChannel().cancelAllNotifications(this)

        // 3. Stop RouteWalkService jika sedang jalan
        try {
            val stopWalkIntent = Intent(this, RouteWalkService::class.java).apply {
                action = RouteWalkService.ACTION_STOP
            }
            startService(stopWalkIntent)
        } catch (e: Exception) {
            Log.w(TAG, "Stop walk service error (mungkin tidak jalan): ${e.message}")
        }

        // 4. Broadcast ke MapActivity agar UI update
        val broadcastIntent = Intent(ACTION_ORDER_DETECTED).apply {
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)

        Log.i(TAG, "GPS fake dimatikan otomatis karena orderan masuk!")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Tidak perlu action khusus saat notifikasi dihapus
    }
}
