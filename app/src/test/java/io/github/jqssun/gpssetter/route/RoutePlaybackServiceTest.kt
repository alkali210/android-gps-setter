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
            assertNotNull(RoutePlaybackService.gpxState.value)
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertNull(RoutePlaybackService.gpxState.value)
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
            assertNull(RoutePlaybackService.gpxState.value)
            assertTrue(PrefManager.isStarted)
            assertEquals(lat, PrefManager.getLat, 0.0)
            assertEquals(lng, PrefManager.getLng, 0.0)
            assertEquals(0f, PrefManager.speed)
        } finally {
            controller.destroy()
        }
    }

    @Test fun pauseHoldsFixAndResumeExcludesPausedTimeAtTheNewSpeed() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        fun deliver() {
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
        }
        fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        try {
            RoutePlaybackService.start(context, listOf(RoutePoint(0.0, 0.0, 100.0), RoutePoint(0.0, 1.0, 200.0)), 10f, false, gpx = true)
            deliver()
            advance(4)
            val held = RoutePoint(PrefManager.getLat, PrefManager.getLng, PrefManager.altitude)
            RoutePlaybackService.setPaused(context, true)
            deliver()
            advance(60)
            assertEquals(held.lat, PrefManager.getLat, 0.0)
            assertEquals(held.lng, PrefManager.getLng, 0.0)
            assertEquals(held.altitude, PrefManager.altitude, 0.0)
            assertEquals(0f, PrefManager.speed)
            assertTrue(PrefManager.isStarted)
            assertEquals(true, RoutePlaybackService.gpxState.value?.paused)

            RoutePlaybackService.adjustSpeed(context, 36)
            deliver()
            advance(5)
            assertEquals(held.lng, PrefManager.getLng, 0.0)
            assertEquals(0f, PrefManager.speed)
            RoutePlaybackService.setPaused(context, false)
            deliver()
            advance(1)
            val resumed = RoutePoint(PrefManager.getLat, PrefManager.getLng)
            assertEquals(5.0, RouteMath.pathLength(listOf(held, resumed)), 0.1)
            advance(3)
            assertEquals(20f, PrefManager.speed)
            val beforeSlowing = RoutePoint(PrefManager.getLat, PrefManager.getLng)
            RoutePlaybackService.adjustSpeed(context, -54)
            deliver()
            advance(1)
            assertEquals(5f, PrefManager.speed)
            assertEquals(5.0, RouteMath.pathLength(listOf(beforeSlowing, RoutePoint(PrefManager.getLat, PrefManager.getLng))), 0.1)
            RoutePlaybackService.adjustSpeed(context, -100)
            deliver()
            advance(1)
            assertEquals(1f / 3.6f, PrefManager.speed)
        } finally {
            controller.destroy()
        }
    }

    @Test fun stoppingWhilePausedEndsSessionAndLateControlsCannotRestartIt() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        fun deliver() {
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
        }
        try {
            RoutePlaybackService.start(context, listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), 10f, false, gpx = true)
            deliver()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            RoutePlaybackService.setPaused(context, true)
            deliver()
            val lng = PrefManager.getLng
            RoutePlaybackService.stop(context)
            deliver()
            assertNull(RoutePlaybackService.gpxState.value)
            assertTrue(PrefManager.isStarted)
            PrefManager.update(false, PrefManager.getLat, lng)
            RoutePlaybackService.setPaused(context, false)
            deliver()
            RoutePlaybackService.adjustSpeed(context, 36)
            deliver()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10))
            assertNull(RoutePlaybackService.gpxState.value)
            assertFalse(PrefManager.isStarted)
            assertEquals(lng, PrefManager.getLng, 0.0)
        } finally {
            controller.destroy()
        }
    }
}
