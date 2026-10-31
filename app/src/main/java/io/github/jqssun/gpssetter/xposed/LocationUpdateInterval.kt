package io.github.jqssun.gpssetter.xposed

object LocationUpdateInterval {
    const val KEY = "location_update_interval_ms"
    const val DEFAULT_MS = 1000L
    const val MIN_MS = 100L
    const val MAX_MS = 10000L

    fun normalize(value: Long): Long = value.coerceIn(MIN_MS, MAX_MS)
}
