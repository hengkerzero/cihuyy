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
import io.github.jqssun.gpssetter.utils.PrefManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import timber.log.Timber
import java.util.*
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object LocationHook {

    var newlat: Double = 45.0000
    var newlng: Double = 0.0000
    private const val pi = 3.14159265359
    private var accuracy: Float = 0.0f
    private var mockSpeed: Float = 0.0f
    private var mockBearing: Float = 0.0f
    private var mockAltitude: Double = 0.0
    private val rand: Random = Random()
    private const val earth = 6378137.0
    private val settings = Xshare()
    private var mLastUpdated: Long = 0

    // --- Random Location (natural GPS drift) ---
    // Posisi acak dibuat seperti drift GPS asli saat diam: bergerak pelan dan
    // halus dalam radius kecil, bukan loncat ke titik acak baru tiap update.
    private const val JITTER_RADIUS = 3.0          // radius maksimum drift (meter)
    private const val JITTER_SPEED = 0.3           // kecepatan drift (meter/detik)
    private const val JITTER_TARGET_INTERVAL = 3000L // pilih target baru tiap 3 detik
    private var curOffN: Double = 0.0              // offset saat ini ke utara (meter)
    private var curOffE: Double = 0.0              // offset saat ini ke timur (meter)
    private var tgtOffN: Double = 0.0              // target offset utara (meter)
    private var tgtOffE: Double = 0.0              // target offset timur (meter)
    private var lastJitterTarget: Long = 0
    private var lastJitterCalc: Long = 0

    private val ignorePkg = arrayListOf("com.android.location.fused", BuildConfig.APPLICATION_ID)

    private val context by lazy { AndroidAppHelper.currentApplication() as Context }

    private fun updateLocation() {
        try {
            val now = System.currentTimeMillis()
            mLastUpdated = now

            if (settings.isRandomPosition) {
                // Pilih target acak baru di dalam radius secara berkala.
                if (now - lastJitterTarget > JITTER_TARGET_INTERVAL) {
                    lastJitterTarget = now
                    val angle = rand.nextDouble() * 2.0 * pi
                    val r = rand.nextDouble() * JITTER_RADIUS
                    tgtOffN = r * cos(angle)
                    tgtOffE = r * sin(angle)
                }

                // Geser offset saat ini menuju target dengan kecepatan terbatas
                // (berbasis waktu, jadi tetap halus walau frekuensi update berubah).
                val dt = (now - lastJitterCalc).coerceIn(0L, 1000L) / 1000.0
                lastJitterCalc = now
                val step = JITTER_SPEED * dt
                val dN = tgtOffN - curOffN
                val dE = tgtOffE - curOffE
                val dist = hypot(dN, dE)
                if (dist <= step || dist == 0.0) {
                    curOffN = tgtOffN
                    curOffE = tgtOffE
                } else {
                    curOffN += dN / dist * step
                    curOffE += dE / dist * step
                }

                val dlat = curOffN / earth
                val dlng = curOffE / (earth * cos(pi * settings.getLat / 180.0))
                newlat = settings.getLat + (dlat * 180.0 / pi)
                newlng = settings.getLng + (dlng * 180.0 / pi)
            } else {
                newlat = settings.getLat
                newlng = settings.getLng
            }

            accuracy = settings.accuracy!!.toFloat()
            mockSpeed = settings.getSpeed
            mockBearing = settings.getBearing
            mockAltitude = settings.getAltitude

        } catch (e: Exception) {
            Timber.tag("GPS Setter")
                .e(e, "Failed to get XposedSettings for %s", context.packageName)
        }
    }

    private fun hookLegacySystemServer(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return runCatching {
            val locationManagerServiceClass = XposedHelpers.findClass(
                "com.android.server.LocationManagerService",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                locationManagerServiceClass,
                "getLastLocation",
                LocationRequest::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val location = Location(LocationManager.GPS_PROVIDER)
                        location.time = System.currentTimeMillis() - 300
                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = mockAltitude
                        location.speed = mockSpeed
                        location.bearing = mockBearing
                        location.accuracy = accuracy
                        location.speedAccuracyMetersPerSecond = 0F
                        param.result = location
                    }
                }
            )

            for (method in locationManagerServiceClass.declaredMethods) {
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
                        lateinit var location: Location
                        lateinit var originLocation: Location
                        if (param.args[0] == null) {
                            location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                        } else {
                            originLocation = param.args[0] as Location
                            location = Location(originLocation.provider)
                            location.time = originLocation.time
                            location.accuracy = accuracy
                            location.bearing = originLocation.bearing
                            location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                            location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                        }

                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = mockAltitude
                        location.speed = mockSpeed
                        location.bearing = mockBearing
                        location.speedAccuracyMetersPerSecond = 0F
                        XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                        try {
                            HiddenApiBypass.invoke(
                                location.javaClass, location, "setIsFromMockProvider", false
                            )
                        } catch (e: Exception) {
                            XposedBridge.log("LocationHook: unable to set mock $e")
                        }
                        param.args[0] = location
                    }
                }
            )
            true
        }.getOrElse { e ->
            XposedBridge.log("GS: legacy system-server hook failed: $e")
            false
        }
    }

    private fun hookModernSystemServer(lpparam: XC_LoadPackage.LoadPackageParam): Boolean {
        return runCatching {
            val locationManagerServiceClass = XposedHelpers.findClass(
                "com.android.server.location.LocationManagerService",
                lpparam.classLoader
            )

            for (method in locationManagerServiceClass.declaredMethods) {
                if (method.name == "getLastLocation" && method.returnType == Location::class.java) {
                    XposedBridge.hookMethod(
                        method,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                                location.latitude = newlat
                                location.longitude = newlng
                                location.altitude = mockAltitude
                                location.speed = mockSpeed
                                location.bearing = mockBearing
                                location.accuracy = accuracy
                                location.speedAccuracyMetersPerSecond = 0F
                                param.result = location
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
                locationManagerServiceClass,
                "injectLocation",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        lateinit var location: Location
                        lateinit var originLocation: Location
                        if (param.args[0] == null) {
                            location = Location(LocationManager.GPS_PROVIDER)
                            location.time = System.currentTimeMillis() - 300
                        } else {
                            originLocation = param.args[0] as Location
                            location = Location(originLocation.provider)
                            location.time = originLocation.time
                            location.accuracy = accuracy
                            location.bearing = originLocation.bearing
                            location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                            location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                            location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                        }

                        location.latitude = newlat
                        location.longitude = newlng
                        location.altitude = mockAltitude
                        location.speed = mockSpeed
                        location.bearing = mockBearing
                        location.speedAccuracyMetersPerSecond = 0F
                        XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                        try {
                            HiddenApiBypass.invoke(
                                location.javaClass, location, "setIsFromMockProvider", false
                            )
                        } catch (e: Exception) {
                            XposedBridge.log("LocationHook: unable to set mock $e")
                        }
                        param.args[0] = location
                    }
                }
            )
            true
        }.getOrElse { e ->
            XposedBridge.log("GS: modern system-server hook failed: $e")
            false
        }
    }

    private fun hookAndroid13Compatibility(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (hookModernSystemServer(lpparam)) return
        hookLegacySystemServer(lpparam)
    }

    private fun fakeLocation(provider: String = LocationManager.GPS_PROVIDER): Location {
        return Location(provider).apply {
            time = System.currentTimeMillis() - 300
            latitude = newlat
            longitude = newlng
            altitude = mockAltitude
            speed = mockSpeed
            bearing = mockBearing
            accuracy = this@LocationHook.accuracy
            speedAccuracyMetersPerSecond = 0F
            try {
                HiddenApiBypass.invoke(javaClass, this, "setIsFromMockProvider", false)
            } catch (_: Exception) {
            }
        }
    }

    private fun hookFusedLocationApis(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val fusedClass = XposedHelpers.findClass(
                "com.google.android.gms.location.FusedLocationProviderClient",
                lpparam.classLoader
            )

            for (method in fusedClass.declaredMethods) {
                when (method.name) {
                    "getLastLocation", "getCurrentLocation" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    runCatching {
                                        val tasksClass = XposedHelpers.findClass(
                                            "com.google.android.gms.tasks.Tasks",
                                            lpparam.classLoader
                                        )
                                        val fake = fakeLocation()
                                        val forResult = XposedHelpers.findMethodBestMatch(
                                            tasksClass,
                                            "forResult",
                                            Any::class.java
                                        )
                                        param.result = forResult.invoke(null, fake)
                                    }.onFailure { e ->
                                        XposedBridge.log("GS: fused ${method.name} hook failed: $e")
                                    }
                                }
                            }
                        )
                    }

                    "requestLocationUpdates" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val locationCallbackIndex = method.parameterTypes.indexOfFirst {
                                        it.name.contains("LocationCallback")
                                    }
                                    if (locationCallbackIndex >= 0 && locationCallbackIndex < param.args.size) {
                                        val callback = param.args[locationCallbackIndex]
                                        XposedHelpers.findAndHookMethod(
                                            callback.javaClass,
                                            "onLocationResult",
                                            XposedHelpers.findClass(
                                                "com.google.android.gms.location.LocationResult",
                                                lpparam.classLoader
                                            ),
                                            object : XC_MethodHook() {
                                                override fun beforeHookedMethod(callbackParam: MethodHookParam) {
                                                    callbackParam.args[0] = XposedHelpers.callStaticMethod(
                                                        XposedHelpers.findClass(
                                                            "com.google.android.gms.location.LocationResult",
                                                            lpparam.classLoader
                                                        ),
                                                        "create",
                                                        listOf(fakeLocation())
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                fusedClass,
                "getLastLocation",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        runCatching {
                            val tasksClass = XposedHelpers.findClass(
                                "com.google.android.gms.tasks.Tasks",
                                lpparam.classLoader
                            )
                            val fakeLocation = fakeLocation()
                            val forResult = XposedHelpers.findMethodBestMatch(
                                tasksClass,
                                "forResult",
                                Any::class.java
                            )
                            param.result = forResult.invoke(null, fakeLocation)
                        }.onFailure { e ->
                            XposedBridge.log("GS: fused getLastLocation hook failed: $e")
                        }
                    }
                }
            )

            runCatching {
                Unit
            }

            runCatching {
                XposedHelpers.findAndHookMethod(
                    "com.google.android.gms.location.LocationResult",
                    lpparam.classLoader,
                    "getLastLocation",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = fakeLocation()
                        }
                    }
                )
            }
        }
    }

    private fun hookLocationListenerObject(listener: Any, classLoader: ClassLoader) {
        runCatching {
            XposedHelpers.findAndHookMethod(
                listener.javaClass,
                "onLocationChanged",
                Location::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.args[0] = fakeLocation()
                    }
                }
            )
        }.onFailure { e ->
            XposedBridge.log("GS: listener hook failed for ${listener.javaClass.name}: $e")
        }
    }

    private fun hookFrameworkLocationManagerApis(lpparam: XC_LoadPackage.LoadPackageParam) {
        runCatching {
            val locationManagerClass = XposedHelpers.findClass(
                "android.location.LocationManager",
                lpparam.classLoader
            )

            for (method in locationManagerClass.declaredMethods) {
                when (method.name) {
                    "getLastKnownLocation" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = fakeLocation(param.args.firstOrNull()?.toString() ?: LocationManager.GPS_PROVIDER)
                                }
                            }
                        )
                    }

                    "getCurrentLocation" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val consumerIndex = method.parameterTypes.indexOfFirst {
                                        it.name.contains("Consumer")
                                    }
                                    if (consumerIndex >= 0 && consumerIndex < param.args.size) {
                                        runCatching {
                                            XposedHelpers.callMethod(param.args[consumerIndex], "accept", fakeLocation())
                                        }
                                    }
                                    param.result = null
                                }
                            }
                        )
                    }

                    "requestLocationUpdates", "requestSingleUpdate" -> {
                        XposedBridge.hookMethod(
                            method,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    val listenerIndex = method.parameterTypes.indexOfFirst {
                                        it.name.contains("LocationListener")
                                    }
                                    if (listenerIndex >= 0 && listenerIndex < param.args.size) {
                                        hookLocationListenerObject(param.args[listenerIndex], lpparam.classLoader)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }.onFailure { e ->
            XposedBridge.log("GS: framework LocationManager hook failed: $e")
        }
    }

    @SuppressLint("NewApi")
    fun initHooks(lpparam: XC_LoadPackage.LoadPackageParam) {

        if (lpparam.packageName == "android") { XposedBridge.log("Hooking system server")
        if (settings.isStarted && !ignorePkg.contains(lpparam.packageName)) {
            if (System.currentTimeMillis() - mLastUpdated > 200) {
                updateLocation()
            }

            when (settings.androidOsMode) {
                PrefManager.OS_MODE_LEGACY -> hookLegacySystemServer(lpparam)
                PrefManager.OS_MODE_ANDROID_13 -> hookAndroid13Compatibility(lpparam)
                PrefManager.OS_MODE_MODERN -> hookModernSystemServer(lpparam)
                else -> if (Build.VERSION.SDK_INT < 34) {
                    hookAndroid13Compatibility(lpparam)
                } else {
                    hookModernSystemServer(lpparam)
                }
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
                            lateinit var location: Location
                            lateinit var originLocation: Location
                            if (param.args[0] == null) {
                                location = Location(LocationManager.GPS_PROVIDER)
                                location.time = System.currentTimeMillis() - 300
                            } else {
                                originLocation = param.args[0] as Location
                                location = Location(originLocation.provider)
                                location.time = originLocation.time
                                location.accuracy = accuracy
                                location.bearing = originLocation.bearing
                                location.bearingAccuracyDegrees = originLocation.bearingAccuracyDegrees
                                location.elapsedRealtimeNanos = originLocation.elapsedRealtimeNanos
                                location.verticalAccuracyMeters = originLocation.verticalAccuracyMeters
                            }

                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = mockAltitude
                            location.speed = mockSpeed
                            location.bearing = mockBearing
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.args[0] = location
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
                            val location = Location(provider)
                            location.time = System.currentTimeMillis() - 300
                            location.latitude = newlat
                            location.longitude = newlng
                            location.altitude = mockAltitude
                            location.speed = mockSpeed
                            location.bearing = mockBearing
                            location.speedAccuracyMetersPerSecond = 0F
                            XposedBridge.log("GS: lat: ${location.latitude}, lon: ${location.longitude}")
                            try {
                                HiddenApiBypass.invoke(
                                    location.javaClass, location, "setIsFromMockProvider", false
                                )
                            } catch (e: Exception) {
                                XposedBridge.log("LocationHook: unable to set mock $e")
                            }
                            param.result = location
                        }
                    }
                }
            )

            hookFusedLocationApis(lpparam)
            hookFrameworkLocationManagerApis(lpparam)
        }
    }
}
