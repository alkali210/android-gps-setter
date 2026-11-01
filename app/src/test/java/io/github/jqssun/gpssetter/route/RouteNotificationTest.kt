package io.github.jqssun.gpssetter.route

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
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
@Config(sdk = [28], qualifiers = "en-rUS")
class RouteNotificationTest {
    @Test fun distanceAdvancesBeforeOnePercentAndHoldsWhilePaused() {
        val context = RuntimeEnvironment.getApplication()
        val controller = Robolectric.buildService(RoutePlaybackService::class.java).create()
        val notifications = shadowOf(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        fun deliver() {
            controller.get().onStartCommand(shadowOf(context).nextStartedService, 0, 1)
            shadowOf(Looper.getMainLooper()).idle()
        }
        fun text() = notifications.allNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
        try {
            RoutePlaybackService.start(context, listOf(RoutePoint(0.0, 0.0), RoutePoint(0.0, 1.0)), 10f, false, gpx = true)
            deliver()
            assertEquals("0 m / 111.2 km", text())
            advance(4)
            assertEquals("20 m / 111.2 km", text())
            RoutePlaybackService.setPaused(context, true)
            deliver()
            advance(30)
            assertEquals("20 m / 111.2 km", text())
            RoutePlaybackService.setSpeed(context, 72)
            deliver()
            assertEquals("20 m / 111.2 km", text())
            RoutePlaybackService.setPaused(context, false)
            deliver()
            advance(4)
            assertEquals("60 m / 111.2 km", text())
            advance(50)
            assertEquals("1.1 km / 111.2 km", text())
        } finally {
            controller.destroy()
        }
    }
}
