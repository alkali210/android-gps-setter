package io.github.jqssun.gpssetter.ui

import android.content.res.Configuration
import android.os.Looper
import android.view.View
import io.github.jqssun.gpssetter.R
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.gms.dynamic.ObjectWrapper
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.PolylineOptions
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel
import io.github.jqssun.gpssetter.utils.PrefManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpxThemeRecreationTest {
    @Test fun explicitThemeRecreationRetainsRouteAndCamera() = exerciseRecreation(false)

    @Test fun followSystemRecreationRetainsRouteAndCamera() = exerciseRecreation(true)

    @Test fun completionClearsRouteWithoutMovingCamera() = exerciseRouteEnd(false)

    @Test fun stoppingPausedRouteInBackgroundClearsItOnReturn() = exerciseRouteEnd(true)

    private fun exerciseRouteEnd(stopInBackground: Boolean) {
        installMapFactories()
        val controller = Robolectric.buildActivity(MapActivity::class.java).setup()
        val service = Robolectric.buildService(RoutePlaybackService::class.java).create()
        try {
            val home = controller.get()
            val route = listOf(0.0 to 0.0, 0.0 to 0.00001)
            val model = BaseMapActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }.invoke(home) as MainViewModel
            model.gpxOverlay = route
            RoutePlaybackService.start(home, route.map { RoutePoint(it.first, it.second) }, 10f, false, gpx = true)
            service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val camera = CameraPosition(LatLng(1.0, 2.0), 17f, 30f, 60f)
            val surface = MapSurface(camera)
            home.onMapReady(surface.map)
            surface.camera = camera
            assertEquals(listOf(route.map { LatLng(it.first, it.second) }), surface.routes)
            if (stopInBackground) {
                RoutePlaybackService.setPaused(home, true)
                service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 2)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(listOf(route.map { LatLng(it.first, it.second) }), surface.routes)
                controller.pause().stop()
                RoutePlaybackService.stop(home)
                service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 3)
                shadowOf(Looper.getMainLooper()).idle()
                controller.start().resume().visible()
            } else {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(surface.routes.isEmpty())
            assertTrue(model.gpxOverlay.isEmpty())
            assertEquals(camera, surface.camera)
            assertTrue(PrefManager.isStarted)
            assertEquals(if (stopInBackground) 0.0 else 0.00001, PrefManager.getLng, 0.0)
            controller.recreate()
            val restored = MapSurface(CameraPosition(LatLng(0.0, 0.0), 2f, 0f, 0f))
            controller.get().onMapReady(restored.map)
            assertTrue(restored.routes.isEmpty())
            assertEquals(camera, restored.camera)
        } finally {
            service.destroy()
            controller.pause().stop().destroy()
        }
    }

    private fun exerciseRecreation(followSystem: Boolean) {
        installMapFactories()
        AppCompatDelegate.setDefaultNightMode(if (followSystem) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM else AppCompatDelegate.MODE_NIGHT_NO)
        val controller = Robolectric.buildActivity(MapActivity::class.java).setup()
        val service = Robolectric.buildService(RoutePlaybackService::class.java).create()
        try {
            val first = controller.get()
            val route = listOf(17.0 to 78.0, 18.0 to 79.0)
            val model = BaseMapActivity::class.java.getDeclaredMethod("getViewModel").apply { isAccessible = true }.invoke(first) as MainViewModel
            model.gpxOverlay = route
            RoutePlaybackService.start(first, route.map { RoutePoint(it.first, it.second) }, 10f, false, gpx = true)
            service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            val originalCamera = CameraPosition(LatLng(18.25, 79.5), 17.5f, 35f, 75f)
            val initialMap = MapSurface(originalCamera)
            first.onMapReady(initialMap.map)
            // Represents the user's pan/zoom/rotation after importing the track.
            initialMap.camera = originalCamera
            if (followSystem) {
                first.findViewById<View>(R.id.stop_button).performClick()
                service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 2)
                shadowOf(Looper.getMainLooper()).idle()
                repeat(3) { first.findViewById<View>(R.id.gpx_speed_plus).performClick() }
                repeat(3) {
                    service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 3)
                }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(RoutePlaybackService.gpxState.value!!.paused)
                assertEquals(0f, PrefManager.speed)
            }

            for (dark in listOf(true, false)) {
                val previous = controller.get()
                if (followSystem) {
                    val configuration = Configuration(previous.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    controller.configurationChange(configuration)
                } else {
                    AppCompatDelegate.setDefaultNightMode(
                        if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
                    )
                    controller.recreate()
                }
                val recreated = controller.get()
                assertNotSame(previous, recreated)
                val restoredMap = MapSurface(CameraPosition(LatLng(0.0, 0.0), 2f, 0f, 0f))
                recreated.onMapReady(restoredMap.map)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(originalCamera, restoredMap.camera)
                assertEquals(listOf(route.map { GoogleMapCoordinates(PrefManager.mapType).toMap(it.first, it.second) }), restoredMap.routes)
                assertEquals(0, restoredMap.fitBoundsCalls)
                assertNotNull(RoutePlaybackService.gpxState.value)
                assertTrue(PrefManager.isStarted)
                assertEquals(followSystem, RoutePlaybackService.gpxState.value!!.paused)
                assertEquals(View.VISIBLE, recreated.findViewById<View>(R.id.gpx_controls).visibility)
                assertEquals(if (followSystem) View.VISIBLE else View.GONE, recreated.findViewById<View>(R.id.start_button).visibility)
                assertEquals(if (followSystem) 51f / 3.6f else 10f,
                    RoutePlaybackService.gpxState.value!!.speedMps, 0.00001f)
            }
            if (followSystem) {
                val home = controller.get()
                home.findViewById<View>(R.id.start_button).performClick()
                service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 4)
                shadowOf(Looper.getMainLooper()).idle()
                assertFalse(RoutePlaybackService.gpxState.value!!.paused)
                assertEquals(View.VISIBLE, home.findViewById<View>(R.id.stop_button).visibility)
                assertTrue(PrefManager.isStarted)
            }
        } finally {
            service.destroy()
            controller.pause().stop().destroy()
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    // Replace only the remote Google Maps renderer. Activity, ViewModel retention,
    // AppCompat configuration handling and playback service run under Robolectric.
    private class MapSurface(var camera: CameraPosition) {
        val routes = mutableListOf<List<LatLng>>()
        var fitBoundsCalls = 0
        val map: GoogleMap

        init {
            val constructor = GoogleMap::class.java.constructors.single()
            val polylineType = constructor.parameterTypes.single().methods.single { it.name == "addPolyline" }.returnType
            val removePolyline = polylineType.methods.single { it.returnType == Void.TYPE && it.parameterCount == 0 }.name
            val delegate = proxy(constructor.parameterTypes.single()) { name, args ->
                when (name) {
                    "getCameraPosition" -> camera
                    "addPolyline" -> {
                        val points = (args!![0] as PolylineOptions).points.toList()
                        routes.add(points)
                        proxy(polylineType) { method, _ ->
                            if (method == removePolyline) {
                                routes.remove(points)
                                null
                            } else Unhandled
                        }
                    }
                    "moveCamera", "animateCamera" -> {
                        val update = ObjectWrapper.unwrap<Any>(args!![0] as com.google.android.gms.dynamic.IObjectWrapper)
                        if (update is CameraPosition) camera = update else fitBoundsCalls++
                        null
                    }
                    else -> Unhandled
                }
            }
            map = constructor.newInstance(delegate) as GoogleMap
        }
    }

    companion object {
        private object Unhandled

        private fun proxy(type: Class<*>, handler: (String, Array<out Any?>?) -> Any? = { _, _ -> Unhandled }): Any =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                val result = handler(method.name, args)
                if (result !== Unhandled) result else when {
                    method.returnType == Boolean::class.javaPrimitiveType -> false
                    method.returnType == Int::class.javaPrimitiveType -> 0
                    method.returnType == Float::class.javaPrimitiveType -> 0f
                    method.returnType == Double::class.javaPrimitiveType -> 0.0
                    method.returnType.isInterface -> proxy(method.returnType)
                    else -> null
                }
            }

        private fun installMapFactories() {
            for (factory in listOf(CameraUpdateFactory::class.java, BitmapDescriptorFactory::class.java)) {
                val initializer = factory.declaredMethods.single {
                    Modifier.isStatic(it.modifiers) && it.parameterTypes.size == 1 && it.parameterTypes[0].isInterface
                }
                initializer.invoke(null, proxy(initializer.parameterTypes.single()) { name, args ->
                    if (name == "newCameraPosition") ObjectWrapper.wrap(args!![0]!!)
                    else ObjectWrapper.wrap(name)
                })
            }
        }
    }
}
