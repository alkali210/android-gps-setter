package io.github.jqssun.gpssetter.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChinaCoordinatesTest {
    // Independent fixture outputs from coordtransform 2.1.2's wgs84togcj02,
    // https://unpkg.com/coordtransform@2.1.2/index.js (which takes longitude first).
    // These are numerical reference values, not surveyed map-provider positions.
    private val beijingWgs = 39.908823 to 116.397470
    private val beijingGcj = 39.91022649807321 to 116.4037135824225
    private val shanghaiWgs = 31.2304 to 121.4737
    private val shanghaiGcj = 31.22845773757727 to 121.47822305927693

    @Test fun forwardMatchesIndependentBeijingAndShanghaiFixtures() {
        for ((wgs, gcj) in listOf(beijingWgs to beijingGcj, shanghaiWgs to shanghaiGcj)) {
            assertCoordinateEquals(gcj, ChinaCoordinates.wgs84ToGcj02(wgs.first, wgs.second), 1e-10)
        }
    }

    @Test fun inverseMatchesIndependentFixturesWithinCentimeters() {
        // Testing known GCJ inputs, rather than just our own forward output, catches
        // reversed axes, the wrong direction, double offsets and one-step inverses.
        for ((wgs, gcj) in listOf(beijingWgs to beijingGcj, shanghaiWgs to shanghaiGcj)) {
            assertCoordinateEquals(wgs, ChinaCoordinates.gcj02ToWgs84(gcj.first, gcj.second), 1e-7)
        }
    }

    @Test fun outsideApplicabilityIsExactlyUnchangedInBothDirections() {
        val points = listOf(
            0.0 to 0.0,
            51.5074 to -0.1278,
            40.7128 to -74.0060,
            35.6762 to 139.6503,
            -33.8688 to 151.2093,
            0.829299 to 110.0,
            55.827101 to 110.0,
            30.0 to 72.003999,
            30.0 to 137.834701
        )
        for (point in points) {
            val forward = ChinaCoordinates.wgs84ToGcj02(point.first, point.second)
            val inverse = ChinaCoordinates.gcj02ToWgs84(point.first, point.second)
            assertEquals(point, forward)
            assertEquals(point, inverse)
        }
    }

    @Test fun variedInteriorAndInwardBoundaryPointsRoundtrip() {
        val points = listOf(
            beijingWgs,
            shanghaiWgs,
            23.1291 to 113.2644,
            22.5431 to 114.0579,
            30.5728 to 104.0668,
            43.8256 to 87.6168,
            29.6520 to 91.1721,
            45.8038 to 126.5349,
            30.0 to 105.0, // The square-root term's cusp.
            30.0 to 104.999999,
            30.0 to 105.000001,
            30.0 to 72.004, // Exact western edge; its offset points inward.
            0.8293 to 72.004, // Exact southwest corner, also shifted inward.
            0.85 to 110.0,
            55.8 to 110.0,
            30.0 to 137.8
        )
        for (wgs in points) {
            val gcj = ChinaCoordinates.wgs84ToGcj02(wgs.first, wgs.second)
            assertNotEquals(wgs, gcj)
            assertCoordinateEquals(wgs, ChinaCoordinates.gcj02ToWgs84(gcj.first, gcj.second), 1e-7)
        }
    }

    @Test fun applicabilityBoundsAreInclusive() {
        for (point in listOf(0.8293 to 110.0, 55.8271 to 110.0, 30.0 to 72.004, 30.0 to 137.8347)) {
            assertNotEquals(point, ChinaCoordinates.wgs84ToGcj02(point.first, point.second))
            assertNotEquals(point, ChinaCoordinates.gcj02ToWgs84(point.first, point.second))
        }
    }

    @Test fun outwardBoundaryShiftRemainsOutsideForTheInverse() {
        // Outside identity necessarily wins over roundtripping across the abrupt
        // approximate boundary: there is no datum flag to disambiguate the input.
        val gcj = ChinaCoordinates.wgs84ToGcj02(30.0, 137.8347)
        assertTrue(gcj.second > 137.8347)
        assertEquals(gcj, ChinaCoordinates.gcj02ToWgs84(gcj.first, gcj.second))
    }

    private fun assertCoordinateEquals(expected: Pair<Double, Double>, actual: Pair<Double, Double>, tolerance: Double) {
        assertEquals("latitude for $expected", expected.first, actual.first, tolerance)
        assertEquals("longitude for $expected", expected.second, actual.second, tolerance)
    }
}
