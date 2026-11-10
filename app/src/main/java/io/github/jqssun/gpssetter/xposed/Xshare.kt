package io.github.jqssun.gpssetter.xposed
import android.content.SharedPreferences
import android.os.SystemClock
import java.io.InputStream
import java.util.Properties

// Hook-side view of the settings the app mirrors into the framework's remote preferences (see PrefManager).
//
// [open] is set in system_server only. Vector 2.2 never pushes remote-preference changes there: at boot
// its getModulesForSystemServer() gives system_server a module service that the daemon's cache later
// replaces, so updateRemotePreferences notifies nobody in system_server and the hook kept its boot-time
// values (start/stop needed a reboot). The app also writes the same values to a remote file [FILE];
// remote files are read fresh from the daemon on every call, so system_server re-reads that, at most
// once a second, and falls back to [prefs] for anything the file doesn't have.
// ponytail: drop [open] and the app-side file once Vector pushes to system_server again
// (https://github.com/JingMatrix/Vector/issues/1083).
class Xshare(private val prefs: SharedPreferences, private val open: (() -> InputStream)? = null) {

    companion object {
        const val GROUP = "settings"
        const val FILE = "settings.properties"
        const val END = "end" // last line of the file; a read without it caught a write half-done

        // The app's settings as "key=value" lines; null unless the whole file was written.
        fun parse(text: InputStream): Properties? =
            Properties().apply { load(text) }.takeIf { it.getProperty(END) == "1" }
    }

    @Volatile private var snapshot: Properties? = null
    private var lastRead = 0L

    // system_server: pick up changes the framework didn't push. No-op elsewhere.
    fun refresh() {
        val open = open ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRead < 1000) return
        lastRead = now
        // keep the last good snapshot if the file is missing (app never synced) or mid-write
        runCatching { open().use { parse(it) } }.getOrNull()?.let { snapshot = it }
    }

    private fun s(key: String): String? = snapshot?.getProperty(key)

    val isStarted : Boolean
    get() = s("start")?.toBooleanStrictOrNull() ?: prefs.getBoolean("start", false)

    // Double bits written by PrefManager; only ever Long under these keys, so never a cross-type read
    val getLat: Double
    get() = Double.fromBits(s("latitude_d")?.toLongOrNull() ?: prefs.getLong("latitude_d", (45.0).toRawBits()))

    val getLng : Double
    get() = Double.fromBits(s("longitude_d")?.toLongOrNull() ?: prefs.getLong("longitude_d", (0.0).toRawBits()))

    // read once, as system_server boots; the boot-time value is the one that matters
    val isHookedSystem : Boolean
    get() = prefs.getBoolean("system_hooked", true)

    // metres; 0 = report the exact point
    val jitterMeters: Float
    get() = s("jitter_m")?.toFloatOrNull() ?: prefs.getFloat("jitter_m", 0f)

    val accuracy : String?
    get() = s("accuracy_level") ?: prefs.getString("accuracy_level","10")

    // motion for route playback (defaults mean "static": no speed, bearing from the real fix)
    val altitude: Double
    get() = Double.fromBits(s("altitude_d")?.toLongOrNull() ?: prefs.getLong("altitude_d", (0.0).toRawBits()))

    val speed: Float
    get() = s("speed")?.toFloatOrNull() ?: prefs.getFloat("speed", 0f)

    val bearing: Float
    get() = s("bearing")?.toFloatOrNull() ?: prefs.getFloat("bearing", -1f)

}
