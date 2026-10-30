package io.github.jqssun.gpssetter.ui

import android.os.Looper
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import io.github.jqssun.gpssetter.R
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
import org.robolectric.shadows.ShadowDialog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GpxSpeedInputTest {
    @Test fun editingSpeedWhileMovingPreservesProgress() = exerciseInput(false)
    @Test fun editingSpeedWhilePausedKeepsPositionAndPauseState() = exerciseInput(true)

    private fun exerciseInput(paused: Boolean) {
        val activity = Robolectric.buildActivity(MapActivity::class.java).setup()
        val service = Robolectric.buildService(RoutePlaybackService::class.java).create()
        fun deliver() {
            service.get().onStartCommand(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            val home = activity.get()
            RoutePlaybackService.start(home, listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), 10f, false, gpx = true)
            deliver()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            if (paused) {
                home.findViewById<View>(R.id.stop_button).performClick()
                deliver()
            }
            val lng = PrefManager.getLng
            home.findViewById<View>(R.id.gpx_speed_value).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            val input = dialog.currentFocus as EditText
            input.setText("0")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(dialog.isShowing)
            assertEquals(10f, RoutePlaybackService.gpxState.value!!.speedMps)
            input.setText("73")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertFalse(dialog.isShowing)
            deliver()
            assertEquals(73f / 3.6f, RoutePlaybackService.gpxState.value!!.speedMps, 0.00001f)
            assertEquals(paused, RoutePlaybackService.gpxState.value!!.paused)
            assertEquals(lng, PrefManager.getLng, 0.0)

            home.findViewById<View>(R.id.gpx_speed_plus).performClick()
            deliver()
            assertEquals(78f / 3.6f, RoutePlaybackService.gpxState.value!!.speedMps, 0.00001f)
            home.findViewById<View>(R.id.gpx_speed_minus).performClick()
            deliver()
            assertEquals(73f / 3.6f, RoutePlaybackService.gpxState.value!!.speedMps, 0.00001f)

            home.findViewById<View>(R.id.gpx_speed_value).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val cancelled = ShadowDialog.getLatestDialog() as AlertDialog
            (cancelled.currentFocus as EditText).setText("99")
            cancelled.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            assertEquals(73f / 3.6f, RoutePlaybackService.gpxState.value!!.speedMps, 0.00001f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            if (paused) {
                assertEquals(lng, PrefManager.getLng, 0.0)
                assertEquals(0f, PrefManager.speed)
            } else {
                assertTrue(PrefManager.getLng > lng)
            }
            assertTrue(PrefManager.isStarted)
        } finally {
            service.destroy()
            activity.pause().stop().destroy()
        }
    }
}
