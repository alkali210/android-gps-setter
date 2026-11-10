package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.utils.Accent
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null

    override fun hasMarker(): Boolean {
        return mMarker?.isVisible == true
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    override fun initializeMap() {
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
                mMap.animateCamera(CameraUpdateFactory.newCameraPosition(
                        CameraPosition.Builder()
                        .target(latLng!!)
                        .zoom(12.0f)
                        .bearing(0f)
                        .tilt(0f)
                        .build()
                ))
                mMarker?.apply {
                    position = latLng
                    isVisible = true
                    showInfoWindow()
                }
            }
        }
    }
    // pref values 1..4 are GoogleMap.MAP_TYPE_NORMAL / SATELLITE / TERRAIN / HYBRID
    override fun applyMapType() {
        if (::mMap.isInitialized) mMap.mapType = PrefManager.mapType
    }
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        with(mMap){

            
            // gms custom ui
            if (ActivityCompat.checkSelfPermission(this@MapActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { 
                setMyLocationEnabled(true); 
            } else {
                ActivityCompat.requestPermissions(this@MapActivity, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 99);
            }
            setTrafficEnabled(true)
            uiSettings.isMyLocationButtonEnabled = false
            uiSettings.isZoomControlsEnabled = false
            uiSettings.isCompassEnabled = false
            setPadding(0,80,0,0)
            applyMapType()
            if (isNight) setMapStyle(com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(this@MapActivity, R.raw.map_style_dark))


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = LatLng(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(true)
                )
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(it, zoom))
            }

            
            setOnMapClickListener(this@MapActivity)
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
        if (mMarker == null) return // map not ready yet
        val ll = LatLng(la, ln)
        mLatLng = ll
        updateMarker(ll)
        mMap.animateCamera(CameraUpdateFactory.newLatLng(ll))
        lat = la
        lon = ln
    }
    override fun onMapClick(latLng: LatLng) {
        if (!onMapTapped(latLng.latitude, latLng.longitude)) selectPoint(latLng.latitude, latLng.longitude)
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
        routeLine = mMap.addPolyline(PolylineOptions().addAll(lls).color(Accent.current().color).width(10f))
        aMarker = mMap.addMarker(MarkerOptions().position(lls.first()).icon(BitmapDescriptorFactory.fromBitmap(dotBitmap(0xFF22C55E.toInt()))))
        bMarker = mMap.addMarker(MarkerOptions().position(lls.last()).icon(BitmapDescriptorFactory.fromBitmap(dotBitmap(0xFFEF4444.toInt()))))
        val bounds = LatLngBounds.Builder().apply { lls.forEach { include(it) } }.build()
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
            travelerMarker = mMap.addMarker(MarkerOptions().position(pos).icon(BitmapDescriptorFactory.fromBitmap(dotBitmap(Accent.current().color))))
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
            val width = metersBetween(v.nearLeft.latitude, v.nearLeft.longitude, v.nearRight.latitude, v.nearRight.longitude)
            mMap.animateCamera(CameraUpdateFactory.zoomTo(MapScale.toggle(mMap.cameraPosition.zoom.toDouble(), width).toFloat()))
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
