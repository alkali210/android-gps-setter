package io.github.jqssun.gpssetter.xposed

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Metres -> degrees for the "random position" jitter. Longitude must be scaled by cos(latitude),
// otherwise the east-west jitter grows wrong away from the equator. Pure so it's unit-tested.
object GeoOffset {
    private const val EARTH_RADIUS_M = 6378137.0

    fun latOffsetDegrees(metersNorth: Double): Double =
        Math.toDegrees(metersNorth / EARTH_RADIUS_M)

    fun lngOffsetDegrees(metersEast: Double, atLatDegrees: Double): Double =
        Math.toDegrees(metersEast / (EARTH_RADIUS_M * cos(Math.toRadians(atLatDegrees))))

    // A uniform random point within [radiusM] of the centre, as (dLat, dLng) degrees.
    // u and v are uniform 0..1 (caller's RNG); sqrt(u) keeps the density even across the disc.
    fun jitter(atLatDegrees: Double, radiusM: Double, u: Double, v: Double): Pair<Double, Double> {
        if (radiusM <= 0.0) return 0.0 to 0.0
        val d = radiusM * sqrt(u)
        val a = 2 * PI * v
        return latOffsetDegrees(d * cos(a)) to lngOffsetDegrees(d * sin(a), atLatDegrees)
    }
}
