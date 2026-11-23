package io.github.jqssun.gpssetter.xposed

import java.util.Random
import kotlin.math.cos

// Metres -> degrees for the "random position" jitter. Longitude must be scaled by cos(latitude),
// otherwise the east-west jitter grows wrong away from the equator. Pure so it's unit-tested.
object GeoOffset {
    private const val EARTH_RADIUS_M = 6378137.0

    fun latOffsetDegrees(metersNorth: Double): Double =
        Math.toDegrees(metersNorth / EARTH_RADIUS_M)

    fun lngOffsetDegrees(metersEast: Double, atLatDegrees: Double): Double =
        Math.toDegrees(metersEast / (EARTH_RADIUS_M * cos(Math.toRadians(atLatDegrees))))
}

internal class RandomPosition(private val random: Random = Random()) {
    private var sampledAtMs: Long? = null
    private var northMeters = 0.0
    private var eastMeters = 0.0

    var nextSampleMs = Long.MAX_VALUE
        private set

    fun apply(settings: HookSettings, nowMs: Long, intervalMs: Long): HookSettings {
        if (!settings.isStarted || !settings.isRandomPosition) {
            sampledAtMs = null
            nextSampleMs = Long.MAX_VALUE
            return settings
        }

        val sampledAt = sampledAtMs
        if (sampledAt == null || nowMs - sampledAt >= intervalMs) {
            sampledAtMs = nowMs
            northMeters = (random.nextInt(51) - 25).toDouble()
            eastMeters = (random.nextInt(51) - 25).toDouble()
        }
        nextSampleMs = (sampledAtMs ?: nowMs) + intervalMs
        return settings.copy(
            latitude = settings.latitude + GeoOffset.latOffsetDegrees(northMeters),
            longitude = settings.longitude + GeoOffset.lngOffsetDegrees(eastMeters, settings.latitude),
        )
    }
}
