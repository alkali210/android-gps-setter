package io.github.jqssun.gpssetter.route

import android.content.Context
import android.os.Looper
import io.github.jqssun.gpssetter.utils.PrefManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoutePlaybackServiceTest {
    @Test fun completionHoldsEndpointUntilExplicitStop() {
        val context: Context = RuntimeEnvironment.getApplication()
        PrefManager.update(false, 1.0, 2.0)
        val route = listOf(RoutePoint(17.0, 78.0), RoutePoint(17.00001, 78.0))
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        try {
            RoutePlaybackService.start(context, route, 10f, loop = false, gpx = true)
            assertTrue(RoutePlaybackService.isGpxPlaying.value)
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(RoutePlaybackService.isGpxPlaying.value)
            assertTrue(PrefManager.isStarted)
            assertEquals(route.last().lat, PrefManager.getLat, 0.0)
            assertEquals(route.last().lng, PrefManager.getLng, 0.0)
            assertEquals(0f, PrefManager.speed)
            PrefManager.update(false, PrefManager.getLat, PrefManager.getLng)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertFalse(PrefManager.isStarted)
            assertEquals(route.last().lat, PrefManager.getLat, 0.0)
        } finally {
            controller.destroy()
        }
    }

    @Test fun stoppingRouteFreezesCurrentFixWithoutDisablingSpoofing() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        try {
            RoutePlaybackService.start(context, listOf(RoutePoint(17.0, 78.0), RoutePoint(18.0, 79.0)), 10f, false, gpx = true)
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            val lat = PrefManager.getLat
            val lng = PrefManager.getLng
            RoutePlaybackService.stop(context)
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 2)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertFalse(RoutePlaybackService.isGpxPlaying.value)
            assertTrue(PrefManager.isStarted)
            assertEquals(lat, PrefManager.getLat, 0.0)
            assertEquals(lng, PrefManager.getLng, 0.0)
            assertEquals(0f, PrefManager.speed)
        } finally {
            controller.destroy()
        }
    }
}
