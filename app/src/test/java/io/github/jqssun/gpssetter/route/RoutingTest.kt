package io.github.jqssun.gpssetter.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test fun parsesOrsFeatures() {
        val json = """
            {"type":"FeatureCollection","features":[
              {"geometry":{"type":"LineString","coordinates":[[78.40,17.48],[78.41,17.49],[78.42,17.50]]}}
            ]}
        """.trimIndent()
        val pts = coordsFromGeoJson(json, "features")
        assertEquals(3, pts.size)
        assertEquals(17.48, pts[0].lat, 1e-9)   // [lng,lat] -> lat is index 1
        assertEquals(78.40, pts[0].lng, 1e-9)
    }

    @Test fun parsesOsrmRoutes() {
        val json = """{"routes":[{"geometry":{"coordinates":[[1.0,2.0],[3.0,4.0]]}}]}"""
        val pts = coordsFromGeoJson(json, "routes")
        assertEquals(2, pts.size)
        assertEquals(4.0, pts[1].lat, 1e-9)
        assertEquals(3.0, pts[1].lng, 1e-9)
    }

    @Test fun osrmUrlListsEveryStopInOrder() {
        val url = Routing.osrmUrl(listOf(RoutePoint(17.48, 78.40), RoutePoint(17.49, 78.41), RoutePoint(17.5, 78.42)))
        assertEquals(
            "https://router.project-osrm.org/route/v1/driving/78.4,17.48;78.41,17.49;78.42,17.5?overview=full&geometries=geojson",
            url
        )
    }

    @Test fun readsALineFromAnyGeoJsonWrapper() {
        val line = """{"type":"LineString","coordinates":[[78.40,17.48,500],[78.41,17.49]]}"""
        val bare = lineFromGeoJson(line)
        assertEquals(listOf(RoutePoint(17.48, 78.40, 500.0), RoutePoint(17.49, 78.41, 0.0)), bare)
        assertEquals(bare, lineFromGeoJson("""{"type":"Feature","properties":{},"geometry":$line}"""))
        // a collection joins its lines in order and skips points
        val fc = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[1,2]}},
            {"type":"Feature","geometry":$line},
            {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[[[78.42,17.50],[78.43,17.51]]]}}
        ]}"""
        assertEquals(4, lineFromGeoJson(fc).size)
        assertEquals(17.51, lineFromGeoJson(fc).last().lat, 1e-9)
    }

    @Test fun namesComeFromFeatureProperties() {
        val line = """{"type":"LineString","coordinates":[[1,2],[3,4]]}"""
        assertEquals("lake loop", nameFromGeoJson(
            """{"type":"FeatureCollection","features":[{"type":"Feature","properties":null,"geometry":$line},
               {"type":"Feature","properties":{"name":"lake loop"},"geometry":$line}]}"""))
        assertEquals(null, nameFromGeoJson(line))
        assertEquals(null, nameFromGeoJson("nonsense"))
    }

    @Test fun noLineInGeoJsonIsEmpty() {
        assertTrue(lineFromGeoJson("""{"type":"Point","coordinates":[1,2]}""").isEmpty())
        assertTrue(lineFromGeoJson("not json").isEmpty())
        assertTrue(lineFromGeoJson("[1,2,3]").isEmpty())
    }

    @Test fun emptyOnBadJson() {
        assertTrue(coordsFromGeoJson("nonsense", "features").isEmpty())
        assertTrue(coordsFromGeoJson("""{"features":[]}""", "features").isEmpty())
    }
}
