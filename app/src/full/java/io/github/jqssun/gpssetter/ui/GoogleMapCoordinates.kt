package io.github.jqssun.gpssetter.ui

import android.location.Location
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLng
import io.github.jqssun.gpssetter.utils.ChinaCoordinates

/** Google map display coordinates use GCJ-02 in China; stored and injected fixes stay WGS-84. */
internal class GoogleMapCoordinates(mapType: Int) {
    // China satellite imagery and hybrid labels share the same display datum.
    // Map type changes the rendering, not the coordinate conversion boundary.
    private val shiftedMap = mapType == GoogleMap.MAP_TYPE_NORMAL ||
        mapType == GoogleMap.MAP_TYPE_TERRAIN ||
        mapType == GoogleMap.MAP_TYPE_SATELLITE ||
        mapType == GoogleMap.MAP_TYPE_HYBRID

    fun toMap(lat: Double, lng: Double): LatLng {
        if (!shiftedMap) return LatLng(lat, lng)
        val point = ChinaCoordinates.wgs84ToGcj02(lat, lng)
        return LatLng(point.first, point.second)
    }

    fun fromMap(point: LatLng): Pair<Double, Double> =
        if (shiftedMap) ChinaCoordinates.gcj02ToWgs84(point.latitude, point.longitude)
        else point.latitude to point.longitude

    fun toMapLocation(fix: Location): Location {
        val point = toMap(fix.latitude, fix.longitude)
        return Location(fix).apply {
            latitude = point.latitude
            longitude = point.longitude
        }
    }
}
