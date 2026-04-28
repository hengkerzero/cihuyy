package io.github.jqssun.gpscihuyy.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StopGpsReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_GPS = "io.github.jqssun.io.github.hengkerzero.gpscihuyy.ACTION_STOP_GPS"
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