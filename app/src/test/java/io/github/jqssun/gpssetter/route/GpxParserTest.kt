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

    @Test fun emptyOnGarbage() {
        assertTrue(parseGpx("not xml at all").isEmpty())
    }

    @Test fun parsesUtf8BomPrefixedGpx() {
        val withProlog = "\uFEFF<?xml version=\"1.0\" encoding=\"UTF-8\"?><gpx><trk><trkseg>" +
            "<trkpt lat=\"1.0\" lon=\"2.0\"/><trkpt lat=\"3.0\" lon=\"4.0\"/></trkseg></trk></gpx>"
        val withoutProlog = "\uFEFF<gpx><rte><rtept lat=\"5.0\" lon=\"6.0\"/><rtept lat=\"7.0\" lon=\"8.0\"/></rte></gpx>"
        assertEquals(listOf(RoutePoint(1.0, 2.0), RoutePoint(3.0, 4.0)), parseGpx(withProlog))
        assertEquals(listOf(RoutePoint(5.0, 6.0), RoutePoint(7.0, 8.0)), parseGpx(withoutProlog))
    }
}
