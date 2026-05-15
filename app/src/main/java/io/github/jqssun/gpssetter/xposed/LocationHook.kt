package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.jqssun.gpssetter.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0
    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat =
                if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng =
                if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng
            accuracy = settings.accuracy!!.toFloat()

        } catch (e: Exception) {
            Timber.tag("GPS Setter")
                .e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    /**
     * Membuat Location object palsu dengan koordinat dari settings.
     * setIsFromMockProvider(false) dipanggil agar app target tidak tahu ini mock.
     */
    private fun buildFakeLocation(origin: Location? = null): Location {
        val location = if (origin != null) {
            Location(origin.provider).also {
                it.time = origin.time
                it.bearing = origin.bearing
                it.bearingAccuracyDegrees = origin.bearingAccuracyDegrees
                it.elapsedRealtimeNanos = origin.elapsedRealtimeNanos
                it.verticalAccuracyMeters = origin.verticalAccuracyMeters
            }
        } else {
            Location(LocationManager.GPS_PROVIDER).also {
                it.time = System.currentTimeMillis() - 300
            }
        }
        location.latitude = newlat
        location.longitude = newlng
        location.altitude = 0.0
        location.speed = 0F
        location.accuracy = accuracy
        location.speedAccuracyMetersPerSecond = 0F
        try {
            HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false)
        } catch (e: Exception) {
            XposedBridge.log("LocationHook: unable to set mock $e")
        }
        return location
    }

    /**
     * Hook callLocationChangedLocked — dipakai di Android < 14 dan Android 14 sebagai fallback.
     * Method ini dipanggil setiap kali ada update lokasi dari provider ke listener app.
     */
    private fun hookCallLocationChangedLocked(classLoader: ClassLoader, serviceClassName: String) {
        try {
            XposedHelpers.findAndHookMethod(
                "$serviceClassName\$Receiver",
                classLoader,
                "callLocationChangedLocked",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                        val origin = param.args[0] as? Location
                        val fake = buildFakeLocation(origin)
                        XposedBridge.log("GS [callLocationChanged]: lat=${fake.latitude}, lon=${fake.longitude}")
                        param.args[0] = fake
                    }
                }
            )
            XposedBridge.log("LocationHook: hooked callLocationChangedLocked on $serviceClassName")
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: callLocationChangedLocked not found ($serviceClassName): $e")
        }
    }

    /**
     * Hook injectLocation — dipakai di Android 14+ (SDK >= 34).
     * Method ini menjadi entry point lokasi di LocationManagerService baru.
     */
    private fun hookInjectLocation(serviceClass: Class<*>) {
        try {
            XposedHelpers.findAndHookMethod(
                serviceClass,
                "injectLocation",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                        val origin = param.args[0] as? Location
                        val fake = buildFakeLocation(origin)
                        XposedBridge.log("GS [injectLocation]: lat=${fake.latitude}, lon=${fake.longitude}")
                        param.args[0] = fake
                    }
                }
            )
            XposedBridge.log("LocationHook: hooked injectLocation")
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: injectLocation not found: $e")
        }
    }

    /**
     * Hook getLastLocation — untuk semua versi, agar tombol "my location" juga mengembalikan posisi palsu.
     */
    private fun hookGetLastLocation(serviceClass: Class<*>) {
        try {
            for (method in serviceClass.declaredMethods) {
                if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                val fake = buildFakeLocation()
                                XposedBridge.log("GS [getLastLocation]: lat=${fake.latitude}, lon=${fake.longitude}")
                                param.result = fake
                            }
                        }
                    )
                }
            }
            XposedBridge.log("LocationHook: hooked getLastLocation")
        } catch (e: Throwable) {
            XposedBridge.log("LocationHook: getLastLocation not found: $e")
        }
    }

    /**
     * Suppress GNSS callbacks agar tidak ada bocoran dari sensor hardware.
     */
    private fun suppressGnssCallbacks(serviceClass: Class<*>) {
        val gnssMethodNames = setOf(
            "addGnssBatchingCallback",
            "addGnssMeasurementsListener",
            "addGnssNavigationMessageListener",
            "addGnssAntennaInfoListener",
            "startGnssBatch"
        )
        for (method in serviceClass.declaredMethods) {
            if (method.name in gnssMethodNames) {
                try {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = if (method.returnType == Boolean::class.java) false else null
                        }
                    })
                } catch (e: Throwable) {
                    XposedBridge.log("LocationHook: cannot suppress ${method.name}: $e")
                }
            }
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName == "android") {
            XposedBridge.log("Hooking system server (SDK=${Build.VERSION.SDK_INT})")

            if (!settings.isStarted || !settings.isHookedSystem) return

            if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()

            when {
                // ── Android 13 ke bawah (SDK < 33 sebenarnya masuk sini, tapi kita cover < 34) ──
                Build.VERSION.SDK_INT < 34 -> {
                    val serviceClassName = "com.android.server.LocationManagerService"
                    try {
                        val serviceClass = XposedHelpers.findClass(serviceClassName, lpparam.classLoader)

                        // Hook getLastLocation (SDK < 34 pakai signature lama dengan LocationRequest)
                        try {
                            XposedHelpers.findAndHookMethod(
                                serviceClass,
                                "getLastLocation",
                                LocationRequest::class.java,
                                String::class.java,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                        param.result = buildFakeLocation()
                                    }
                                }
                            )
                        } catch (e: Throwable) {
                            // Fallback: cari getLastLocation tanpa signature spesifik
                            hookGetLastLocation(serviceClass)
                        }

                        suppressGnssCallbacks(serviceClass)
                        hookCallLocationChangedLocked(lpparam.classLoader, serviceClassName)

                    } catch (e: Throwable) {
                        XposedBridge.log("LocationHook: failed to hook $serviceClassName: $e")
                    }
                }

                // ── Android 14 (SDK == 34) — class baru, tapi injectLocation kadang tidak konsisten ──
                Build.VERSION.SDK_INT == 34 -> {
                    val serviceClassName = "com.android.server.location.LocationManagerService"
                    try {
                        val serviceClass = XposedHelpers.findClass(serviceClassName, lpparam.classLoader)

                        hookGetLastLocation(serviceClass)
                        suppressGnssCallbacks(serviceClass)

                        // Dual hook: injectLocation sebagai primary, callLocationChangedLocked sebagai fallback
                        hookInjectLocation(serviceClass)
                        hookCallLocationChangedLocked(lpparam.classLoader, serviceClassName)

                    } catch (e: Throwable) {
                        XposedBridge.log("LocationHook: failed to hook $serviceClassName (API34): $e")
                    }
                }

                // ── Android 15+ (SDK >= 35) ──
                else -> {
                    val serviceClassName = "com.android.server.location.LocationManagerService"
                    try {
                        val serviceClass = XposedHelpers.findClass(serviceClassName, lpparam.classLoader)

                        hookGetLastLocation(serviceClass)
                        suppressGnssCallbacks(serviceClass)
                        hookInjectLocation(serviceClass)

                    } catch (e: Throwable) {
                        XposedBridge.log("LocationHook: failed to hook $serviceClassName (API35+): $e")
                    }
                }
            }

        } else {
            // ── Application hook: hook langsung di class Location untuk semua app ──
            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )
            val interval = 80

            for (method in LocationClass.declaredMethods) {
                when (method.name) {
                    "getLatitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                param.result = newlat
                            }
                        }
                    })
                    "getLongitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                param.result = newlng
                            }
                        }
                    })
                    "getAccuracy" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                param.result = accuracy
                            }
                        }
                    })
                }
            }

            XposedHelpers.findAndHookMethod(
                LocationClass,
                "set",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                            val origin = param.args[0] as? Location
                            val fake = buildFakeLocation(origin)
                            XposedBridge.log("GS: lat: ${fake.latitude}, lon: ${fake.longitude}")
                            param.args[0] = fake
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.location.LocationManager",
                lpparam.classLoader,
                "getLastKnownLocation",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                            val provider = param.args[0] as String
                            val fake = buildFakeLocation(Location(provider))
                            XposedBridge.log("GS: lat: ${fake.latitude}, lon: ${fake.longitude}")
                            param.result = fake
                        }
                    }
                }
            )
        }
    }
}
