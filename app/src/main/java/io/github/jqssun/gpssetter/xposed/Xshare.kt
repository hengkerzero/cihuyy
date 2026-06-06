package io.github.jqssun.gpssetter.xposed
import android.os.Build
import de.robv.android.xposed.XSharedPreferences
import io.github.jqssun.gpssetter.BuildConfig

class Xshare {

    private var xPref: XSharedPreferences? = null

    private fun pref() : XSharedPreferences {
        xPref = XSharedPreferences(BuildConfig.APPLICATION_ID,"${BuildConfig.APPLICATION_ID}_prefs")
        return xPref as XSharedPreferences
    }

    val isStarted : Boolean
    get() = pref().getBoolean(
        "start",
        false
    )

    val getLat: Double
    get() = pref().let { p ->
        p.getString("latitude_hp", null)?.toDoubleOrNull()
            ?: p.getFloat("latitude", 45.0000000.toFloat()).toDouble()
    }


    val getLng : Double
    get() = pref().let { p ->
        p.getString("longitude_hp", null)?.toDoubleOrNull()
            ?: p.getFloat("longitude", 0.0000000.toFloat()).toDouble()
    }

    val getSpeed : Float
    get() = pref().getFloat("mock_speed", 0f)

    val getBearing : Float
    get() = pref().getFloat("mock_bearing", 0f)

    val getAltitude : Double
    get() = pref().getString("mock_altitude", null)?.toDoubleOrNull() ?: 0.0

    val isRandomPosition :Boolean
    get() = pref().getBoolean(
        "random_position",
        false
    )

    val accuracy : String?
    get() = pref().getString("accuracy_level","10")

    val androidOsMode : String
    get() {
        val defaultMode = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.TIRAMISU) {
            "android13"
        } else {
            "modern"
        }
        return pref().getString("android_os_mode", defaultMode) ?: defaultMode
    }

    val reload = pref().reload()

}