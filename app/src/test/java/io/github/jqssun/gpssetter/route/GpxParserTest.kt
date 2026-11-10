package io.github.jqssun.gpssetter.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxParserTest {
    @Test fun parsesTrkptsWithElevation() {
        val gpx = """
            <gpx><trk><trkseg>
              <trkpt lat="17.4" lon="78.5"><ele>500.0</ele></trkpt>
              <trkpt lat="17.41" lon="78.51"></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()
        val pts = parseGpx(gpx)
        assertEquals(2, pts.size)
        assertEquals(17.4, pts[0].lat, 1e-9)
        assertEquals(78.5, pts[0].lng, 1e-9)
        assertEquals(500.0, pts[0].altitude, 1e-9)
        assertEquals(0.0, pts[1].altitude, 1e-9) // missing ele -> 0
    }

    @Test fun parsesSelfClosingAndRtept() {
        val gpx = """<gpx><rte><rtept lat="1.0" lon="2.0"/><rtept lon="4.0" lat="3.0"/></rte></gpx>"""
        val pts = parseGpx(gpx)
        assertEquals(2, pts.size)
        assertEquals(1.0, pts[0].lat, 1e-9)
        assertEquals(4.0, pts[1].lng, 1e-9) // attribute order lon-before-lat still works
    }

    @Test fun toGpxRoundTrips() {
        val pts = listOf(RoutePoint(17.48372, 78.41145, 540.0), RoutePoint(17.4845, 78.4123, 545.5), RoutePoint(-33.9, 151.2))
        val gpx = toGpx("Home <-> \"Work\" & back", pts)
        assertEquals(pts, parseGpx(gpx))
        assertEquals("Home <-> \"Work\" & back", gpxName(gpx)) // escaped on the way out, unescaped on the way in
    }

    @Test fun nameIsNullWithoutOne() {
        assertEquals(null, gpxName("""<gpx><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>"""))
    }

    @Test fun emptyOnGarbage() {
        assertTrue(parseGpx("not xml at all").isEmpty())
    }
}
