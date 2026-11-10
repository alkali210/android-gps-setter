package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import io.github.jqssun.gpssetter.utils.Accent
import io.github.jqssun.gpssetter.utils.PrefManager
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.OnMapReadyCallback
import org.maplibre.android.maps.Style
import org.maplibre.android.maps.SupportMapFragment

typealias CustomLatLng = LatLng

// Keyless raster tiles for the non-Normal map types (#12); Normal stays on OpenFreeMap vector tiles.
// ponytail: Esri's public tile endpoints work without a key, but their terms expect an ArcGIS account
// for production use; swap these URLs for a keyed provider if that ever matters.
private const val ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services/"
private const val ESRI_IMAGERY = ESRI + "World_Imagery/MapServer/tile/{z}/{y}/{x}"
private const val ESRI_ROADS = ESRI + "Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"
private const val ESRI_PLACES = ESRI + "Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"
private const val ESRI_ATTRIBUTION = "Tiles © Esri, Maxar, Earthstar Geographics"
private const val OPEN_TOPO = "https://tile.opentopomap.org/{z}/{x}/{y}.png"
private const val OPEN_TOPO_ATTRIBUTION = "© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"

// A MapLibre style of stacked raster layers: the first URL is the base, the rest overlay it (Hybrid labels).
private fun rasterStyle(attribution: String, maxZoom: Int, vararg urls: String): Style.Builder {
    val sources = urls.withIndex().joinToString(",") { (i, url) ->
        """"s$i":{"type":"raster","tiles":["$url"],"tileSize":256,"maxzoom":$maxZoom,"attribution":"$attribution"}"""
    }
    val layers = urls.indices.joinToString(",") { """{"id":"l$it","type":"raster","source":"s$it"}""" }
    return Style.Builder().fromJson("""{"version":8,"sources":{$sources},"layers":[$layers]}""")
}

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    override fun hasMarker(): Boolean {
        // TODO: if (!mMarker?.isVisible!!){
        if (mMarker != null) {
            return true
        }
        return false
    }
    private fun updateMarker(it: LatLng) {
        // TODO: mMarker?.isVisible = true
        if (mMarker == null) {
            mMarker = mMap.addMarker(
                MarkerOptions().position(it)
            )
        } else {
            mMarker?.position = it!!
        }
    }
    override fun initializeMap() {
        MapLibre.getInstance(this)
        // val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment?
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation && ::mMap.isInitialized) {
            mLatLng = LatLng(lat, lon)
            mLatLng.let { latLng ->
                // mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng!!, 12.0f.toDouble()))
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f.toDouble())
                        .bearing(0f.toDouble())
                        .tilt(0f.toDouble())
                        .build()
                ))
                updateMarker(latLng)
            }
        }
    }
    private var appliedMapType = 0

    // pref 1 Normal, 2 Satellite, 3 Terrain, 4 Hybrid. Skips an unchanged type so every resume doesn't reload (and flash) the map.
    // A theme change recreates the activity, so Normal's light/dark pick is always current.
    override fun applyMapType() {
        if (!::mMap.isInitialized) return
        val type = PrefManager.mapType
        if (type == appliedMapType) return
        appliedMapType = type
        val builder = when (type) {
            2 -> rasterStyle(ESRI_ATTRIBUTION, 19, ESRI_IMAGERY)
            3 -> rasterStyle(OPEN_TOPO_ATTRIBUTION, 17, OPEN_TOPO)
            4 -> rasterStyle(ESRI_ATTRIBUTION, 19, ESRI_IMAGERY, ESRI_ROADS, ESRI_PLACES)
            else -> Style.Builder().fromUri("https://tiles.openfreemap.org/styles/" + if (isNight) "dark" else "liberty")
        }
        mMap.setStyle(builder) { style ->
            // the location puck carries over to a new style; only set it up (or ask for permission) once
            if (mMap.locationComponent.isLocationComponentActivated) return@setStyle
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationComponent = mMap.locationComponent
                locationComponent.activateLocationComponent(
                    LocationComponentActivationOptions.builder(this@MapActivity, style)
                    .useDefaultLocationEngine(true)
                    .build()
                )
                locationComponent.isLocationComponentEnabled = true
                locationComponent.cameraMode = CameraMode.TRACKING
                locationComponent.renderMode = RenderMode.COMPASS
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
        }
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui
            applyMapType()
            // TODO: fix bug with drawer
            uiSettings.setAllGesturesEnabled(true)
            uiSettings.setCompassEnabled(true)
            uiSettings.setCompassMargins(0,480,120,0)
            uiSettings.setLogoEnabled(true)
            uiSettings.setLogoMargins(0,0,0,80)
            uiSettings.setAttributionEnabled(true) // OSM data (ODbL) requires attribution
            uiSettings.setAttributionMargins(uiSettings.attributionMarginLeft, 0, 0, 80)
            // uiSettings.setAttributionMargins(80,0,0,80)
            // setPadding(0,0,0,80)


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                updateMarker(it!!)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
            }

            addOnMapClickListener(this@MapActivity)
            if (viewModel.isStarted){
                mMarker?.let {
                    // TODO:
                    // it.isVisible = true
                    // it.showInfoWindow()
                }
            }
        }
    }
    override fun selectPoint(la: Double, ln: Double) {
        if (!::mMap.isInitialized) return
        val ll = LatLng(la, ln)
        mLatLng = ll
        updateMarker(ll)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(ll))
        lat = la
        lon = ln
    }
    override fun onMapClick(latLng: LatLng): Boolean {
        if (!onMapTapped(latLng.latitude, latLng.longitude)) selectPoint(latLng.latitude, latLng.longitude)
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    private var routeLine: Polyline? = null
    private var aMarker: Marker? = null
    private var bMarker: Marker? = null
    private var travelerMarker: Marker? = null

    override fun drawRoute(points: List<Pair<Double, Double>>) {
        clearRoute()
        if (points.size < 2) return
        val lls = points.map { LatLng(it.first, it.second) }
        routeLine = mMap.addPolyline(PolylineOptions().addAll(lls).color(Accent.current().color).width(4f))
        val ic = IconFactory.getInstance(this)
        aMarker = mMap.addMarker(MarkerOptions().position(lls.first()).icon(ic.fromBitmap(dotBitmap(0xFF22C55E.toInt()))))
        bMarker = mMap.addMarker(MarkerOptions().position(lls.last()).icon(ic.fromBitmap(dotBitmap(0xFFEF4444.toInt()))))
        val bounds = LatLngBounds.Builder().includes(lls).build()
        mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
    }

    override fun clearRoute() {
        routeLine?.remove(); routeLine = null
        aMarker?.remove(); aMarker = null
        bMarker?.remove(); bMarker = null
        travelerMarker?.remove(); travelerMarker = null
    }

    override fun setTraveler(lat: Double, lng: Double) {
        val pos = LatLng(lat, lng)
        if (travelerMarker == null) {
            travelerMarker = mMap.addMarker(MarkerOptions().position(pos).icon(IconFactory.getInstance(this).fromBitmap(dotBitmap(Accent.current().color))))
        } else {
            travelerMarker?.position = pos
        }
    }

    @SuppressLint("MissingPermission")
    override fun setupButtons(){
        binding.addfavorite.setOnClickListener {
            addFavoriteDialog()
        }
        // tap: toggle 1 km <-> 100 m around the current centre (#8); long-press: jump to the device location
        binding.getlocation.setOnClickListener {
            if (!::mMap.isInitialized) return@setOnClickListener // map still loading
            val v = mMap.projection.visibleRegion
            val l = v.nearLeft ?: return@setOnClickListener
            val r = v.nearRight ?: return@setOnClickListener
            val width = metersBetween(l.latitude, l.longitude, r.latitude, r.longitude)
            mMap.animateCamera(CameraUpdateFactory.zoomTo(MapScale.toggle(mMap.cameraPosition.zoom, width)))
        }
        binding.getlocation.setOnLongClickListener { getLastLocation(); true }

        // which of the two shows (and the "Move here" label) is BaseMapActivity.renderTeleport's job
        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            lifecycleScope.launch {
                LatLng(lat, lon).getAddress(getActivityInstance()).collect { showStartNotification(it) }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            viewModel.update(false, lat, lon)
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
