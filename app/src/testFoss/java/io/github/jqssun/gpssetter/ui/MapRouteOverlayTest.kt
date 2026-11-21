package io.github.jqssun.gpssetter.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRouteOverlayTest {
    private val planned = listOf(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0)
    private val gpx = listOf(10.0 to 20.0, 11.0 to 21.0)

    @Test fun deferredPlanningDrawKeepsPointsAndTravelerOrder() {
        val overlay = RecordingOverlay()
        overlay.setTraveler(1.0, 2.0) // The first tap precedes the routing result.
        overlay.drawRoute(planned, fitCamera = true)
        overlay.setTraveler(3.0, 4.0)
        overlay.setTraveler(5.0, 6.0)
        assertTrue(overlay.events.isEmpty())

        overlay.onStyleLoaded()
        overlay.onStyleLoaded()

        assertEquals(listOf(Clear, Draw(planned, true), Traveler(5.0, 6.0)), overlay.events)
    }

    @Test fun initialGpxDrawFitsInsteadOfRunningThemeRestoration() {
        val overlay = RecordingOverlay(activeGpx = gpx)
        overlay.drawRoute(gpx, fitCamera = true)
        assertTrue(overlay.events.isEmpty())

        overlay.onStyleLoaded()

        assertEquals(listOf(Clear, Draw(gpx, true)), overlay.events)
    }

    @Test fun themeRestorationWithoutPendingDrawDoesNotFit() {
        val overlay = RecordingOverlay(activeGpx = gpx)

        overlay.onStyleLoaded()
        overlay.onStyleLoaded()

        assertEquals(listOf(Clear, Draw(gpx, false)), overlay.events)
    }

    @Test fun lastPendingDrawWinsWithItsOwnFitFlag() {
        val overlay = RecordingOverlay(activeGpx = gpx)
        overlay.drawRoute(gpx, fitCamera = true)
        overlay.setTraveler(10.0, 20.0)
        overlay.drawRoute(planned, fitCamera = false)

        overlay.onStyleLoaded()

        assertEquals(listOf(Clear, Draw(planned, false)), overlay.events)
    }

    @Test fun clearBeforeLoadDiscardsRouteAndTravelerAndDoesNotRestore() {
        val overlay = RecordingOverlay(activeGpx = gpx)
        overlay.drawRoute(gpx, fitCamera = true)
        overlay.setTraveler(10.0, 20.0)
        overlay.clearRoute()

        overlay.onStyleLoaded()
        assertTrue(overlay.events.isEmpty())

        overlay.drawRoute(planned, fitCamera = true)
        overlay.clearRoute()
        assertEquals(listOf(Clear, Draw(planned, true), Clear), overlay.events)
    }

    @Test fun invalidDrawCancelsEarlierPendingRequest() {
        val overlay = RecordingOverlay()
        overlay.drawRoute(planned, fitCamera = true)
        overlay.drawRoute(listOf(10.0 to 20.0), fitCamera = true)
        overlay.onStyleLoaded()

        assertTrue(overlay.events.isEmpty())
    }

    private sealed interface Event
    private object Clear : Event
    private data class Draw(val points: List<Pair<Double, Double>>, val fitCamera: Boolean) : Event
    private data class Traveler(val lat: Double, val lng: Double) : Event

    private class RecordingOverlay(private val activeGpx: List<Pair<Double, Double>> = emptyList()) : MapRouteOverlay() {
        val events = mutableListOf<Event>()

        override fun renderRoute(points: List<Pair<Double, Double>>, fitCamera: Boolean) {
            events += Draw(points, fitCamera)
        }

        override fun clearAnnotations() {
            events += Clear
        }

        override fun renderTraveler(lat: Double, lng: Double) {
            events += Traveler(lat, lng)
        }

        override fun restoreRoute() {
            if (activeGpx.isNotEmpty()) drawRoute(activeGpx, fitCamera = false)
        }
    }
}
