package io.github.jqssun.gpssetter.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RouteMathTest {
    @Test fun haversineKnownDistance() {
        // ~111.2 km per degree of latitude
        val d = RouteMath.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111195.0, d, 200.0)
    }

    @Test fun bearingCardinal() {
        assertEquals(0.0, RouteMath.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.5)    // north
        assertEquals(90.0, RouteMath.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.5)   // east
    }

    @Test fun interpolatesMidSegment() {
        val pts = listOf(RoutePoint(0.0, 0.0, 100.0), RoutePoint(0.0, 1.0, 200.0))
        val len = RouteMath.pathLength(pts)
        val mid = RouteMath.pointAtDistance(pts, len / 2)!!
        assertEquals(0.5, mid.lng, 1e-6)
        assertEquals(150.0, mid.altitude, 1.0)   // altitude interpolated
        assertEquals(90.0, mid.bearing.toDouble(), 0.5) // heading east
    }

    @Test fun clampsEnds() {
        val pts = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0))
        assertEquals(0.0, RouteMath.pointAtDistance(pts, -5.0)!!.lng, 1e-9) // before start
        assertEquals(1.0, RouteMath.pointAtDistance(pts, 1e9)!!.lng, 1e-9)  // past end
        assertNull(RouteMath.pointAtDistance(emptyList(), 0.0))
    }

    @Test fun advanceHandlesEachMode() {
        // mid-route: just moves
        assertEquals(RouteMath.Progress(60.0, 1, false), RouteMath.advance(50.0, 1, 10.0, 100.0, RouteMath.ONCE))
        // once: stops at the end
        assertEquals(RouteMath.Progress(100.0, 1, true), RouteMath.advance(95.0, 1, 10.0, 100.0, RouteMath.ONCE))
        // loop: wraps to the start
        assertEquals(RouteMath.Progress(5.0, 1, false), RouteMath.advance(95.0, 1, 10.0, 100.0, RouteMath.LOOP))
        // ping-pong: bounces off the end, then off the start
        assertEquals(RouteMath.Progress(95.0, -1, false), RouteMath.advance(95.0, 1, 10.0, 100.0, RouteMath.PING_PONG))
        assertEquals(RouteMath.Progress(5.0, 1, false), RouteMath.advance(5.0, -1, 10.0, 100.0, RouteMath.PING_PONG))
        assertEquals(RouteMath.Progress(30.0, -1, false), RouteMath.advance(40.0, -1, 10.0, 100.0, RouteMath.PING_PONG))
    }

    @Test fun speedFactorStaysInBandAndMoves() {
        assertEquals(1.0, RouteMath.speedFactor(0.0, 12.0, 1.0), 1e-12)
        val f = (0..600).map { RouteMath.speedFactor(0.12, it.toDouble(), 0.7) }
        assertTrue(f.all { it in 0.88..1.12 })
        assertTrue(f.max() - f.min() > 0.1) // it does vary
    }

    @Test fun approachLimitsTheStep() {
        assertEquals(4.0, RouteMath.approach(0.0, 10.0, 4.0), 1e-12)   // speeding up
        assertEquals(6.0, RouteMath.approach(10.0, 0.0, 4.0), 1e-12)   // braking
        assertEquals(9.0, RouteMath.approach(8.0, 9.0, 4.0), 1e-12)    // close enough: lands on target
    }

    @Test fun nextStopLooksAheadInTheTravelDirection() {
        val stops = doubleArrayOf(300.0, 700.0, 1000.0)
        assertEquals(300.0, RouteMath.nextStop(stops, 120.0, 1, 1000.0), 1e-9)
        assertEquals(700.0, RouteMath.nextStop(stops, 300.0, 1, 1000.0), 1e-9)   // on a stop: the one after
        assertEquals(300.0, RouteMath.nextStop(stops, 650.0, -1, 1000.0), 1e-9)  // coming back
        assertEquals(0.0, RouteMath.nextStop(stops, 200.0, -1, 1000.0), 1e-9)    // none behind: the start
        assertEquals(1000.0, RouteMath.nextStop(doubleArrayOf(), 10.0, 1, 1000.0), 1e-9)
    }

    // metres -> a RoutePoint near the equator, where a degree is ~111.3 km both ways
    private fun m(north: Double, east: Double) = RoutePoint(north / 111319.49, east / 111319.49)

    @Test fun findsASharpCorner() {
        val l = listOf(m(0.0, 0.0), m(0.0, 100.0), m(100.0, 100.0)) // east, then a left turn north
        val c = RouteMath.corners(l)
        assertEquals(1, c.size)
        assertEquals(100.0, c[0].at, 0.5)
        assertEquals(90.0, c[0].turnDeg, 1.0)
    }

    @Test fun straightRouteHasNoCorners() {
        val line = (0..10).map { m(0.0, it * 10.0) } + m(0.0, 100.0) // a duplicate end point too
        assertTrue(RouteMath.corners(line).isEmpty())
    }

    // a tight 90° curve drawn as 10 segments of 9° each still counts, and only once
    @Test fun findsACurveMadeOfShortSegments() {
        val east = (0..10).map { m(0.0, it * 5.0) }
        val arc = (1..10).map { val t = Math.toRadians(it * 9.0); m(10 - 10 * cos(t), 50 + 10 * sin(t)) }
        val north = (1..10).map { m(10.0 + it * 5.0, 60.0) }
        val c = RouteMath.corners(east + arc + north)
        assertEquals(1, c.size)
        assertTrue(c[0].turnDeg in 80.0..95.0)
        assertEquals(58.0, c[0].at, 10.0) // around the middle of the curve
    }

    @Test fun sharperTurnsAreTakenSlower() {
        assertEquals(Double.POSITIVE_INFINITY, RouteMath.cornerCapMps(10.0), 0.0)
        assertEquals(20 / 3.6, RouteMath.cornerCapMps(90.0), 1e-9)
        assertTrue(RouteMath.cornerCapMps(45.0) > RouteMath.cornerCapMps(120.0))
        assertTrue(RouteMath.cornerCapMps(120.0) > RouteMath.cornerCapMps(180.0))
    }

    @Test fun cornerLimitBrakesAheadOfTheTurn() {
        val corners = listOf(RouteMath.Corner(100.0, 90.0))
        val cap = 20 / 3.6
        assertEquals(cap, RouteMath.cornerLimit(corners, 100.0, 1, 2.0), 1e-9)              // in the turn
        assertEquals(sqrt(cap * cap + 2 * 2.0 * 50), RouteMath.cornerLimit(corners, 50.0, 1, 2.0), 1e-9) // 50 m out
        assertEquals(cap, RouteMath.cornerLimit(corners, 104.0, 1, 2.0), 1e-9)              // just past: still turning
        assertEquals(Double.POSITIVE_INFINITY, RouteMath.cornerLimit(corners, 120.0, 1, 2.0), 0.0) // behind us
        // coming back on a ping-pong, the same corner is ahead again
        assertEquals(RouteMath.cornerLimit(corners, 50.0, 1, 2.0), RouteMath.cornerLimit(corners, 150.0, -1, 2.0), 1e-9)
    }

    @Test fun sketchFitsTheBoxAndKeepsTheShape() {
        // a straight east-west line: full width, vertically centred
        val line = (0..200).map { m(0.0, it * 10.0) }
        val s = RouteMath.sketch(line, max = 64)
        assertTrue(s.size / 2 <= 65)
        assertTrue(s.all { it in 0f..1f })
        assertEquals(0f, s[0], 1e-6f)                // starts at the left
        assertEquals(1f, s[s.size - 2], 1e-6f)       // ends at the right (last point always kept)
        assertTrue((1 until s.size step 2).all { kotlin.math.abs(s[it] - 0.5f) < 1e-6f })
        assertEquals(0, RouteMath.sketch(listOf(m(0.0, 0.0))).size)
    }

    @Test fun compassNamesTheDirection() {
        assertEquals("000° N", RouteMath.compass(0f))
        assertEquals("045° NE", RouteMath.compass(45f))
        assertEquals("200° S", RouteMath.compass(200f))
        assertEquals("000° N", RouteMath.compass(359.6f))
        assertNull(RouteMath.compass(-1f))
    }
}
