package io.github.jqssun.gpssetter.route

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import io.github.jqssun.gpssetter.utils.PrefManager
import io.github.jqssun.gpssetter.xposed.LocationUpdateInterval
import io.github.jqssun.gpssetter.xposed.HookSettings
import io.github.jqssun.gpssetter.xposed.SystemFixCadence
import io.github.jqssun.gpssetter.xposed.Xshare
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoutePlaybackCadenceTest {
    @Test fun fastCadenceMovesOnlyEvery200Milliseconds() = exerciseCadence(200L)

    @Test fun slowCadenceMovesOnlyEvery2000Milliseconds() = exerciseCadence(2000L)

    @Test fun longestCadenceMovesOnlyEvery10000Milliseconds() = exerciseCadence(LocationUpdateInterval.MAX_MS)

    @Test fun maximumCadencePicksUpPushedStopWithinOneSecondWithoutGeneratingEarlyFixes() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cadence_control", Context.MODE_PRIVATE)
        val interval = LocationUpdateInterval.MAX_MS
        val moving = HookSettings(isStarted = true, locationUpdateIntervalMs = interval, revision = 1)
        val stopped = moving.copy(isStarted = false, revision = 2)
        val file = moving.encode()
        moving.publish(prefs)
        val system = Xshare(prefs) { file.inputStream() }
        val cadence = SystemFixCadence()
        val attempts = mutableListOf<Long>()
        var settings = moving
        var lastGpsReportMs = 0L
        fun step(nowMs: Long) {
            settings = system.refresh(nowMs) ?: settings
            if (settings.isStarted) cadence.generateIfDue(nowMs, lastGpsReportMs, settings) { attempts += nowMs }
        }

        for (time in 0L until interval step 1000L) {
            step(time)
            assertEquals(time + 1000L, system.nextReadMs)
            assertEquals(interval, cadence.nextFixMs(lastGpsReportMs, settings))
            assertTrue(attempts.isEmpty())
        }
        step(interval - 1L)
        assertTrue(attempts.isEmpty())
        step(interval)
        assertEquals(listOf(interval), attempts)
        assertEquals(2L * interval, cadence.nextFixMs(lastGpsReportMs, settings))

        lastGpsReportMs = interval + 500L
        assertEquals(lastGpsReportMs + interval, cadence.nextFixMs(lastGpsReportMs, settings))
        stopped.publish(prefs) // Stop just after a poll, with the old file still in place.
        step(interval + 999L)
        assertEquals(moving, settings)
        step(interval + 1000L)
        assertEquals(stopped, settings)
        step(lastGpsReportMs + interval)
        assertEquals(listOf(interval), attempts)
    }

    @Test fun maximumCadencePicksUpPauseAndFasterIntervalAtTheNextPoll() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cadence_control", Context.MODE_PRIVATE)
        val moving = HookSettings(isStarted = true, speed = 10f, locationUpdateIntervalMs = 10_000L, revision = 1)
        val paused = moving.copy(speed = 0f, revision = 2)
        val faster = moving.copy(locationUpdateIntervalMs = 200L, revision = 3)
        var file = moving.encode()
        moving.publish(prefs)
        val system = Xshare(prefs) { file.inputStream() }
        assertEquals(moving, system.refresh(0))

        // A Pause keeps spoofing the held position, with zero speed.
        file = paused.encode()
        assertNull(system.refresh(999L))
        assertEquals(paused, system.refresh(1000L))

        faster.publish(prefs)
        assertNull(system.refresh(1999L))
        val updated = system.refresh(2000L)!!
        assertEquals(faster, updated)
        assertEquals(2200L, system.nextReadMs)
        assertEquals(2050L, SystemFixCadence().nextFixMs(lastGpsReportMs = 1850L, settings = updated))
    }

    private fun exerciseCadence(intervalMs: Long) {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        val remote = RemotePreferenceEndpoint(context)
        fun deliver() {
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun assertInterval() {
            assertEquals(intervalMs, PrefManager.locationUpdateIntervalMs)
            assertEquals(intervalMs, RoutePlaybackService.gpxState.value!!.updateIntervalMs)
            assertEquals(intervalMs, remote.hook.locationUpdateIntervalMs)
            assertEquals(intervalMs, remote.preferences.getLong(LocationUpdateInterval.KEY, -1L))
        }
        try {
            RoutePlaybackService.start(context, longRoute, 10f, false, gpx = true, updateIntervalMs = intervalMs)
            deliver()
            assertInterval()
            // Warm up the four-second acceleration ramp and end on a tick boundary.
            advance((4000L + intervalMs - 1L) / intervalMs * intervalMs)
            repeat(3) {
                val before = fix()
                advance(intervalMs - 1)
                assertEquals(before, fix())
                advance(1)
                assertEquals(10.0 * intervalMs / 1000.0, RouteMath.pathLength(listOf(before, fix())), 0.01)
                assertEquals(PrefManager.getLng, remote.hook.longitude, 0.0)
                assertInterval()
            }

            RoutePlaybackService.setPaused(context, true)
            deliver()
            val held = fix()
            advance(intervalMs * 3)
            assertEquals(held, fix())
            assertEquals(0f, PrefManager.speed)
            assertTrue(RoutePlaybackService.gpxState.value!!.paused)
            assertInterval()

            RoutePlaybackService.setSpeed(context, 72)
            deliver()
            advance(intervalMs * 2)
            assertEquals(held, fix())
            assertEquals(0f, PrefManager.speed)
            assertEquals(20f, RoutePlaybackService.gpxState.value!!.speedMps)
            assertInterval()

            RoutePlaybackService.setPaused(context, false)
            deliver()
            advance(intervalMs - 1)
            assertEquals(held, fix())
            advance(1)
            assertTrue(RouteMath.pathLength(listOf(held, fix())) > 0.0)
            assertInterval()
            val resumed = fix()
            advance(intervalMs - 1)
            assertEquals(resumed, fix())
            advance(1)
            assertTrue(RouteMath.pathLength(listOf(resumed, fix())) > 0.0)

            RoutePlaybackService.stop(context)
            deliver()
            val stopped = fix()
            assertNull(RoutePlaybackService.gpxState.value)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, PrefManager.locationUpdateIntervalMs)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, remote.hook.locationUpdateIntervalMs)
            advance(intervalMs * 3)
            assertEquals(stopped, fix())
            assertTrue(PrefManager.isStarted)
        } finally {
            controller.destroy()
            remote.close()
        }
    }

    @Test fun completionRestoresDefaultInAppAndHookWhileHoldingEndpoint() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        val remote = RemotePreferenceEndpoint(context)
        val route = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 0.00001))
        try {
            RoutePlaybackService.start(context, route, 10f, false, gpx = true, updateIntervalMs = 200L)
            controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(200L, remote.hook.locationUpdateIntervalMs)
            advance(2000)
            assertNull(RoutePlaybackService.gpxState.value)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, PrefManager.locationUpdateIntervalMs)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, remote.hook.locationUpdateIntervalMs)
            assertEquals(route.last().lng, PrefManager.getLng, 0.0)
            assertEquals(0f, PrefManager.speed)
            assertTrue(PrefManager.isStarted)
            advance(4000)
            assertEquals(route.last().lng, PrefManager.getLng, 0.0)
        } finally {
            controller.destroy()
            remote.close()
        }
    }

    @Test fun destroyingActivePlaybackRestoresDefaultCadence() {
        val context: Context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        val remote = RemotePreferenceEndpoint(context)
        try {
            try {
                RoutePlaybackService.start(context, longRoute, 10f, false, gpx = true, updateIntervalMs = 2000L)
                controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2000L, remote.hook.locationUpdateIntervalMs)
            } finally {
                controller.destroy()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(RoutePlaybackService.gpxState.value)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, PrefManager.locationUpdateIntervalMs)
            assertEquals(LocationUpdateInterval.DEFAULT_MS, remote.hook.locationUpdateIntervalMs)
        } finally {
            remote.close()
        }
    }

    @Test fun cadenceDoesNotChangeTravelledDistanceAcrossAccelerationAndCruising() {
        val context: Context = RuntimeEnvironment.getApplication()
        fun distanceAfterEightSeconds(intervalMs: Long): Double {
            val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
            try {
                RoutePlaybackService.start(context, longRoute, 10f, false, gpx = true, updateIntervalMs = intervalMs)
                controller.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
                shadowOf(Looper.getMainLooper()).idle()
                advance(8000)
                return RouteMath.pathLength(listOf(longRoute.first(), fix()))
            } finally {
                controller.destroy()
            }
        }
        val fastDistance = distanceAfterEightSeconds(200L)
        val slowDistance = distanceAfterEightSeconds(2000L)
        // Four seconds accelerating from 0 to 10m/s (20m), then four cruising (40m).
        assertEquals(60.0, fastDistance, 0.01)
        assertEquals(60.0, slowDistance, 0.01)
        assertEquals(fastDistance, slowDistance, 0.00001)
    }

    private fun advance(milliseconds: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))

    private fun fix() = RoutePoint(PrefManager.getLat, PrefManager.getLng, PrefManager.altitude)

    private val longRoute = listOf(RoutePoint(0.0, 0.0, 100.0), RoutePoint(0.0, 1.0, 200.0))

    // Inspect both transports after a route edit. Fresh readers inspect the current publication
    // independently of the poll deadline, which is exercised by XshareTest.
    private class RemotePreferenceEndpoint(context: Context) : AutoCloseable {
        val preferences: SharedPreferences = context.getSharedPreferences("cadence_remote", Context.MODE_PRIVATE)
        private lateinit var file: ByteArray
        val hook get() = Xshare(preferences).refresh(0)!!.also { pushed ->
            assertEquals(pushed, HookSettings.decode(file))
        }

        init {
            preferences.edit().clear().commit()
            PrefManager.bindRemote(preferences, { it.run() }) { file = it }
        }

        override fun close() {
            PrefManager.onServiceDied()
        }
    }
}
