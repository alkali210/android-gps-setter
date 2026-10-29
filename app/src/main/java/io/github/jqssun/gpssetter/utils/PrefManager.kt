package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.MutableLiveData
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.xposed.Xshare
import io.github.libxposed.service.XposedService
import timber.log.Timber


object PrefManager   {

    private const val START = "start"
    private const val LATITUDE = "latitude"          // legacy Float; read only to migrate old installs
    private const val LONGITUDE = "longitude"
    private const val LATITUDE_D = "latitude_d"       // Double bits (Long); Float lost ~1 m of precision
    private const val LONGITUDE_D = "longitude_d"
    private const val DEF_LAT = 40.7128
    private const val DEF_LNG = -74.0060
    private const val HOOKED_SYSTEM = "system_hooked"
    private const val RANDOM_POSITION = "random_position"
    private const val ACCURACY_SETTING = "accuracy_level"
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val ACCENT = "accent_color"
    // motion, set during route playback; static spoofing leaves these at the defaults below
    private const val ALTITUDE = "altitude_d" // Double bits
    private const val SPEED = "speed"         // m/s
    private const val BEARING = "bearing"     // degrees, -1 = unset (use the real fix's bearing)


    private val pref: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
    }

    // the hook can't read app-private files, so the settings it needs are mirrored into
    // LSPosed's remote preferences (libxposed service) whenever they change
    @Volatile private var remote: SharedPreferences? = null
    val moduleActive = MutableLiveData<Boolean>()
    private val mirror = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> syncRemote() }

    fun onServiceBind(service: XposedService) {
        remote = try {
            service.getRemotePreferences(Xshare.GROUP)
        } catch (e: RuntimeException) {
            Timber.e(e, "Remote preferences unavailable")
            return
        }
        pref.registerOnSharedPreferenceChangeListener(mirror)
        syncRemote()
        moduleActive.postValue(true)
    }

    fun onServiceDied() {
        remote = null
        moduleActive.postValue(false)
    }

    private fun syncRemote() {
        val remote = remote ?: return
        remote.edit()
            .putBoolean(START, isStarted)
            .putLong(LATITUDE_D, getLat.toRawBits())
            .putLong(LONGITUDE_D, getLng.toRawBits())
            .putBoolean(HOOKED_SYSTEM, isSystemHooked)
            .putBoolean(RANDOM_POSITION, isRandomPosition)
            .putString(ACCURACY_SETTING, accuracy)
            .putLong(ALTITUDE, altitude.toRawBits())
            .putFloat(SPEED, speed)
            .putFloat(BEARING, bearing)
            .apply()
    }

    val altitude: Double
        get() = if (pref.contains(ALTITUDE)) Double.fromBits(pref.getLong(ALTITUDE, 0)) else 0.0
    val speed: Float get() = pref.getFloat(SPEED, 0f)
    val bearing: Float get() = pref.getFloat(BEARING, -1f)

    // route playback: move to a point with motion. start=true; mirror listener syncs to the hook.
    fun updateMotion(la: Double, ln: Double, altitude: Double, speed: Float, bearing: Float) {
        pref.edit()
            .putLong(LATITUDE_D, la.toRawBits())
            .putLong(LONGITUDE_D, ln.toRawBits())
            .putBoolean(START, true)
            .putLong(ALTITUDE, altitude.toRawBits())
            .putFloat(SPEED, speed)
            .putFloat(BEARING, bearing)
            .apply()
    }


    val isStarted : Boolean
        get() = pref.getBoolean(START, false)

    val getLat : Double
        get() = readCoord(LATITUDE_D, LATITUDE, DEF_LAT)

    val getLng : Double
        get() = readCoord(LONGITUDE_D, LONGITUDE, DEF_LNG)

    // prefer the Double-bits value; fall back to the legacy Float once, so an upgrade keeps the saved point
    private fun readCoord(bitsKey: String, legacyKey: String, def: Double): Double = when {
        pref.contains(bitsKey) -> Double.fromBits(pref.getLong(bitsKey, 0))
        pref.contains(legacyKey) -> pref.getFloat(legacyKey, def.toFloat()).toDouble()
        else -> def
    }

    var isSystemHooked : Boolean
        get() = pref.getBoolean(HOOKED_SYSTEM, true) // must match Xshare.isHookedSystem default
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

    var accentIndex: Int
        get() = pref.getInt(ACCENT, 0) // default Blue
        set(value) = pref.edit().putInt(ACCENT, value).apply()

    fun update(start:Boolean, la: Double, ln: Double) {
        // Update memory before publishing playback completion; apply persists asynchronously.
        pref.edit()
            .putLong(LATITUDE_D, la.toRawBits())
            .putLong(LONGITUDE_D, ln.toRawBits())
            .putBoolean(START, start)
            .putLong(ALTITUDE, 0.0.toRawBits())
            .putFloat(SPEED, 0f)
            .putFloat(BEARING, -1f)
            .apply()
    }


}