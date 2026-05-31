package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import io.github.jqssun.gpssetter.R
import kotlin.math.abs

/**
 * Floating control overlay yang muncul saat GPS mock aktif (mode Normal).
 *
 * Pill vertikal gelap berisi 3 tombol (atas -> bawah):
 *  1. Logo app  : drag handle. Tekan singkat = collapse/expand (sembunyikan 2 tombol bawah).
 *                 Tahan & geser = pindahkan posisi floating.
 *  2. Refresh   : re-apply lokasi mock terakhir (refresh hook).
 *  3. Stop      : matikan hook, lalu floating ikut hilang.
 *
 * Service ini tidak menahan state spoofing sendiri — sumber kebenaran tetap di
 * PrefManager. Service hanya UI tipis di atas hook yang sudah ada.
 */
class FloatingControlService : Service() {

    companion object {
        const val ACTION_SHOW = "io.github.jqssun.gpssetter.FLOATING_SHOW"
        const val ACTION_HIDE = "io.github.jqssun.gpssetter.FLOATING_HIDE"

        /** Dikirim saat user menekan Stop di floating, agar UI activity ikut sinkron. */
        const val ACTION_FLOATING_STOP = "io.github.jqssun.gpssetter.ACTION_FLOATING_STOP"
    }

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var refreshBtn: ImageButton? = null
    private var stopBtn: ImageButton? = null
    private var logo: ImageView? = null

    private var collapsed = false

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val inflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        rootView = inflater.inflate(R.layout.floating_control, null as ViewGroup?)

        logo = rootView!!.findViewById(R.id.floating_logo)
        refreshBtn = rootView!!.findViewById(R.id.floating_refresh)
        stopBtn = rootView!!.findViewById(R.id.floating_stop)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 320
        }

        logo?.setOnTouchListener(makeDragListener())

        refreshBtn?.setOnClickListener {
            // Re-apply lokasi terakhir agar app target membaca ulang posisi mock.
            PrefManager.update(
                start = true,
                la = PrefManager.getLat,
                ln = PrefManager.getLng
            )
            toast(getString(R.string.floating_refreshed))
        }

        stopBtn?.setOnClickListener {
            // Matikan hook
            PrefManager.update(
                start = false,
                la = PrefManager.getLat,
                ln = PrefManager.getLng
            )
            NotificationsChannel().cancelAllNotifications(this)
            // Beritahu activity supaya UI ikut update (tombol play, marker, dll)
            sendBroadcast(Intent(ACTION_FLOATING_STOP).setPackage(packageName))
            stopSelf()
        }

        wm?.addView(rootView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Toggle collapse: sembunyikan/ tampilkan tombol refresh & stop. */
    private fun toggleCollapsed() {
        collapsed = !collapsed
        val vis = if (collapsed) View.GONE else View.VISIBLE
        refreshBtn?.visibility = vis
        stopBtn?.visibility = vis
    }

    /**
     * Listener gabungan untuk logo: bedakan tap (toggle collapse) vs drag (pindah posisi).
     */
    private fun makeDragListener(): View.OnTouchListener {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val touchSlop = ViewConfigurationSlop()

        return View.OnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) moved = true
                    params?.x = initialX + dx
                    params?.y = initialY + dy
                    try {
                        wm?.updateViewLayout(rootView, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleCollapsed()
                    true
                }
                else -> false
            }
        }
    }

    private fun ViewConfigurationSlop(): Int =
        android.view.ViewConfiguration.get(this).scaledTouchSlop

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        rootView?.let {
            try {
                wm?.removeView(it)
            } catch (_: Exception) {
            }
        }
        rootView = null
    }
}
