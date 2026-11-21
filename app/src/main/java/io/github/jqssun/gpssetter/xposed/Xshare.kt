package io.github.jqssun.gpssetter.xposed

import android.content.SharedPreferences
import java.io.InputStream

// One immutable fix/configuration for the preference and file transports.
internal data class HookSettings(
    val isStarted: Boolean = false,
    val latitude: Double = 45.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = -1f,
    val accuracy: Float = 10f,
    val isRandomPosition: Boolean = false,
    val isHookedSystem: Boolean = true,
    val locationUpdateIntervalMs: Long = LocationUpdateInterval.DEFAULT_MS,
    val revision: Long = 0L,
) {
    fun publish(prefs: SharedPreferences) {
        val editor = prefs.edit().putBoolean(START, isStarted)
            .putLong(LATITUDE_D, latitude.toRawBits()).putLong(LONGITUDE_D, longitude.toRawBits())
            .putLong(ALTITUDE, altitude.toRawBits()).putFloat(SPEED, speed).putFloat(BEARING, bearing)
            .putString(ACCURACY_SETTING, accuracy.toString()).putBoolean(RANDOM_POSITION, isRandomPosition)
            .putBoolean(HOOKED_SYSTEM, isHookedSystem).putLong(LocationUpdateInterval.KEY, locationUpdateIntervalMs)
        if (revision > 0L) editor.putLong(REVISION, revision) else editor.remove(REVISION)
        editor.apply()
    }

    fun encode(): ByteArray = (
        "$START=$isStarted\n$LATITUDE_D=${latitude.toRawBits()}\n$LONGITUDE_D=${longitude.toRawBits()}\n" +
            "$ALTITUDE=${altitude.toRawBits()}\n$SPEED=$speed\n$BEARING=$bearing\n$ACCURACY_SETTING=$accuracy\n" +
            "$RANDOM_POSITION=$isRandomPosition\n$HOOKED_SYSTEM=$isHookedSystem\n" +
            "${LocationUpdateInterval.KEY}=$locationUpdateIntervalMs\n" +
            (if (revision > 0L) "$REVISION=$revision\n" else "") + END
        ).toByteArray(Charsets.US_ASCII)

    companion object {
        const val START = "start"
        const val LATITUDE_D = "latitude_d"
        const val LONGITUDE_D = "longitude_d"
        const val ALTITUDE = "altitude_d"
        const val SPEED = "speed"
        const val BEARING = "bearing"
        const val ACCURACY_SETTING = "accuracy_level"
        const val RANDOM_POSITION = "random_position"
        const val HOOKED_SYSTEM = "system_hooked"
        const val REVISION = "revision"
        private const val END = "end=1\n"
        private val trailer = "\n$END".toByteArray(Charsets.US_ASCII)
        const val BUFFER_SIZE = 1024

        fun fromLocalPreferences(
            prefs: SharedPreferences,
            defaultLat: Double,
            defaultLng: Double,
            revision: Long,
        ): HookSettings = fromUnversionedPreferences(prefs.all, defaultLat, defaultLng, revision)

        fun fromPushedPreferences(prefs: SharedPreferences, revisionAfter: Long): HookSettings? {
            if (revisionAfter >= 0L && prefs.getLong(REVISION, 0L) <= revisionAfter) return null
            // Take one snapshot; individual getters could straddle two route ticks.
            val p = prefs.all
            if (!p.containsKey(REVISION)) {
                return if (revisionAfter < 0L) fromUnversionedPreferences(p, 45.0, 0.0, 0L) else null
            }
            val revision = (p[REVISION] as? Long)?.takeIf { it > 0L && it > revisionAfter } ?: return null
            // Versioned publications must supply the whole fix, not legacy defaults.
            return HookSettings(
                p[START] as? Boolean ?: return null,
                Double.fromBits(p[LATITUDE_D] as? Long ?: return null),
                Double.fromBits(p[LONGITUDE_D] as? Long ?: return null),
                Double.fromBits(p[ALTITUDE] as? Long ?: return null),
                p[SPEED] as? Float ?: return null, p[BEARING] as? Float ?: return null,
                (p[ACCURACY_SETTING] as? String)?.toFloatOrNull()?.takeIf { it > 0f } ?: return null,
                p[RANDOM_POSITION] as? Boolean ?: return null, p[HOOKED_SYSTEM] as? Boolean ?: return null,
                LocationUpdateInterval.normalize(p[LocationUpdateInterval.KEY] as? Long ?: return null),
                revision,
            )
        }

        private fun fromUnversionedPreferences(
            p: Map<String, *>,
            defaultLat: Double,
            defaultLng: Double,
            revision: Long,
        ): HookSettings {
            fun coord(key: String, legacy: String, default: Double) =
                (p[key] as? Long)?.let { Double.fromBits(it) } ?: (p[legacy] as? Float)?.toDouble() ?: default
            return HookSettings(
                p[START] as? Boolean ?: false,
                coord(LATITUDE_D, "latitude", defaultLat), coord(LONGITUDE_D, "longitude", defaultLng),
                (p[ALTITUDE] as? Long)?.let { Double.fromBits(it) } ?: 0.0,
                p[SPEED] as? Float ?: 0f, p[BEARING] as? Float ?: -1f,
                (p[ACCURACY_SETTING] as? String)?.toFloatOrNull()?.takeIf { it > 0f } ?: 10f,
                p[RANDOM_POSITION] as? Boolean ?: false, p[HOOKED_SYSTEM] as? Boolean ?: true,
                LocationUpdateInterval.normalize(p[LocationUpdateInterval.KEY] as? Long ?: LocationUpdateInterval.DEFAULT_MS),
                revision,
            )
        }

        fun decode(bytes: ByteArray, n: Int = bytes.size): HookSettings? {
            if (n < trailer.size || n >= BUFFER_SIZE || trailer.indices.any { bytes[n - trailer.size + it] != trailer[it] }) return null
            // Only ASCII keys and numbers/booleans, no property escapes.
            val text = String(bytes, 0, n - trailer.size, Charsets.US_ASCII)
            val p = HashMap<String, String>(16)
            for (line in text.lineSequence()) {
                val equals = line.indexOf('=')
                if (equals <= 0 || p.put(line.substring(0, equals), line.substring(equals + 1)) != null) return null
            }
            val revision = if (p.containsKey(REVISION)) {
                p[REVISION]?.toLongOrNull()?.takeIf { it > 0L } ?: return null
            } else 0L
            return HookSettings(
                p[START]?.toBooleanStrictOrNull() ?: return null,
                Double.fromBits(p[LATITUDE_D]?.toLongOrNull() ?: return null),
                Double.fromBits(p[LONGITUDE_D]?.toLongOrNull() ?: return null),
                Double.fromBits(p[ALTITUDE]?.toLongOrNull() ?: return null),
                p[SPEED]?.toFloatOrNull() ?: return null, p[BEARING]?.toFloatOrNull() ?: return null,
                p[ACCURACY_SETTING]?.toFloatOrNull()?.takeIf { it > 0f } ?: return null,
                p[RANDOM_POSITION]?.toBooleanStrictOrNull() ?: return null,
                p[HOOKED_SYSTEM]?.toBooleanStrictOrNull() ?: return null,
                LocationUpdateInterval.normalize(p[LocationUpdateInterval.KEY]?.toLongOrNull() ?: return null),
                revision,
            )
        }
    }
}

// Vector may not push preference updates to system_server (JingMatrix/Vector#1083).
// Only that process gets a file opener; scoped apps continue to use pushed preferences.
internal class Xshare(private val prefs: SharedPreferences, open: (() -> InputStream)? = null) {
    companion object {
        const val GROUP = "settings"
        const val FILE = "settings.properties"
        const val MAX_CONTROL_INTERVAL_MS = 1000L
    }

    private val file = open?.let(::RemoteFile)
    private var latest = readPushed(-1L)
    val initialSettings: HookSettings? get() = latest

    private fun readPushed(revisionAfter: Long): HookSettings? = try {
        HookSettings.fromPushedPreferences(prefs, revisionAfter)
    } catch (_: RuntimeException) {
        // A temporarily unavailable preference service must not hide a complete file.
        null
    }

    private class RemoteFile(val open: () -> InputStream) {
        private var buffer = ByteArray(HookSettings.BUFFER_SIZE)
        private var previous = ByteArray(HookSettings.BUFFER_SIZE)
        private var length = 0
        private var lastGood: HookSettings? = null

        fun read(): HookSettings? {
            try {
                // Like the single write on the app side, one regular-file read cannot
                // concatenate pieces of different generations across syscalls.
                val n = open().use { it.read(buffer) }
                if (n > 0 && (n != length || (0 until n).any { buffer[it] != previous[it] })) {
                    HookSettings.decode(buffer, n)?.let {
                        lastGood = it
                        length = n
                        val tmp = buffer
                        buffer = previous
                        previous = tmp
                    }
                }
            } catch (_: Exception) {
                // Missing before first sync, or unavailable while the framework reconnects.
            }
            return lastGood
        }
    }
    @Volatile var nextReadMs = 0L
        private set

    // Called under LocationHook's update lock. No second file-specific poll timer.
    fun refresh(nowMs: Long, maxIntervalMs: Long = Long.MAX_VALUE): HookSettings? {
        if (nowMs < nextReadMs) return null
        // Even failures must not spin the worker. Control polling is independent of fix delivery.
        val limit = minOf(MAX_CONTROL_INTERVAL_MS, maxIntervalMs)
        nextReadMs = nowMs + limit
        val previous = latest
        val pulled = file?.read()
        // Preserve file priority for two legacy transports. A versioned snapshot can only
        // be superseded by a complete snapshot with a strictly larger revision.
        val fallback = if (pulled != null &&
            (previous == null || previous.revision == 0L || pulled.revision > previous.revision)
        ) pulled else previous
        val revisionAfter = fallback?.revision?.takeIf { it > 0L || pulled != null } ?: -1L
        val current = readPushed(revisionAfter) ?: fallback ?: return null
        latest = current
        nextReadMs = nowMs + minOf(current.locationUpdateIntervalMs, limit)
        return current
    }
}
