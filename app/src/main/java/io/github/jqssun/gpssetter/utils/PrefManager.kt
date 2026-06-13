package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


@SuppressLint("WorldReadableFiles")
object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"
    private const val LONGITUDE = "longitude"
    // Key presisi tinggi (Double disimpan sebagai String). Reader baru pakai ini,
    // key Float lama tetap ditulis untuk kompatibilitas mundur.
    private const val LATITUDE_HP = "latitude_hp"
    private const val LONGITUDE_HP = "longitude_hp"
    private const val MOCK_SPEED = "mock_speed"
    private const val MOCK_BEARING = "mock_bearing"
    private const val MOCK_ALTITUDE = "mock_altitude"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val ENABLE_FLOATING = "floating_enabled"
    private const val AUTO_OFF_ON_ORDER = "auto_off_on_order"
    private const val MANUAL_PARAMS = "manual_params_enabled"
    private const val MANUAL_SPEED = "manual_speed"
    private const val MANUAL_BEARING = "manual_bearing"
    private const val MANUAL_ALTITUDE = "manual_altitude"
    private const val ANDROID_OS_MODE = "android_os_mode"

    // Nilai mode kompatibilitas Android OS
    const val OS_MODE_LEGACY = "legacy"   // Android 10 - 12
    const val OS_MODE_ANDROID_13 = "android13"   // Android 13
    const val OS_MODE_MODERN = "modern"   // Android 14+


    private val pref: SharedPreferences by lazy {
        try {
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_WORLD_READABLE
            )
        }catch (e:SecurityException){
            val prefsFile = "${BuildConfig.APPLICATION_ID}_prefs"
            gsApp.getSharedPreferences(
                prefsFile,
                Context.MODE_PRIVATE
            )
        }

    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = pref.getString(LATITUDE_HP, null)?.toDoubleOrNull()
            ?: pref.getFloat(LATITUDE, 40.7128F).toDouble()

    val getLng : Double
        get() = pref.getString(LONGITUDE_HP, null)?.toDoubleOrNull()
            ?: pref.getFloat(LONGITUDE, -74.0060F).toDouble()

    val getSpeed : Float
        get() = pref.getFloat(MOCK_SPEED, 0f)

    val getBearing : Float
        get() = pref.getFloat(MOCK_BEARING, 0f)

    val getAltitude : Double
        get() = pref.getString(MOCK_ALTITUDE, null)?.toDoubleOrNull() ?: 0.0

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    // ===== Parameter manual (Accuracy/Altitude/Bearing/Speed) =====

    /** Saat true, slider parameter manual aktif & nilainya dipakai. */
    var isManualParams: Boolean
        get() = pref.getBoolean(MANUAL_PARAMS, false)
        set(value) { pref.edit().putBoolean(MANUAL_PARAMS, value).apply() }

    var manualSpeed: Float
        get() = pref.getFloat(MANUAL_SPEED, 0f)
        set(value) { pref.edit().putFloat(MANUAL_SPEED, value).apply() }

    var manualBearing: Float
        get() = pref.getFloat(MANUAL_BEARING, 0f)
        set(value) { pref.edit().putFloat(MANUAL_BEARING, value).apply() }

    var manualAltitude: Float
        get() = pref.getFloat(MANUAL_ALTITUDE, 0f)
        set(value) { pref.edit().putFloat(MANUAL_ALTITUDE, value).apply() }

    /** Mode kompatibilitas Android OS: legacy (10-12), android13, modern (14+). */
    var androidOsMode: String
        get() {
            val defaultMode = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
                OS_MODE_ANDROID_13
            } else {
                OS_MODE_MODERN
            }
            return pref.getString(ANDROID_OS_MODE, defaultMode) ?: defaultMode
        }
        set(value) { pref.edit().putString(ANDROID_OS_MODE, value).apply() }

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,1)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    /** Joystick virtual (service terpisah dari floating control). */
    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    /**
     * Floating Control (tombol Stop + Refresh).
     * Muncul otomatis saat app masuk background & GPS Normal sudah Start.
     */
    var isFloatingEnabled: Boolean
        get() = pref.getBoolean(ENABLE_FLOATING, false)
        set(value) = pref.edit().putBoolean(ENABLE_FLOATING, value).apply()

    var isAutoOffOnOrder: Boolean
        get() = pref.getBoolean(AUTO_OFF_ON_ORDER, false)
        set(value) = pref.edit().putBoolean(AUTO_OFF_ON_ORDER, value).apply()

    fun update(start: Boolean, la: Double, ln: Double) {
        // Teleport biasa = diam di tempat (speed & bearing = 0),
        // KECUALI user mengaktifkan parameter manual.
        if (isManualParams) {
            updateInternal(start, la, ln, manualSpeed, manualBearing, manualAltitude.toDouble())
        } else {
            updateInternal(start, la, ln, 0f, 0f, 0.0)
        }
    }

    /**
     * Update lokasi sekaligus speed (m/s) & bearing (derajat).
     * Dipakai oleh Auto Walk agar lokasi mock terlihat bergerak natural.
     */
    fun updateWithMotion(start: Boolean, la: Double, ln: Double, speedMs: Float, bearingDeg: Float) {
        val alt = if (isManualParams) manualAltitude.toDouble() else 0.0
        updateInternal(start, la, ln, speedMs, bearingDeg, alt)
    }

    private fun updateInternal(start: Boolean, la: Double, ln: Double, speedMs: Float, bearingDeg: Float, altitude: Double) {
        runInBackground {
            pref.edit().apply {
                // Presisi tinggi (Double) — sumber kebenaran untuk reader baru
                putString(LATITUDE_HP, la.toString())
                putString(LONGITUDE_HP, ln.toString())
                // Legacy Float — dipertahankan agar reader lama tetap berfungsi
                putFloat(LATITUDE, la.toFloat())
                putFloat(LONGITUDE, ln.toFloat())
                putFloat(MOCK_SPEED, speedMs)
                putFloat(MOCK_BEARING, bearingDeg)
                putString(MOCK_ALTITUDE, altitude.toString())
                putBoolean(START, start)
                apply()
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runInBackground(method: suspend () -> Unit){
        GlobalScope.launch(Dispatchers.IO) {
            method.invoke()
        }
    }

}
