package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.ImageButton
import io.github.jqssun.gpssetter.R

/**
 * FloatingService (dahulu JoystickService) — menampilkan overlay dua tombol:
 *  • Stop  : kirim broadcast StopGpsReceiver agar GPS berhenti
 *  • Refresh: update lokasi ke koordinat terkini di PrefManager
 *
 * Floating hanya ditampilkan ketika GPS sudah di-start (PrefManager.isStarted == true).
 * Kalau GPS belum start, overlay tidak di-add ke WindowManager sehingga tidak kelihatan.
 */
class JoystickService : Service() {

    private var wm: WindowManager? = null
    private var floatingView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate() {
        super.onCreate()

        // Hanya tampilkan floating kalau GPS sudah aktif
        if (!PrefManager.isStarted) return

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        floatingView = inflater.inflate(R.layout.floating_controls, null as ViewGroup?)

        // --- Tombol Stop ---
        floatingView!!.findViewById<ImageButton>(R.id.btn_floating_stop).setOnClickListener {
            // Kirim broadcast ke StopGpsReceiver (sama seperti tombol Stop di notifikasi)
            sendBroadcast(Intent(this, StopGpsReceiver::class.java).apply {
                action = StopGpsReceiver.ACTION_STOP_GPS
            })
        }

        // --- Tombol Refresh ---
        floatingView!!.findViewById<ImageButton>(R.id.btn_floating_refresh).setOnClickListener {
            // Re-apply koordinat terkini dari PrefManager agar lokasi di-refresh
            PrefManager.update(
                start = PrefManager.isStarted,
                la    = PrefManager.getLat,
                ln    = PrefManager.getLng
            )
        }

        // --- Draggable agar bisa dipindah ---
        var initialX = 0; var initialY = 0
        var initialTouchX = 0f; var initialTouchY = 0f

        floatingView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x; initialY = layoutParams!!.y
                    initialTouchX = event.rawX;  initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    layoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    wm!!.updateViewLayout(floatingView, layoutParams)
                    true
                }
                else -> false
            }
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 200
        }

        wm!!.addView(floatingView, layoutParams)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let {
            wm?.removeView(it)
            floatingView = null
        }
    }
}
