package io.github.jqssun.gpssetter.ui

import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.route.RoutePoint
import io.github.jqssun.gpssetter.utils.EXTRA_ASK_LOCATION
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationGuardTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun setLocationOn(enabled: Boolean) =
        shadowOf(app.getSystemService(LocationManager::class.java)!!).setLocationEnabled(enabled)

    private fun prompts() = ShadowDialog.getShownDialogs().filterIsInstance<AlertDialog>().filter {
        it.isShowing && it.getButton(AlertDialog.BUTTON_POSITIVE)?.text == app.getString(R.string.turn_on_location)
    }

    @Test fun teleportAndRouteStartLeaveStateUntouchedWhenLocationIsOff() {
        setLocationOn(false)
        Robolectric.buildActivity(MapActivity::class.java).setup().use { controller ->
            val home = controller.get()
            val start = home.findViewById<View>(R.id.start_button)
            start.performClick()
            assertFalse(PrefManager.isStarted)
            assertEquals(View.VISIBLE, start.visibility)
            prompts().single().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(home).nextStartedActivity)

            home.findViewById<View>(R.id.mode_route).performClick()
            // Supply the result of routing without contacting a real routing provider.
            BaseMapActivity::class.java.getDeclaredField("roadPoints").apply { isAccessible = true }
                .set(home, listOf(RoutePoint(1.0, 2.0), RoutePoint(1.1, 2.1)))
            val routeStart = home.findViewById<View>(R.id.route_start).apply { isEnabled = true }
            routeStart.performClick()
            assertNull(shadowOf(app).nextStartedService)
            assertEquals(View.VISIBLE, home.findViewById<View>(R.id.route_setup).visibility)
            assertEquals(View.GONE, home.findViewById<View>(R.id.route_travel).visibility)
            prompts().single().dismiss()

            setLocationOn(true)
            routeStart.performClick()
            assertNotNull(shadowOf(app).nextStartedService)
            assertEquals(View.VISIBLE, home.findViewById<View>(R.id.route_travel).visibility)
            setLocationOn(false)
            home.findViewById<View>(R.id.route_stop).performClick()
            assertNotNull(shadowOf(app).nextStartedService)
            assertTrue(prompts().isEmpty())
        }
    }

    @Test fun coldAndWarmTileLaunchesPromptOnceAndRecheckLocation() {
        setLocationOn(false)
        val intent = Intent(app, MapActivity::class.java).putExtra(EXTRA_ASK_LOCATION, true)
        Robolectric.buildActivity(MapActivity::class.java, intent).setup().use { controller ->
            prompts().single().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(controller.get()).nextStartedActivity.action)
            controller.recreate()
            assertTrue(prompts().isEmpty())
            controller.newIntent(intent)
            prompts().single().dismiss()
            setLocationOn(true)
            controller.newIntent(intent)
            assertTrue(prompts().isEmpty())
            assertFalse(PrefManager.isStarted)
        }
    }
}
