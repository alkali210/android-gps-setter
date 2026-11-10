package io.github.jqssun.gpssetter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MapScaleTest {
    @Test fun oneKmZoomsInToHundredMetres() {
        // 1 km -> 100 m is 10x narrower, i.e. log2(10) zoom levels in
        assertEquals(15.0 + 3.3219, MapScale.toggle(15.0, 1000.0), 1e-3)
    }

    @Test fun hundredMetresZoomsOutToOneKm() {
        assertEquals(18.3219 - 3.3219, MapScale.toggle(18.3219, 100.0), 1e-3)
    }

    @Test fun repeatedTogglesAlternate() {
        // start from the app's default wide view; each toggle lands exactly on the other scale
        var zoom = 12.0
        var width = 8000.0
        for (expected in listOf(100.0, 1000.0, 100.0, 1000.0)) {
            val next = MapScale.toggle(zoom, width)
            width /= Math.pow(2.0, next - zoom) // what the map would show after zooming
            zoom = next
            assertEquals(expected, width, 1e-6)
        }
    }
}
