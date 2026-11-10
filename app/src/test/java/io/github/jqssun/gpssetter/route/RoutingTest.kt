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

    @Test fun emptyOnBadJson() {
        assertTrue(coordsFromGeoJson("nonsense", "features").isEmpty())
        assertTrue(coordsFromGeoJson("""{"features":[]}""", "features").isEmpty())
    }
}
