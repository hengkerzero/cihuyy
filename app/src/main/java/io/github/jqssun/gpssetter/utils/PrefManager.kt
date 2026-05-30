package io.github.jqssun.gpssetter.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
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
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val AUTO_OFF_ON_ORDER = "auto_off_on_order"

    // === Auto Walk session persistence ===
    // Disimpan agar UI bisa nyambung lagi ke walk yang sedang berjalan
    // setelah rotasi layar atau Activity/proses di-recreate.
    private const val WALK_ACTIVE = "walk_active"
    private const val WALK_STATE = "walk_state"          // "walking" | "paused"
    private const val WALK_SPEED_KMH = "walk_speed_kmh"
    private const val WALK_ROUTE = "walk_route"          // "lat,lng;lat,lng;..."
    private const val WALK_PROGRESS = "walk_progress"    // Int 0..100
    private const val WALK_LAST_TICK = "walk_last_tick"  // epoch ms tick terakhir

    const val WALK_STATE_WALKING = "walking"
    const val WALK_STATE_PAUSED = "paused"


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

    var isSystemHooked : Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, false)
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).apply() }

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).apply() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).apply()}

    var mapType : Int
        get() = pref.getInt(MAP_TYPE,1)
        set(value) { pref.edit().putInt(MAP_TYPE,value).apply()}

    var darkTheme: Int
        get() = pref.getInt(DARK_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        set(value) = pref.edit().putInt(DARK_THEME, value).apply()

    var isUpdateDisabled: Boolean
        get() = pref.getBoolean(DISABLE_UPDATE, false)
        set(value) = pref.edit().putBoolean(DISABLE_UPDATE, value).apply()

    var isJoystickEnabled: Boolean
        get() = pref.getBoolean(ENABLE_JOYSTICK, false)
        set(value) = pref.edit().putBoolean(ENABLE_JOYSTICK, value).apply()

    var isAutoOffOnOrder: Boolean
        get() = pref.getBoolean(AUTO_OFF_ON_ORDER, false)
        set(value) = pref.edit().putBoolean(AUTO_OFF_ON_ORDER, value).apply()

    // === Auto Walk session ===

    /** True selama ada sesi walk yang aktif (sedang berjalan atau dijeda). */
    val isWalkSessionActive: Boolean
        get() = pref.getBoolean(WALK_ACTIVE, false)

    /** State sesi walk yang tersimpan: [WALK_STATE_WALKING] atau [WALK_STATE_PAUSED]. */
    val walkSessionState: String
        get() = pref.getString(WALK_STATE, WALK_STATE_WALKING) ?: WALK_STATE_WALKING

    val walkSessionSpeedKmh: Float
        get() = pref.getFloat(WALK_SPEED_KMH, 5f)

    /** Route ter-encode ("lat,lng;lat,lng;..."), null jika tidak ada. */
    val walkSessionRoute: String?
        get() = pref.getString(WALK_ROUTE, null)

    val walkSessionProgress: Int
        get() = pref.getInt(WALK_PROGRESS, 0)

    /** Epoch ms saat service terakhir update (untuk deteksi service mati). */
    val walkSessionLastTick: Long
        get() = pref.getLong(WALK_LAST_TICK, 0L)

    /**
     * Simpan sesi walk baru. Dipanggil service saat walk dimulai.
     */
    fun saveWalkSession(state: String, speedKmh: Float, routeEncoded: String, progress: Int) {
        pref.edit().apply {
            putBoolean(WALK_ACTIVE, true)
            putString(WALK_STATE, state)
            putFloat(WALK_SPEED_KMH, speedKmh)
            putString(WALK_ROUTE, routeEncoded)
            putInt(WALK_PROGRESS, progress)
            putLong(WALK_LAST_TICK, System.currentTimeMillis())
            apply()
        }
    }

    /** Update progress + timestamp tick (dipanggil berkala oleh service). */
    fun updateWalkSessionProgress(progress: Int) {
        pref.edit().apply {
            putInt(WALK_PROGRESS, progress)
            putLong(WALK_LAST_TICK, System.currentTimeMillis())
            apply()
        }
    }

    /** Update state walk (walking/paused) + timestamp. */
    fun updateWalkSessionState(state: String) {
        pref.edit().apply {
            putString(WALK_STATE, state)
            putLong(WALK_LAST_TICK, System.currentTimeMillis())
            apply()
        }
    }

    /** Hapus sesi walk. Dipanggil saat walk selesai/stop/error. */
    fun clearWalkSession() {
        pref.edit().apply {
            putBoolean(WALK_ACTIVE, false)
            remove(WALK_STATE)
            remove(WALK_ROUTE)
            remove(WALK_PROGRESS)
            remove(WALK_LAST_TICK)
            apply()
        }
    }

    fun update(start: Boolean, la: Double, ln: Double) {
        // Teleport biasa = diam di tempat, jadi speed & bearing = 0.
        updateInternal(start, la, ln, 0f, 0f)
    }

    /**
     * Update lokasi sekaligus speed (m/s) & bearing (derajat).
     * Dipakai oleh Auto Walk agar lokasi mock terlihat bergerak natural.
     */
    fun updateWithMotion(start: Boolean, la: Double, ln: Double, speedMs: Float, bearingDeg: Float) {
        updateInternal(start, la, ln, speedMs, bearingDeg)
    }

    private fun updateInternal(start: Boolean, la: Double, ln: Double, speedMs: Float, bearingDeg: Float) {
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