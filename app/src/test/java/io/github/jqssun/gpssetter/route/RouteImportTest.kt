package io.github.jqssun.gpssetter.route

import io.github.jqssun.gpssetter.room.SavedRoute
import org.junit.Assert.*
import org.junit.Test

class RouteImportTest {
    @Test fun featureCollectionJoinsLinesInOrderAndKeepsElevation() {
        val json = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"name":"Not a track"},"geometry":{"type":"Point","coordinates":[1,2]}},
            {"type":"Feature","properties":{"name":"Harbor & hill"},"geometry":{"type":"MultiLineString",
                "coordinates":[[[-122,37,1],[-121,38,14]],[[-120,39,21],[-119,40]]]}},
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[-118,41],[-117,42,33]]}}
        ]}"""
        val route = importRoute(json)!!
        assertEquals("Harbor & hill", route.name)
        assertEquals(listOf(RoutePoint(37.0,-122.0,1.0), RoutePoint(38.0,-121.0,14.0),
            RoutePoint(39.0,-120.0,21.0), RoutePoint(40.0,-119.0), RoutePoint(41.0,-118.0),
            RoutePoint(42.0,-117.0,33.0)), route.points)
        assertEquals(route, importRoute(route.toGpx(route.name!!)))
        assertNotNull(importRoute("""{"type":"LineString","coordinates":[[0,1],[2,3]]}"""))
        assertNull(importRoute("""{"type":"LineString","coordinates":[[0,91],[2,3]]}"""))
        assertNull(importRoute("""{"type":"Point","coordinates":[0,1]}"""))
    }

    @Test fun typedWaypointsSurviveRoundTripWithoutChangingPlaybackPath() {
        val points = listOf(RoutePoint(5.0, 6.0, 123.0), RoutePoint(5.4, 6.5, 155.25), RoutePoint(7.0, 8.0, 99.0))
        val stops = listOf(RouteStop(points.first(), "Pier <A>"), RouteStop(RoutePoint(5.3, 6.4, 150.0), "Tea & cake"),
            RouteStop(RoutePoint(5.6, 6.7), null), RouteStop(points.last(), "Finish"))
        val route = RouteDocument("Harbor & <hill>", points, stops)
        val file = route.toGpx(route.name!!)
        assertEquals(route, importRoute(file))
        assertEquals(points, parseGpx(file))
        assertNull(importRoute("""<!DOCTYPE gpx SYSTEM "file:///not-a-route"><gpx/>"""))
    }

    @Test fun exportedGpxUsesDecimalLexemesAndPreservesPrecision() {
        val points = listOf(RoutePoint(0.00012345678901234567, -0.00000042, 1.23456789e14),
            RoutePoint(-0.00005, 0.00001, -0.00000987))
        val route = RouteDocument("Decimals", points, listOf(RouteStop(points.first(), "Start")))
        val gpx = route.toGpx("Decimals")
        val values = Regex("""(?:lat|lon)="([^"]+)"|<ele>([^<]+)</ele>""").findAll(gpx).map {
            it.groupValues[1].ifEmpty { it.groupValues[2] }
        }.toList()
        assertEquals(9, values.size)
        assertTrue(values.all { it.matches(Regex("""[+-]?\d+(?:\.\d+)?""")) })
        assertEquals(route, importRoute(gpx))
    }

    @Test fun exportedTrackPointsAndStopsUseValidGpxAntimeridianLongitude() {
        val doc = importRoute("""{"type":"LineString","coordinates":[[180,2],[179.999999,4]]}""")!!
        val stop = RouteStop(doc.points.first(), "East")
        val gpx = doc.copy(stops = listOf(stop)).toGpx("Antimeridian")

        val longitudes = Regex("""lon="([^"]+)"""").findAll(gpx).map { it.groupValues[1] }.toList()
        assertEquals(listOf("-180.0", "-180.0", "179.999999"), longitudes)
        val result = importRoute(gpx)!!
        val antimeridian = stop.point.copy(lng = -180.0)
        assertEquals(listOf(antimeridian, doc.points.last()), result.points)
        assertEquals(listOf(stop.copy(point = antimeridian)), result.stops)
    }

    @Test fun savedGeoJsonNamesAndStopLabelsRemainValidGpxText() {
        val doc = importRoute("""{"type":"Feature","properties":{"name":"A\u0000 & 港🚢"},
            "geometry":{"type":"LineString","coordinates":[[1,2],[3,4]]}}""")!!
        val label = "\u000B<stop>\uD800\uFFFE\r\n🚲"
        val row = SavedRoute.from(doc.name!!, doc.copy(stops = listOf(RouteStop(doc.points.first(), label))))
        assertEquals("A\uFFFD & 港🚢", row.name)
        assertEquals(RouteDocument(row.name, doc.points,
            listOf(RouteStop(doc.points.first(), "\uFFFD<stop>\uFFFD\uFFFD\r\n🚲"))), importRoute(row.gpx))
    }

    @Test fun pickerReadEnforcesTheByteLimitEvenWithoutProviderMetadata() {
        val route = """{"type":"LineString","coordinates":[[1,2],[3,4]]}""".toByteArray()
        val exact = ByteArray(MAX_ROUTE_FILE_BYTES + 128) { ' '.code.toByte() }
        route.copyInto(exact)
        assertNotNull(importRoute(exact.inputStream(0, MAX_ROUTE_FILE_BYTES).readRouteText()))

        val tooLarge = exact.inputStream()
        assertThrows(RouteFileTooLargeException::class.java) { tooLarge.readRouteText() }
        assertEquals(127, tooLarge.available()) // no need to read beyond the first excess byte
    }
}
