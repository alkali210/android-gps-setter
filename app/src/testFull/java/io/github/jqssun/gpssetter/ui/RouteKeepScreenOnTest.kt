package io.github.jqssun.gpssetter.ui

import android.os.Looper
import android.view.WindowManager
import io.github.jqssun.gpssetter.route.RoutePlaybackService
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.utils.PrefManager
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
class RouteKeepScreenOnTest {
    @Test fun homeRouteKeepsScreenOnUntilStopped() = exercisePlayback(gpx = false)
    @Test fun gpxKeepsScreenOnUntilStopped() = exercisePlayback(gpx = true)
    @Test fun homeRouteCompletionReleasesScreen() = exercisePlayback(gpx = false, complete = true)
    @Test fun gpxCompletionReleasesScreen() = exercisePlayback(gpx = true, complete = true)

    private fun exercisePlayback(gpx: Boolean, complete: Boolean = false) {
        val store = ActivitySettings.SettingPreferenceDataStore()
        assertFalse(store.getBoolean("keep_screen_on_during_route", false))
        val activity = Robolectric.buildActivity(MapActivity::class.java).setup()
        val service = Robolectric.buildService(RoutePlaybackService::class.java).create()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun deliver() {
            service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            idle()
        }
        fun assertScreenOn(expected: Boolean) {
            assertEquals(expected, activity.get().window.attributes.flags and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        }
        try {
            RoutePlaybackService.start(activity.get(), listOf(RoutePoint(0.0, 0.0),
                RoutePoint(0.0, if (complete) 0.00001 else 1.0)), 10f, false, gpx = gpx)
            deliver()
            assertTrue(RoutePlaybackService.isPlaying.value)
            assertScreenOn(false)

            // Apply the switch through the same store as Settings, then return to the map.
            activity.pause().stop()
            store.putBoolean("keep_screen_on_during_route", true)
            assertTrue(store.getBoolean("keep_screen_on_during_route", false))
            activity.start().resume().visible()
            idle()
            assertScreenOn(true)

            if (complete) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            } else {
                if (gpx) {
                    RoutePlaybackService.setPaused(activity.get(), true)
                    deliver()
                    assertScreenOn(false)
                    assertFalse(RoutePlaybackService.isPlaying.value)
                    RoutePlaybackService.setPaused(activity.get(), false)
                    deliver()
                    assertScreenOn(true)
                }
                activity.recreate()
                idle()
                assertScreenOn(true)
                activity.pause().stop()
                assertScreenOn(false)
                activity.start().resume().visible()
                idle()
                assertScreenOn(true)

                activity.pause().stop()
                store.putBoolean("keep_screen_on_during_route", false)
                activity.start().resume().visible()
                idle()
                assertScreenOn(false)
                activity.pause().stop()
                store.putBoolean("keep_screen_on_during_route", true)
                activity.start().resume().visible()
                idle()
                assertScreenOn(true)

                RoutePlaybackService.stop(activity.get())
                deliver()
            }
            assertFalse(RoutePlaybackService.isPlaying.value)
            assertScreenOn(false)
            // Static spoofing remains active, but must no longer keep the screen on.
            assertTrue(PrefManager.isStarted)
        } finally {
            service.destroy()
            activity.pause().stop().destroy()
            PrefManager.keepScreenOnDuringRoute = false
        }
    }
}
