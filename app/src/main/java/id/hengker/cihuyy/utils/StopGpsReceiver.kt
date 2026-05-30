package id.hengker.cihuyy.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.hengker.cihuyy.utils.PrefManager
import id.hengker.cihuyy.utils.NotificationsChannel

class StopGpsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_GPS = "id.hengker.cihuyy.ACTION_STOP_GPS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_GPS) {
            // Sama persis dengan yang dilakukan stopButton.setOnClickListener
            PrefManager.update(
                start = false,
                la = PrefManager.getLat,
                ln = PrefManager.getLng
            )
            NotificationsChannel().cancelAllNotifications(context)
        }
    }
}