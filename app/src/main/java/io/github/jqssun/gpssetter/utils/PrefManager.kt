package io.github.jqssun.gpssetter.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.MutableLiveData
import io.github.jqssun.gpssetter.BuildConfig
import io.github.jqssun.gpssetter.gsApp
import io.github.jqssun.gpssetter.xposed.HookSettings
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.ACCURACY_SETTING
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.ALTITUDE
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.BEARING
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.HOOKED_SYSTEM
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.LATITUDE_D
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.LONGITUDE_D
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.RANDOM_POSITION
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.SPEED
import io.github.jqssun.gpssetter.xposed.HookSettings.Companion.START
import io.github.jqssun.gpssetter.xposed.Xshare
import io.github.jqssun.gpssetter.xposed.LocationUpdateInterval
import io.github.libxposed.service.XposedService
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean


object PrefManager   {

    private const val LATITUDE = "latitude"          // legacy Float; read only to migrate old installs
    private const val LONGITUDE = "longitude"
    private const val DEF_LAT = 40.7128
    private const val DEF_LNG = -74.0060
    private const val MAP_TYPE = "map_type"
    private const val DARK_THEME = "dark_theme"
    private const val DISABLE_UPDATE = "update_disabled"
    private const val ENABLE_JOYSTICK = "joystick_enabled"
    private const val KEEP_SCREEN_ON_DURING_ROUTE = "keep_screen_on_during_route"
    private const val ACCENT = "accent_color"
    private const val RESERVED_REVISION = "hook_reserved_revision"
    private const val REVISION_BATCH_SIZE = 65_536L


    private val pref: SharedPreferences by lazy {
        gsApp.getSharedPreferences("${BuildConfig.APPLICATION_ID}_prefs", Context.MODE_PRIVATE)
    }

    private class Remote(val prefs: SharedPreferences, val executor: Executor, val writeFile: (ByteArray) -> Unit) {
        private var revision = 0L
        private var reservedThrough = 0L

        fun nextRevision(local: SharedPreferences): Long? {
            if (revision == reservedThrough) {
                // Reserve before publishing so a new process can never reuse a revision.
                // A range avoids synchronous disk writes on each route tick.
                val previous = local.getLong(RESERVED_REVISION, 0L)
                val reserved = previous + REVISION_BATCH_SIZE
                if (!local.edit().putLong(RESERVED_REVISION, reserved).commit()) return null
                revision = previous
                reservedThrough = reserved
            }
            return ++revision
        }
    }
    private val writer = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean()
    @Volatile private var remote: Remote? = null
    val moduleActive = MutableLiveData<Boolean>()

    fun onServiceBind(service: XposedService) {
        val prefs = try {
            service.getRemotePreferences(Xshare.GROUP)
        } catch (e: RuntimeException) {
            Timber.e(e, "Remote preferences unavailable")
            return
        }
        bindRemote(prefs, writer) { bytes ->
            // API 102 returns a writable descriptor, not a truncating stream or an atomic
            // rename operation. Serialize writers and publish the end marker in the same write.
            ParcelFileDescriptor.AutoCloseOutputStream(service.openRemoteFile(Xshare.FILE)).use {
                it.channel.truncate(0)
                it.write(bytes)
            }
        }
        moduleActive.postValue(true)
    }

    internal fun bindRemote(prefs: SharedPreferences, executor: Executor, writeFile: (ByteArray) -> Unit) {
        remote = Remote(prefs, executor, writeFile)
        syncRemote()
    }

    fun onServiceDied() {
        remote = null
        moduleActive.postValue(false)
    }

    private fun syncRemote() {
        val executor = remote?.executor ?: return
        if (!queued.compareAndSet(false, true)) return
        executor.execute {
            // At most one pending sync behind the running one. Capture both transports from
            // one local edit, and recheck the binding in case the service died or reconnected.
            queued.set(false)
            val target = remote ?: return@execute
            val revision = target.nextRevision(pref)
            if (revision == null) {
                Timber.e("Couldn't reserve a hook settings revision")
                return@execute
            }
            val snapshot = HookSettings.fromLocalPreferences(pref, DEF_LAT, DEF_LNG, revision)
            try {
                snapshot.publish(target.prefs)
            } catch (e: RuntimeException) {
                Timber.e(e, "Couldn't push hook settings")
            }
            try {
                target.writeFile(snapshot.encode())
            } catch (e: Exception) {
                Timber.e(e, "Couldn't write ${Xshare.FILE}")
            }
        }
    }

    // Publish once per transaction, not once per changed key, and ignore UI-only edits.
    private fun SharedPreferences.Editor.applyHookSettings() {
        apply()
        syncRemote()
    }

    val altitude: Double
        get() = if (pref.contains(ALTITUDE)) Double.fromBits(pref.getLong(ALTITUDE, 0)) else 0.0
    val speed: Float get() = pref.getFloat(SPEED, 0f)
    val bearing: Float get() = pref.getFloat(BEARING, -1f)

    var locationUpdateIntervalMs: Long
        get() = LocationUpdateInterval.normalize(pref.getLong(LocationUpdateInterval.KEY, LocationUpdateInterval.DEFAULT_MS))
        set(value) { pref.edit().putLong(LocationUpdateInterval.KEY, LocationUpdateInterval.normalize(value)).applyHookSettings() }

    // Route playback moves the point, motion and active flag together.
    fun updateMotion(la: Double, ln: Double, altitude: Double, speed: Float, bearing: Float) {
        pref.edit()
            .putLong(LATITUDE_D, la.toRawBits())
            .putLong(LONGITUDE_D, ln.toRawBits())
            .putBoolean(START, true)
            .putLong(ALTITUDE, altitude.toRawBits())
            .putFloat(SPEED, speed)
            .putFloat(BEARING, bearing)
            .applyHookSettings()
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
        get() = pref.getBoolean(HOOKED_SYSTEM, true) // must match HookSettings.isHookedSystem default
        set(value) { pref.edit().putBoolean(HOOKED_SYSTEM,value).applyHookSettings() }

    var isRandomPosition :Boolean
        get() = pref.getBoolean(RANDOM_POSITION, false)
        set(value) { pref.edit().putBoolean(RANDOM_POSITION, value).applyHookSettings() }

    var accuracy : String?
        get() = pref.getString(ACCURACY_SETTING,"10")
        set(value) { pref.edit().putString(ACCURACY_SETTING,value).applyHookSettings() }

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

    var keepScreenOnDuringRoute: Boolean
        get() = pref.getBoolean(KEEP_SCREEN_ON_DURING_ROUTE, false)
        set(value) = pref.edit().putBoolean(KEEP_SCREEN_ON_DURING_ROUTE, value).apply()

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
            .putLong(LocationUpdateInterval.KEY, LocationUpdateInterval.DEFAULT_MS)
            .applyHookSettings()
    }


}
