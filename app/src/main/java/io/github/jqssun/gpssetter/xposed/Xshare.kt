package io.github.jqssun.gpssetter.xposed
import android.content.SharedPreferences

// hook-side view of the settings the app mirrors into LSPosed remote preferences (see PrefManager)
class Xshare(private val prefs: SharedPreferences) {

    companion object {
        const val GROUP = "settings"
    }

    val isStarted : Boolean
    get() = prefs.getBoolean(
        "start",
        false
    )

    // Double bits written by PrefManager; only ever Long under these keys, so never a cross-type read
    val getLat: Double
    get() = Double.fromBits(prefs.getLong("latitude_d", (45.0).toRawBits()))

    val getLng : Double
    get() = Double.fromBits(prefs.getLong("longitude_d", (0.0).toRawBits()))

    val isHookedSystem : Boolean
    get() = prefs.getBoolean(
        "system_hooked",
        true
    )

    val isRandomPosition :Boolean
    get() = prefs.getBoolean(
        "random_position",
        false
    )

    val accuracy : String?
    get() = prefs.getString("accuracy_level","10")

    // motion for route playback (defaults mean "static": no speed, bearing from the real fix)
    val altitude: Double
    get() = Double.fromBits(prefs.getLong("altitude_d", (0.0).toRawBits()))

    val speed: Float
    get() = prefs.getFloat("speed", 0f)

    val bearing: Float
    get() = prefs.getFloat("bearing", -1f)

    val locationUpdateIntervalMs: Long
        get() = LocationUpdateInterval.normalize(prefs.getLong(LocationUpdateInterval.KEY, LocationUpdateInterval.DEFAULT_MS))

}
