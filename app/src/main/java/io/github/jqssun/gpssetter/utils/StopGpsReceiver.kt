package io.github.jqssun.gpssetter.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.NotificationsChannel

class StopGpsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_GPS = "io.github.jqssun.gpssetter.ACTION_STOP_GPS"
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