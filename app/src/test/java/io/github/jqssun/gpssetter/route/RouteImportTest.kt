package io.github.jqssun.gpssetter.route

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
}
