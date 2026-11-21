package io.github.jqssun.gpssetter.xposed

import android.content.Context
import io.github.jqssun.gpssetter.utils.PrefManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class XshareTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("pushed", Context.MODE_PRIVATE)
    private val route = HookSettings(
        isStarted = true, latitude = -33.123456789, longitude = 157.000001234,
        altitude = -2.32, speed = 13.3f, bearing = 270.5f, accuracy = 4.5f,
        isRandomPosition = true, locationUpdateIntervalMs = 100,
    )

    @Test fun transitionsFromPushedToFileAndRetainsEntireLastGoodFixUntilNextCompleteFile() {
        val pushed = HookSettings(latitude = 12.5)
        pushed.publish(prefs)
        var file: ByteArray? = null
        val system = Xshare(prefs) { file?.inputStream() ?: throw FileNotFoundException() }
        assertEquals(pushed, system.refresh(0))
        file = route.encode()
        assertEquals(route, system.refresh(1000))
        val stopped = route.copy(isStarted = false, latitude = 42.0, speed = 0f, bearing = -1f)
        val text = stopped.encode().toString(Charsets.US_ASCII)
        val broken = listOf(null, "", text.substringBefore("end=1"), text + "start=false\n",
            text.replace("altitude_d=${stopped.altitude.toRawBits()}\n", ""), text.replace("speed=0.0", "speed=bad"))
        var now = 1000L
        for (bad in broken) {
            file = bad?.toByteArray()
            now += 100
            assertEquals(route, system.refresh(now))
        }
        file = stopped.encode()
        assertEquals(stopped, system.refresh(now + 100))
        file = route.encode()
        assertEquals(route, system.refresh(now + 200))
    }

    @Test fun readsOnceAtTheConfiguredCadenceAndKeepsCheckingWhileIdle() {
        var opens = 0
        var snapshot = route
        val system = Xshare(prefs) { opens++; snapshot.encode().inputStream() }
        var now = 0L
        for (interval in listOf(100L, 200L, 10_000L)) {
            repeat(3) {
                snapshot = snapshot.copy(latitude = snapshot.latitude + 0.00001, locationUpdateIntervalMs = interval)
                val before = opens
                assertEquals(snapshot, system.refresh(now))
                assertNull(system.refresh(now))
                assertNull(system.refresh(now + interval - 1))
                assertEquals(before + 1, opens)
                now += interval
            }
        }
        snapshot = snapshot.copy(isStarted = false)
        assertEquals(snapshot, system.refresh(now))
        snapshot = route
        assertNull(system.refresh(now + 999))
        assertEquals(route, system.refresh(now + 1000))
    }

    @Test fun scopedReaderUsesOnlyPushedSettingsAtDeliveryBoundaries() {
        val scoped = Xshare(prefs)
        route.publish(prefs)
        assertEquals(route, scoped.refresh(0, 80))
        val stopped = route.copy(isStarted = false)
        stopped.publish(prefs)
        assertNull(scoped.refresh(79, 80))
        assertEquals(stopped, scoped.refresh(80, 80))
    }

    @Test fun multiKeyMotionIsOnePublicationAndQueuedEditsUseTheLatestState() {
        val work = ArrayDeque<Runnable>()
        var writes = 0
        lateinit var bytes: ByteArray
        PrefManager.bindRemote(prefs, { work.addLast(it) }) { writes++; bytes = it }
        try {
            work.removeFirst().run() // initial bind
            PrefManager.updateMotion(2.0, 3.0, 200.0, 15f, 90f)
            assertEquals(1, work.size)
            work.removeFirst().run()
            assertEquals(2, writes)
            PrefManager.locationUpdateIntervalMs = 200
            PrefManager.updateMotion(4.0, 5.0, -80.0, 25f, 180f)
            assertEquals(1, work.size)
            work.removeFirst().run()
            val expected = HookSettings(isStarted = true, latitude = 4.0, longitude = 5.0,
                altitude = -80.0, speed = 25f, bearing = 180f, locationUpdateIntervalMs = 200)
            assertEquals(expected, HookSettings.decode(bytes))
            assertEquals(expected, Xshare(prefs).refresh(0))
            assertEquals(3, writes)
            PrefManager.mapType = 2
            assertEquals(0, work.size)
            PrefManager.update(false, 4.0, 5.0)
            work.removeFirst().run()
            assertEquals(expected.copy(isStarted = false, altitude = 0.0, speed = 0f, bearing = -1f,
                locationUpdateIntervalMs = LocationUpdateInterval.DEFAULT_MS), HookSettings.decode(bytes))
        } finally {
            PrefManager.onServiceDied()
        }
    }
}
