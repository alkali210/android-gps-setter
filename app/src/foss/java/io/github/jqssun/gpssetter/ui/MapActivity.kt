package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch
import io.github.jqssun.gpssetter.utils.Accent
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
import org.maplibre.android.maps.SupportMapFragment
import org.maplibre.android.location.engine.LocationEngineRequest
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.xposed.LocationUpdateInterval

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, MapLibreMap.OnMapClickListener {

    private lateinit var mMap: MapLibreMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    override fun setLocationUpdateInterval(intervalMs: Long) {
        if (!::mMap.isInitialized || !mMap.locationComponent.isLocationComponentActivated) return
        val component = mMap.locationComponent
        if (component.locationEngineRequest.interval != intervalMs) {
            component.locationEngineRequest = locationRequest(intervalMs)
        }
    }

    private fun locationRequest(intervalMs: Long) = LocationEngineRequest.Builder(intervalMs)
        .setFastestInterval(intervalMs)
        .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
        .build()

    override fun onPause() {
        if (::mMap.isInitialized) viewModel.mapCameraPosition = mMap.cameraPosition
        super.onPause()
    }

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
    private fun removeMarker() {
        mMarker?.remove() // mMarker?.isVisible = false
        mMarker = null
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
    override fun centerMapOnLocation(latitude: Double, longitude: Double) {
        val camera = mMap.cameraPosition
        mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder(camera)
                .target(LatLng(latitude, longitude))
                .zoom(maxOf(camera.zoom, 12.0))
                .build()
        ))
    }

    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (isGpxActive || !::mMap.isInitialized) return
        val position = LatLng(lat, lon)
        mLatLng = position
        if (moveNewLocation) {
            mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(position)
                    .zoom(12.0)
                    .bearing(0.0)
                    .tilt(0.0)
                    .build()
            ))
        }
        updateMarker(position)
    }
    override fun onMapReady(mapLibreMap: MapLibreMap) {
        mMap = mapLibreMap
        with(mMap){


            // maplibre custom ui
            // ponytail: keyless OpenFreeMap for every map type; satellite/terrain need a keyed provider (e.g. own Mapbox token)
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val mapStyle = if (isNight) "dark" else "liberty"
            setStyle("https://tiles.openfreemap.org/styles/$mapStyle") { style ->
                if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                    val locationComponent = mMap.locationComponent
                    locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(this@MapActivity, style)
                        .useDefaultLocationEngine(true)
                        .locationEngineRequest(locationRequest(RoutePlaybackService.gpxState.value?.updateIntervalMs ?: LocationUpdateInterval.DEFAULT_MS))
                        .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    locationComponent.cameraMode = if (viewModel.mapCameraPosition != null) CameraMode.NONE else CameraMode.TRACKING
                    locationComponent.renderMode = RenderMode.COMPASS
                } else {
                    ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
                }
                // Native annotations must be restored after the new theme's style is loaded.
                restoreGpxOverlay()
            }
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
            mLatLng?.let {
                if (!isGpxActive) updateMarker(it)
                // TODO: MarkerOptions().position(it!!)
                // .draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED).visible(false)
                val camera = viewModel.mapCameraPosition as? CameraPosition
                if (camera != null) {
                    mMap.moveCamera(CameraUpdateFactory.newCameraPosition(camera))
                } else {
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom.toDouble()))
                }
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
    override fun onMapClick(latLng: LatLng): Boolean {
        if (onMapTapped(latLng.latitude, latLng.longitude)) return true
        mLatLng = latLng
        updateMarker(latLng)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(latLng))
        lat = latLng.latitude
        lon = latLng.longitude
        return true
    }



    override fun getActivityInstance(): BaseMapActivity {
        return this@MapActivity
    }

    private var routeLine: Polyline? = null
    private var aMarker: Marker? = null
    private var bMarker: Marker? = null
    private var travelerMarker: Marker? = null

    override fun drawRoute(points: List<Pair<Double, Double>>, fitCamera: Boolean) {
        clearRoute()
        if (points.size < 2) return
        val lls = points.map { LatLng(it.first, it.second) }
        routeLine = mMap.addPolyline(PolylineOptions().addAll(lls).color(Accent.current().color).width(4f))
        val ic = IconFactory.getInstance(this)
        aMarker = mMap.addMarker(MarkerOptions().position(lls.first()).icon(ic.fromBitmap(dotBitmap(0xFF22C55E.toInt()))))
        bMarker = mMap.addMarker(MarkerOptions().position(lls.last()).icon(ic.fromBitmap(dotBitmap(0xFFEF4444.toInt()))))
        if (fitCamera) {
            val bounds = LatLngBounds.Builder().includes(lls).build()
            mMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
        }
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
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            if (handleGpxPlaybackClick(paused = false)) return@setOnClickListener
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                mLatLng?.getAddress(getActivityInstance())?.let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            if (handleGpxPlaybackClick(paused = true)) return@setOnClickListener
            viewModel.update(false, viewModel.getLat, viewModel.getLng)
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }
}
