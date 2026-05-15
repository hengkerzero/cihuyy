package io.github.jqssun.gpssetter.xposed

// https://github.com/rovo89/XposedBridge/wiki/Helpers

import android.annotation.SuppressLint
import android.app.AndroidAppHelper
import android.content.Context
import android.location.Location
import android.location.LocationManager
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

    // Candidate LocationManagerService dari berbagai ROM
    private val SERVICE_CLASS_CANDIDATES = listOf(
        "com.android.server.location.LocationManagerService",
        "com.android.server.LocationManagerService",
        "com.miui.server.location.MiuiLocationManagerService",
        "com.android.server.location.LocationManagerServiceImpl"
    )

    private val LOCATION_DELIVERY_METHODS = setOf(
        "callLocationChangedLocked",
        "injectLocation",
        "deliverToListener",
        "dispatchLocationChange",
        "notifyLocationChanged",
        "reportLocation",
        "onLocationChanged"
    )

    private val GNSS_SUPPRESS_METHODS = setOf(
        "addGnssBatchingCallback",
        "addGnssMeasurementsListener",
        "addGnssNavigationMessageListener",
        "addGnssAntennaInfoListener",
        "startGnssBatch"
    )

    // GMS class yang perlu di-hook agar FusedLocationProviderClient juga return lokasi palsu
    // Ini jalan di process com.google.android.gms, bukan system server
    private val GMS_LOCATION_CLASSES = listOf(
        "com.google.android.gms.location.internal.FusedLocationProviderService",
        "com.google.android.gms.location.internal.GoogleLocationManagerService",
        "com.google.android.gms.location.internal.LocationProviderProxyGms",
        "com.google.android.gms.location.FusedLocationProviderApi",
        "com.google.android.location.internal.server.GoogleLocationManagerService"
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
        try { HiddenApiBypass.invoke(location.javaClass, location, "setIsFromMockProvider", false) } catch (_: Throwable) {}
        try { HiddenApiBypass.invoke(location.javaClass, location, "setMock", false) } catch (_: Throwable) {}
        return location
    }

    private fun hookAllLocationMethods(serviceClass: Class<*>, classLoader: ClassLoader, tag: String) {
        var hookedCount = 0

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
                    } catch (_: Throwable) {}
                }
                returnsLocation -> {
                    try {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()
                                param.result = buildFakeLocation()
                                XposedBridge.log("GS [$tag][${method.name}]: lat=$newlat lon=$newlng")
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
                                for (i in param.args.indices) {
                                    if (param.args[i] is Location) {
                                        param.args[i] = buildFakeLocation(param.args[i] as Location)
                                        XposedBridge.log("GS [$tag][${method.name} arg$i]: lat=$newlat lon=$newlng")
                                    }
                                }
                            }
                        })
                        hookedCount++
                    } catch (_: Throwable) {}
                }
            }
        }

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
                        } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {}
        }

        XposedBridge.log("GS [$tag]: total $hookedCount methods hooked on ${serviceClass.simpleName}")
    }

    /**
     * Hook GMS process (com.google.android.gms).
     * FusedLocationProviderClient di app (SFD, dll) minta lokasi ke GMS via binder.
     * GMS punya cache lokasi asli sendiri yang tidak tersentuh oleh system server hook.
     * Solusi: hook di dalam process GMS langsung sehingga semua lokasi yang keluar dari GMS sudah palsu.
     */
    private fun hookGmsProcess(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!settings.isStarted || !settings.isHookedSystem) return
        if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()

        XposedBridge.log("GS: Hooking GMS process")

        // 1. Hook android.location.Location di dalam proses GMS
        //    Ini intercept semua Location object sebelum dikirim ke app via binder
        try {
            val LocationClass = XposedHelpers.findClass("android.location.Location", lpparam.classLoader)
            for (method in LocationClass.declaredMethods) {
                when (method.name) {
                    "getLatitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > 80) updateLocation()
                            if (settings.isStarted) param.result = newlat
                        }
                    })
                    "getLongitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > 80) updateLocation()
                            if (settings.isStarted) param.result = newlng
                        }
                    })
                    "getAccuracy" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (System.currentTimeMillis() - mLastUpdated > 80) updateLocation()
                            if (settings.isStarted) param.result = accuracy
                        }
                    })
                    "isFromMockProvider", "isMock" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (settings.isStarted) param.result = false
                        }
                    })
                }
            }
            XposedBridge.log("GS [gms]: hooked Location class")
        } catch (e: Throwable) {
            XposedBridge.log("GS [gms]: failed to hook Location class: $e")
        }

        // 2. Hook GMS-specific location service classes
        var gmsHookedCount = 0
        for (candidate in GMS_LOCATION_CLASSES) {
            try {
                val cls = XposedHelpers.findClass(candidate, lpparam.classLoader)
                hookAllLocationMethods(cls, lpparam.classLoader, "gms")
                gmsHookedCount++
                XposedBridge.log("GS [gms]: hooked $candidate")
            } catch (_: Throwable) {}
        }
        XposedBridge.log("GS [gms]: hooked $gmsHookedCount GMS classes")
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        when (lpparam.packageName) {

            // ── System server ──
            "android" -> {
                XposedBridge.log("GS: system server (SDK=${Build.VERSION.SDK_INT}, ${Build.MANUFACTURER}/${Build.MODEL})")
                if (!settings.isStarted || !settings.isHookedSystem) return
                if (System.currentTimeMillis() - mLastUpdated > 200) updateLocation()

                var hookedAny = false
                for (candidate in SERVICE_CLASS_CANDIDATES) {
                    try {
                        val serviceClass = XposedHelpers.findClass(candidate, lpparam.classLoader)
                        XposedBridge.log("GS: found $candidate")
                        hookAllLocationMethods(serviceClass, lpparam.classLoader, "system")
                        hookedAny = true
                    } catch (_: Throwable) {}
                }
                if (!hookedAny) XposedBridge.log("GS: WARNING — no service class found!")
            }

            // ── GMS process — ini yang dipakai FusedLocationProviderClient di semua app ──
            "com.google.android.gms" -> {
                hookGmsProcess(lpparam)
            }

            // ── Application hook: hook di dalam process app target (SFD, dll) ──
            else -> {
                val interval = 80

                // Hook android.location.Location
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
                            "isFromMockProvider", "isMock" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (settings.isStarted && !ignorePkg.contains(lpparam.packageName))
                                        param.result = false
                                }
                            })
                        }
                    }

                    XposedHelpers.findAndHookMethod(
                        LocationClass, "set", Location::class.java,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) updateLocation()
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.args[0] = buildFakeLocation(param.args[0] as? Location)
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    XposedBridge.log("GS: failed to hook Location in ${lpparam.packageName}: $e")
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
                                    param.result = buildFakeLocation(provider = param.args[0] as String)
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
}
