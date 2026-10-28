package io.github.jqssun.gpssetter.ui


import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import io.github.jqssun.gpssetter.utils.ext.getAddress
import io.github.jqssun.gpssetter.utils.ext.showToast
import kotlinx.coroutines.launch

typealias CustomLatLng = LatLng

class MapActivity: BaseMapActivity(), OnMapReadyCallback, GoogleMap.OnMapClickListener {

    private lateinit var mMap: GoogleMap
    private var mLatLng: LatLng? = null
    private var mMarker: Marker? = null
    private val coordinates by lazy { GoogleMapCoordinates(viewModel.mapType) }

    override fun hasMarker(): Boolean {
        return mMarker?.isVisible == true
    }
    private fun updateMarker(it: LatLng) {
        mMarker?.position = it!!
        mMarker?.isVisible = true
    }
    private fun removeMarker() {
        mMarker?.isVisible = false
    }
    override fun initializeMap() {
        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.map, mapFragment)
            .commit()
        mapFragment?.getMapAsync(this)
    }
    override fun moveMapToNewLocation(moveNewLocation: Boolean) {
        if (moveNewLocation) {
            mLatLng = coordinates.toMap(lat, lon)
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
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.setLocationSource(GoogleMapLocationSource(this, lifecycle, coordinates))
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
            mapType = viewModel.mapType
            val isNight = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            setMapStyle(if (isNight) {
                com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(this@MapActivity, R.raw.map_style_dark)
            } else {
                null // Restore Google's default light map.
            })


            val zoom = 12.0f
            lat = viewModel.getLat
            lon  = viewModel.getLng
            mLatLng = coordinates.toMap(lat, lon)
            mLatLng.let {
                mMarker = addMarker(
                    MarkerOptions().position(it!!).draggable(false).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)).visible(false)
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
    override fun onMapClick(latLng: LatLng) {
        val point = coordinates.fromMap(latLng)
        if (onMapTapped(point.first, point.second)) return
        lat = point.first
        lon = point.second
        mLatLng = latLng
        mMarker?.let { marker ->
            mLatLng.let {
                // marker.isVisible = true
                updateMarker(it!!)
                mMap.animateCamera(CameraUpdateFactory.newLatLng(it))
            }
        }
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
        val lls = points.map { coordinates.toMap(it.first, it.second) }
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
        val pos = coordinates.toMap(lat, lng)
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
        binding.getlocation.setOnClickListener {
            getLastLocation()
        }

        if (viewModel.isStarted) {
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
        }

        binding.startButton.setOnClickListener {
            viewModel.update(true, lat, lon)
            mLatLng.let {
                updateMarker(it!!)
            }
            binding.startButton.visibility = View.GONE
            binding.stopButton.visibility = View.VISIBLE
            lifecycleScope.launch {
                LatLng(lat, lon).getAddress(getActivityInstance()).let { address ->
                    address.collect{ value ->
                        showStartNotification(value)
                    }
                }
            }
            showToast(getString(R.string.location_set))
        }
        binding.stopButton.setOnClickListener {
            viewModel.update(false, lat, lon)
            removeMarker()
            binding.stopButton.visibility = View.GONE
            binding.startButton.visibility = View.VISIBLE
            cancelNotification()
            showToast(getString(R.string.location_unset))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (::mMap.isInitialized &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
        }
    }
}
