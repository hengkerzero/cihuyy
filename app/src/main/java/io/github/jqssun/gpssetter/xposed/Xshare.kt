package io.github.jqssun.gpssetter.xposed
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

    val isHookedSystem : Boolean
    get() = pref().getBoolean(
        "system_hooked",
        true
    )

    val isRandomPosition :Boolean
    get() = pref().getBoolean(
        "random_position",
        false
    )

    val accuracy : String?
    get() = pref().getString("accuracy_level","10")

    val reload = pref().reload()

}