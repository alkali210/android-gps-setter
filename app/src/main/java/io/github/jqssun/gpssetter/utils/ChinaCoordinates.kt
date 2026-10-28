package io.github.jqssun.gpssetter.utils

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Conventional WGS-84 / GCJ-02 math, with latitude first in arguments and results.
 *
 * Applicability uses the common inclusive rectangle 0.8293..55.8271 latitude and
 * 72.004..137.8347 longitude. This is an approximation, NOT a country or political
 * boundary: it includes parts of neighboring regions, Taiwan, Hong Kong and Macau.
 * It does not establish whether a particular map provider offsets those locations.
 * Accurate regional exclusions require boundary data; coarse exclusion rectangles
 * would also exclude nearby mainland locations.
 *
 * Both directions preserve inputs outside that rectangle. Consequently, a forward
 * offset crossing its edge cannot roundtrip through the inverse: the outside point
 * is indistinguishable from an unshifted coordinate. Away from that discontinuity,
 * the iterative inverse targets sub-meter numerical accuracy, not survey accuracy
 * or a guarantee about a provider's actual map alignment.
 */
object ChinaCoordinates {
    private const val SEMI_MAJOR_AXIS = 6378245.0
    private const val ECCENTRICITY_SQUARED = 0.00669342162296594323
    private const val INVERSE_TOLERANCE_DEGREES = 1e-9
    private const val MAX_INVERSE_ITERATIONS = 20

    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (!isApplicable(lat, lng)) return lat to lng
        return withOffset(lat, lng) { deltaLat, deltaLng ->
            lat + deltaLat to lng + deltaLng
        }
    }

    fun gcj02ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        if (!isApplicable(lat, lng)) return lat to lng
        var estimateLat = lat
        var estimateLng = lng
        repeat(MAX_INVERSE_ITERATIONS) {
            // Do not gate intermediate estimates: the rectangle is discontinuous.
            withOffset(estimateLat, estimateLng) { deltaLat, deltaLng ->
                val latError = estimateLat + deltaLat - lat
                val lngError = estimateLng + deltaLng - lng
                estimateLat -= latError
                estimateLng -= lngError
                if (abs(latError) <= INVERSE_TOLERANCE_DEGREES &&
                    abs(lngError) <= INVERSE_TOLERANCE_DEGREES
                ) {
                    return estimateLat to estimateLng
                }
            }
        }
        return estimateLat to estimateLng
    }

    private fun isApplicable(lat: Double, lng: Double): Boolean =
        lat in 0.8293..55.8271 && lng in 72.004..137.8347

    // Inlining lets both directions share the math without allocating pairs or
    // mutable scratch storage on every inverse iteration.
    private inline fun <T> withOffset(
        lat: Double,
        lng: Double,
        result: (Double, Double) -> T
    ): T {
        val x = lng - 105.0
        val y = lat - 35.0
        val rootX = sqrt(abs(x))
        val common = (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        var deltaLat = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * rootX
        deltaLat += common
        deltaLat += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        deltaLat += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        var deltaLng = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * rootX
        deltaLng += common
        deltaLng += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        deltaLng += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0

        val radians = lat / 180.0 * PI
        val sinLat = sin(radians)
        val magic = 1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat
        val rootMagic = sqrt(magic)
        deltaLat = deltaLat * 180.0 /
            (SEMI_MAJOR_AXIS * (1.0 - ECCENTRICITY_SQUARED) / (magic * rootMagic) * PI)
        deltaLng = deltaLng * 180.0 / (SEMI_MAJOR_AXIS / rootMagic * cos(radians) * PI)
        return result(deltaLat, deltaLng)
    }
}
