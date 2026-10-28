package io.github.jqssun.gpssetter.ui

import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleMapCoordinatesTest {
    private val mapTypes = listOf(
        GoogleMap.MAP_TYPE_NORMAL, GoogleMap.MAP_TYPE_TERRAIN,
        GoogleMap.MAP_TYPE_SATELLITE, GoogleMap.MAP_TYPE_HYBRID
    )

    @Test fun allMapTypesDisplayGcj02AndReturnWgs84ForSelectedPoints() {
        for (type in mapTypes) {
            val coordinates = GoogleMapCoordinates(type)
            val display = coordinates.toMap(39.908823, 116.397470)
            assertEquals(39.91022649807321, display.latitude, 1e-10)
            assertEquals(116.4037135824225, display.longitude, 1e-10)
            val selected = coordinates.fromMap(LatLng(39.91022649807321, 116.4037135824225))
            assertEquals(39.908823, selected.first, 1e-7)
            assertEquals(116.397470, selected.second, 1e-7)
        }
    }

    @Test fun allMapTypesLeaveCoordinatesOutsideTheConversionRegionUnchanged() {
        for (type in mapTypes) {
            val coordinates = GoogleMapCoordinates(type)
            val display = coordinates.toMap(51.5074, -0.1278)
            assertEquals(51.5074, display.latitude, 0.0)
            assertEquals(-0.1278, display.longitude, 1e-10)
            val selected = coordinates.fromMap(LatLng(51.5074, -0.1278))
            assertEquals(51.5074, selected.first, 0.0)
            assertEquals(-0.1278, selected.second, 1e-10)
        }
    }
}
