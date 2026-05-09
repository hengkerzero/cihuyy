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

    // Hanya skip app kita sendiri — Fused & GMS sekarang di-hook
    private val ignorePkg = arrayListOf(BuildConfig.APPLICATION_ID)

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

    private fun buildMockLocation(provider: String, origin: Location? = null): Location {
        val location = if (origin != null) {
            Location(origin.provider).apply {
                time = origin.time
                bearing = origin.bearing
                bearingAccuracyDegrees = origin.bearingAccuracyDegrees
                elapsedRealtimeNanos = origin.elapsedRealtimeNanos
                verticalAccuracyMeters = origin.verticalAccuracyMeters
            }
        } else {
            Location(provider).apply {
                time = System.currentTimeMillis() - 300
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

    // ─── Hook GMS / Google Play Services (FusedLocationProviderClient) ───────────
    private fun hookGmsFused(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!settings.isStarted) return

        // Hook LocationResult.getLastLocation() — dipakai tombol "My Location" di SF
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.location.LocationResult",
                lpparam.classLoader,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!settings.isStarted) return
                        val loc = param.result as? Location ?: return
                        loc.latitude = newlat
                        loc.longitude = newlng
                        loc.accuracy = accuracy
                        try {
                            HiddenApiBypass.invoke(loc.javaClass, loc, "setIsFromMockProvider", false)
                        } catch (e: Exception) { /* ignore */ }
                        param.result = loc
                        XposedBridge.log("GS GMS getLastLocation: lat=$newlat lon=$newlng")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("GMS hook getLastLocation failed: $e")
        }

        // Hook LocationResult.getLocations() — dipakai continuous location updates
        try {
            XposedHelpers.findAndHookMethod(
                "com.google.android.gms.location.LocationResult",
                lpparam.classLoader,
                "getLocations",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!settings.isStarted) return
                        @Suppress("UNCHECKED_CAST")
                        val locations = param.result as? List<Location> ?: return
                        locations.forEach { loc ->
                            loc.latitude = newlat
                            loc.longitude = newlng
                            loc.accuracy = accuracy
                            try {
                                HiddenApiBypass.invoke(loc.javaClass, loc, "setIsFromMockProvider", false)
                            } catch (e: Exception) { /* ignore */ }
                        }
                        XposedBridge.log("GS GMS getLocations: patched ${locations.size} locations")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("GMS hook getLocations failed: $e")
        }

        // Hook android.location.Location di dalam proses GMS (fallback)
        try {
            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )
            for (method in LocationClass.declaredMethods) {
                when (method.name) {
                    "getLatitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (settings.isStarted) param.result = newlat
                        }
                    })
                    "getLongitude" -> XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (settings.isStarted) param.result = newlng
                        }
                    })
                }
            }
        } catch (e: Throwable) {
            XposedBridge.log("GMS Location class hook failed: $e")
        }
    }

    // ─── Hook MIUI/HyperOS extra location layer (khusus Xiaomi/Poco) ─────────────
    private fun hookMiuiLocation(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (!settings.isStarted) return

        try {
            XposedHelpers.findAndHookMethod(
                "com.miui.location.XMLocationManager",
                lpparam.classLoader,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!settings.isStarted) return
                        param.result = buildMockLocation(LocationManager.GPS_PROVIDER)
                        XposedBridge.log("GS MIUI getLastLocation: lat=$newlat lon=$newlng")
                    }
                }
            )
        } catch (e: Throwable) {
            // Bukan MIUI atau class tidak ada, skip dengan aman
        }

        try {
            XposedHelpers.findAndHookMethod(
                "com.miui.location.XMLocationManager",
                lpparam.classLoader,
                "getCurrentLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!settings.isStarted) return
                        param.result = buildMockLocation(LocationManager.GPS_PROVIDER)
                        XposedBridge.log("GS MIUI getCurrentLocation: lat=$newlat lon=$newlng")
                    }
                }
            )
        } catch (e: Throwable) {
            // Skip jika tidak ada
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        // ─── Hook GMS Fused Location ──────────────────────────────────────────────
        if (lpparam.packageName == "com.google.android.gms") {
            hookGmsFused(lpparam)
            return
        }

        // ─── Hook MIUI location layer ─────────────────────────────────────────────
        if (lpparam.packageName == "com.miui.location") {
            hookMiuiLocation(lpparam)
            return
        }

        if (lpparam.packageName == "android") { XposedBridge.log("Hooking system server")
        if (settings.isStarted && (settings.isHookedSystem && !ignorePkg.contains(lpparam.packageName))) {
            if (System.currentTimeMillis() - mLastUpdated > 200) {
                updateLocation()
            }

            if (Build.VERSION.SDK_INT < 34) {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.LocationManagerService",
                    lpparam.classLoader
                )

                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass, "getLastLocation",
                    LocationRequest::class.java, String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = buildMockLocation(LocationManager.GPS_PROVIDER)
                        }
                    }
                )

                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.returnType == Boolean::class.java) {
                        if (method.name == "addGnssBatchingCallback" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = false
                                    }
                                }
                            )
                        }
                    }
                }

                XposedHelpers.findAndHookMethod(
                    "com.android.server.LocationManagerService.Receiver",
                    lpparam.classLoader,
                    "callLocationChangedLocked",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val origin = param.args[0] as? Location
                            param.args[0] = buildMockLocation(LocationManager.GPS_PROVIDER, origin)
                            XposedBridge.log("GS: lat: $newlat, lon: $newlng")
                        }
                    }
                )
            } else {

                val LocationManagerServiceClass = XposedHelpers.findClass(
                    "com.android.server.location.LocationManagerService",
                    lpparam.classLoader
                )
                for (method in LocationManagerServiceClass.declaredMethods) {
                    if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = buildMockLocation(LocationManager.GPS_PROVIDER)
                                }
                            }
                        )
                    } else if (method.returnType == Void::class.java) {
                        if (method.name == "startGnssBatch" ||
                            method.name == "addGnssAntennaInfoListener" ||
                            method.name == "addGnssMeasurementsListener" ||
                            method.name == "addGnssNavigationMessageListener"
                        ) {
                            XposedBridge.hookMethod(
                                method,
                                object : XC_MethodHook() {
                                    override fun beforeHookedMethod(param: MethodHookParam) {
                                        param.result = null
                                    }
                                }
                            )
                        }
                    }
                }
                XposedHelpers.findAndHookMethod(
                    LocationManagerServiceClass,
                    "injectLocation",
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val origin = param.args[0] as? Location
                            param.args[0] = buildMockLocation(LocationManager.GPS_PROVIDER, origin)
                            XposedBridge.log("GS: lat: $newlat, lon: $newlng")
                        }
                    }
                )
            }
        }
        } else { // application hook

            val LocationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )
            val interval = 80

            for (method in LocationClass.declaredMethods) {
                if (method.name == "getLatitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = newlat
                                }
                            }
                        }
                    )
                } else if (method.name == "getLongitude") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = newlng
                                }
                            }
                        }
                    )
                } else if (method.name == "getAccuracy") {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (System.currentTimeMillis() - mLastUpdated > interval) {
                                    updateLocation()
                                }
                                if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                                    param.result = accuracy
                                }
                            }
                        }
                    )
                }
            }

            XposedHelpers.findAndHookMethod(
                LocationClass,
                "set",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {

                        if (System.currentTimeMillis() - mLastUpdated > interval) {
                            updateLocation()
                        }
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                            val origin = param.args[0] as? Location
                            param.args[0] = buildMockLocation(LocationManager.GPS_PROVIDER, origin)
                            XposedBridge.log("GS: lat: $newlat, lon: $newlng")
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
                        if (System.currentTimeMillis() - mLastUpdated > interval) {
                            updateLocation()
                        }
                        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
                            val provider = param.args[0] as String
                            param.result = buildMockLocation(provider)
                            XposedBridge.log("GS: lat: $newlat, lon: $newlng")
                        }
                    }
                }
            )
        }
    }
}
