package io.github.jqssun.gpssetter.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.LocationSource

/** Converts only the blue-dot copy, never the provider fix consumed by the rest of the app. */
internal class GoogleMapLocationSource(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val coordinates: GoogleMapCoordinates
) : LocationSource, DefaultLifecycleObserver {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private var listener: LocationSource.OnLocationChangedListener? = null
    private var requesting = false
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val fix = result.lastLocation ?: return
            listener?.onLocationChanged(coordinates.toMapLocation(fix))
        }
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun activate(listener: LocationSource.OnLocationChangedListener) {
        this.listener = listener
        start()
    }

    override fun deactivate() {
        listener = null
        stop()
    }

    override fun onStart(owner: LifecycleOwner) = start()
    override fun onStop(owner: LifecycleOwner) = stop()
    override fun onDestroy(owner: LifecycleOwner) {
        deactivate()
        lifecycle.removeObserver(this)
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (requesting || listener == null || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        requesting = true
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener {
                requesting = false
                Log.w("GPS Setter", "Unable to update the map's location dot", it)
            }
    }

    private fun stop() {
        if (!requesting) return
        requesting = false
        client.removeLocationUpdates(callback)
    }
}
