package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
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

    // Daftar candidate class LocationManagerService dari berbagai ROM
    // MIUI/HyperOS punya class sendiri, crDroid/LineageOS kadang extends class berbeda
    private val SERVICE_CLASS_CANDIDATES = listOf(
        "com.android.server.location.LocationManagerService",   // AOSP Android 14+, PixelOS, crDroid
        "com.android.server.LocationManagerService",            // AOSP Android 13 ke bawah, beberapa MIUI
        "com.miui.server.location.MiuiLocationManagerService", // MIUI/HyperOS custom class
        "com.android.server.location.LocationManagerServiceImpl" // Beberapa vendor fork
    )

    // Method di service yang perlu di-hook — bervariasi antar ROM
    private val LOCATION_DELIVERY_METHODS = setOf(
        "callLocationChangedLocked",  // AOSP < 14, crDroid
        "injectLocation",             // AOSP 14+, PixelOS
        "deliverToListener",          // Beberapa LineageOS fork
        "dispatchLocationChange",     // Beberapa vendor
        "notifyLocationChanged",      // MIUI variant
        "reportLocation",             // HyperOS variant
        "onLocationChanged"           // Generic fallback
    )

    private val GNSS_SUPPRESS_METHODS = setOf(
        "addGnssBatchingCallback",
        "addGnssMeasurementsListener",
        "addGnssNavigationMessageListener",
        "addGnssAntennaInfoListener",
        "startGnssBatch"
    )

    private fun updateLocation() {
        try {
            mLastUpdated = System.currentTimeMillis()
            val x = (rand.nextInt(50) - 15).toDouble()
            val y = (rand.nextInt(50) - 15).toDouble()
            val dlat = x / earth
            val dlng = y / (earth * cos(pi * settings.getLat / 180.0))
            newlat = if (settings.isRandomPosition) settings.getLat + (dlat * 180.0 / pi) else settings.getLat
            newlng = if (settings.isRandomPosition) settings.getLng + (dlng * 180.0 / pi) else settings.getLng
            accuracy = settings.accuracy!!.toFloat()
        } catch (e: Exception) {
            Timber.tag("GPS Setter").e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    /**
     * Buat Location palsu. Kalau ada origin, salin metadata-nya.
     * Selalu set isFromMockProvider = false.
     */
    private fun buildFakeLocation(origin: Location? = null, provider: String? = null): Location {
        val prov = provider ?: origin?.provider ?: LocationManager.GPS_PROVIDER
        val location = Location(prov)
        if (origin != null) {
            location.time = origin.time
            location.bearing = origin.bearing
            try { location.bearingAccuracyDegrees = origin.bearingAccuracyDegrees } catch (_: Throwable) {}
            location.elapsedRealtimeNanos = origin.elapsedRealtimeNanos
            try { location.verticalAccuracyMeters = origin.verticalAccuracyMeters } catch (_: Throwable) {}
        } else {
            location.time = System.currentTimeMillis() - 300
            location.elapsedRealtimeNanos = System.nanoTime() - 300_000_000L
        }
        location.latitude = newlat
        location.longitude = newlng
        location.altitude = 0.0
        location.speed = 0F
        location.accuracy = accuracy
        try { location.speedAccuracyMetersPerSecond = 0F } catch (_: Throwable) {}
        // Sembunyikan flag mock
        try { HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false) } catch (_: Throwable) {}
        try { HiddenApiBypass.invoke(location.javaClass, location, "setMock", false) } catch (_: Throwable) {}
        return location
    }

    /**
     * Hook semua method di sebuah class yang:
     * 1. Namanya ada di LOCATION_DELIVERY_METHODS, ATAU
     * 2. Return type-nya Location, ATAU
     * 3. Punya parameter Location (untuk intercept sebelum dikirim ke listener)
     */
    private fun hookAllLocationMethods(serviceClass: Class<*>, classLoader: ClassLoader, tag: String) {
        var hookedCount = 0

        // Hook method yang punya parameter Location (delivery methods)
        for (method in serviceClass.declaredMethods) {
            val hasLocationParam = method.parameterTypes.any { it == Location::class.java }
            val returnsLocation = method.returnType == Location::class.java
            val isDeliveryMethod = method.name in LOCATION_DELIVERY_METHODS
            val isGnssMethod = method.name in GNSS_SUPPRESS_METHODS

            when {
                isGnssMethod -> {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                param.result = if (method.returnType == Boolean::class.java) false else null
                            }
                        })
                        XposedBridge.log("GS [$tag]: suppressed ${method.name}")
                    } catch (_: Throwable) {}
                }

                returnsLocation -> {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                val fake = buildFakeLocation()
                                XposedBridge.log("GS [$tag][${method.name}->Location]: lat=$newlat lon=$newlng")
                                param.result = fake
                            }
                        })
                        hookedCount++
                    } catch (_: Throwable) {}
                }

                isDeliveryMethod || hasLocationParam -> {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                // Ganti semua argumen Location
                                for (i in param.args.indices) {
                                    if (param.args[i] is Location) {
                                        val fake = buildFakeLocation(param.args[i] as Location)
                                        XposedBridge.log("GS [$tag][${method.name} arg$i]: lat=$newlat lon=$newlng")
                                        param.args[i] = fake
                                    }
                                }
                            }
                        })
                        hookedCount++
                    } catch (_: Throwable) {}
                }
            }
        }

        // Coba juga hook inner class $Receiver (AOSP < 14) dan $LocationListenerTransport
        val innerClasses = listOf("Receiver", "LocationListenerTransport", "LocationListener", "ListenerTransport")
        for (innerName in innerClasses) {
            try {
                val innerClass = XposedHelpers.findClass("${serviceClass.name}\$$innerName", classLoader)
                for (method in innerClass.declaredMethods) {
                    if (method.parameterTypes.any { it == Location::class.java } ||
                        method.name in LOCATION_DELIVERY_METHODS) {
                        try {
                            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                    for (i in param.args.indices) {
                                        if (param.args[i] is Location) {
                                            param.args[i] = buildFakeLocation(param.args[i] as Location)
                                        }
                                    }
                                }
                            })
                            hookedCount++
                            XposedBridge.log("GS [$tag]: hooked inner ${innerClass.simpleName}.${method.name}")
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        XposedBridge.log("GS [$tag]: total $hookedCount methods hooked on ${serviceClass.simpleName}")
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName == "android") {
            XposedBridge.log("GS: Hooking system server (SDK=${Build.VERSION.SDK_INT}, ROM=${Build.MANUFACTURER}/${Build.MODEL})")

            if (!settings.isStarted || !settings.isHookedSystem) return
            if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()

            var hookedAny = false
            for (candidate in SERVICE_CLASS_CANDIDATES) {
                try {
                    val serviceClass = XposedHelpers.findClass(candidate, lpparam.classLoader)
                    XposedBridge.log("GS: Found service class: $candidate")
                    hookAllLocationMethods(serviceClass, lpparam.classLoader, "system")
                    hookedAny = true
                    // Tidak break — MIUI bisa punya dua class sekaligus
                } catch (_: Throwable) {
                    // Class tidak ada di ROM ini, skip
                }
            }

            if (!hookedAny) {
                XposedBridge.log("GS: WARNING — no LocationManagerService class found on this ROM!")
            }

        } else {
            // ── Application hook: hook langsung di android.location.Location ──
            val interval = 80

            try {
                val LocationClass = XposedHelpers.findClass("android.location.Location", lpparam.classLoader)

                for (method in LocationClass.declaredMethods) {
                    when (method.name) {
                        "getLatitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName))
                                    param.result = newlat
                            }
                        })
                        "getLongitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName))
                                    param.result = newlng
                            }
                        })
                        "getAccuracy" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName))
                                    param.result = accuracy
                            }
                        })
                        // Sembunyikan flag mock langsung di object Location
                        "isFromMockProvider", "isMock" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName))
                                    param.result = false
                            }
                        })
                    }
                }

                // Hook Location.set() — intercept saat lokasi di-copy antar object
                XposedHelpers.findAndHookMethod(
                    LocationClass, "set", Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                val origin = param.args[0] as? Location
                                param.args[0] = buildFakeLocation(origin)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("GS: failed to hook Location class: $e")
            }

            // Hook LocationManager.getLastKnownLocation()
            try {
                XposedHelpers.findAndHookMethod(
                    "android.location.LocationManager", lpparam.classLoader,
                    "getLastKnownLocation", String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                            if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                val provider = param.args[0] as String
                                param.result = buildFakeLocation(provider = provider)
                            }
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("GS: failed to hook getLastKnownLocation: $e")
            }
        }
    }
}
