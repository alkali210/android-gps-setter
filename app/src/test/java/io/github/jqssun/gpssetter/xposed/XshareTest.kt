package io.github.jqssun.gpssetter.xposed

import android.content.Context
import android.content.SharedPreferences
import io.github.jqssun.gpssetter.utils.PrefManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

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

    @Test fun newerPushedStopWinsAgainstStaleMissingOrTruncatedFile() {
        val moving = route.copy(revision = 1)
        val stopped = moving.copy(isStarted = false, speed = 0f, bearing = -1f, revision = 2)
        val stale = moving.encode()
        val newer = moving.copy(revision = 3).encode()
        val truncated = newer.copyOf(newer.size - 1)
        var file: ByteArray? = stale
        val system = Xshare(prefs) { file?.inputStream() ?: throw FileNotFoundException() }
        moving.publish(prefs)
        assertEquals(moving, system.refresh(0))

        stopped.publish(prefs)
        for ((index, candidate) in listOf(stale, null, truncated).withIndex()) {
            file = candidate
            assertEquals(stopped, system.refresh(100L * (index + 1)))
            // The order also holds if system_server has not cached either transport yet.
            assertEquals(stopped, Xshare(prefs) { file?.inputStream() ?: throw FileNotFoundException() }.refresh(0))
        }

        // Once Stop was accepted, even loss or rollback of the pushed copy can't resume spoofing.
        moving.publish(prefs)
        file = stale
        assertEquals(stopped, system.refresh(400))
        prefs.edit().clear().apply()
        file = null
        assertEquals(stopped, system.refresh(500))
    }

    @Test fun newerCompleteFileWinsAndPartialPushedSnapshotCannotSupersedeIt() {
        val moving = route.copy(revision = 1)
        val stopped = moving.copy(isStarted = false, speed = 0f, revision = 2)
        moving.publish(prefs)
        var file = stopped.encode()
        val system = Xshare(prefs) { file.inputStream() }
        assertEquals(stopped, system.refresh(0))

        val restarted = moving.copy(revision = 3)
        restarted.publish(prefs)
        prefs.edit().remove(HookSettings.SPEED).apply()
        file = moving.encode()
        assertEquals(stopped, system.refresh(100))
        assertEquals(moving, Xshare(prefs) { file.inputStream() }.refresh(0))

        restarted.publish(prefs)
        assertEquals(restarted, system.refresh(200))
    }

    @Test fun legacyPushedCoordinatesStillWorkWithoutAFile() {
        prefs.edit().putBoolean(HookSettings.START, true).putFloat("latitude", 12.5f).putFloat("longitude", -32f).apply()
        val system = Xshare(prefs) { throw FileNotFoundException() }
        assertEquals(HookSettings(isStarted = true, latitude = 12.5, longitude = -32.0), system.refresh(0))
    }

    @Test fun pollsAtTheConfiguredCadenceUpToOneSecondAndKeepsCheckingWhileIdle() {
        var opens = 0
        var snapshot = route
        val system = Xshare(prefs) { opens++; snapshot.encode().inputStream() }
        var now = 0L
        for (interval in listOf(100L, 200L, 10_000L)) {
            val controlInterval = minOf(interval, 1000L)
            repeat(3) {
                snapshot = snapshot.copy(latitude = snapshot.latitude + 0.00001, locationUpdateIntervalMs = interval)
                val before = opens
                assertEquals(snapshot, system.refresh(now))
                assertNull(system.refresh(now))
                assertNull(system.refresh(now + controlInterval - 1))
                assertEquals(before + 1, opens)
                now += controlInterval
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
            val initial = HookSettings.decode(bytes)!!
            assertTrue(initial.revision > 0)
            PrefManager.updateMotion(2.0, 3.0, 200.0, 15f, 90f)
            assertEquals(1, work.size)
            work.removeFirst().run()
            assertEquals(2, writes)
            val firstMotion = HookSettings.decode(bytes)!!
            assertTrue(firstMotion.revision > initial.revision)
            PrefManager.locationUpdateIntervalMs = 200
            PrefManager.updateMotion(4.0, 5.0, -80.0, 25f, 180f)
            assertEquals(1, work.size)
            work.removeFirst().run()
            val decoded = HookSettings.decode(bytes)!!
            assertTrue(decoded.revision > firstMotion.revision)
            val expected = HookSettings(isStarted = true, latitude = 4.0, longitude = 5.0,
                altitude = -80.0, speed = 25f, bearing = 180f, locationUpdateIntervalMs = 200, revision = decoded.revision)
            assertEquals(expected, HookSettings.decode(bytes))
            assertEquals(expected, Xshare(prefs).refresh(0))
            assertEquals(3, writes)
            PrefManager.mapType = 2
            assertEquals(0, work.size)
            PrefManager.update(false, 4.0, 5.0)
            work.removeFirst().run()
            val stopped = HookSettings.decode(bytes)!!
            assertTrue(stopped.revision > decoded.revision)
            assertEquals(expected.copy(isStarted = false, altitude = 0.0, speed = 0f, bearing = -1f,
                locationUpdateIntervalMs = LocationUpdateInterval.DEFAULT_MS, revision = stopped.revision), stopped)
        } finally {
            PrefManager.onServiceDied()
        }
    }

    @Test fun newBindingPublishesNewerStopEvenIfOldFileCannotBeReplaced() {
        lateinit var file: ByteArray
        PrefManager.bindRemote(prefs, { it.run() }) { file = it }
        try {
            PrefManager.updateMotion(4.0, 5.0, -80.0, 25f, 180f)
            val moving = HookSettings.decode(file)!!
            PrefManager.onServiceDied()
            // A new publisher reserves its own range from the persisted high-water mark.
            PrefManager.bindRemote(prefs, { it.run() }) { throw FileNotFoundException() }
            PrefManager.update(false, 4.0, 5.0)
            val stopped = Xshare(prefs) { file.inputStream() }.refresh(0)!!
            assertTrue(stopped.revision > moving.revision)
            assertEquals(moving.copy(isStarted = false, altitude = 0.0, speed = 0f, bearing = -1f,
                locationUpdateIntervalMs = LocationUpdateInterval.DEFAULT_MS, revision = stopped.revision), stopped)
            assertEquals(moving, HookSettings.decode(file))
        } finally {
            PrefManager.onServiceDied()
        }
    }

    @Test fun clearingLocalDataOrReinstallingWithoutRebootStillSupersedesCachedSystemRevision() {
        val local = PrefManager::class.java.getDeclaredMethod("getPref").apply { isAccessible = true }
            .invoke(PrefManager) as SharedPreferences
        lateinit var file: ByteArray
        PrefManager.bindRemote(prefs, { it.run() }) { file = it }
        try {
            PrefManager.updateMotion(4.0, 5.0, -80.0, 25f, 180f)
            val system = Xshare(prefs) { file.inputStream() }
            val moving = system.refresh(0)!!
            assertTrue(moving.isStarted)

            // Clear app-private storage while remote preferences survive.
            PrefManager.onServiceDied()
            local.edit().clear().commit()
            PrefManager.bindRemote(prefs, { it.run() }) { file = it }
            val afterClear = system.refresh(1000)!!
            assertTrue(afterClear.revision > moving.revision)
            assertFalse(afterClear.isStarted)

            // Wipe both local and remote storage (reinstall without reboot) as uptime advances.
            PrefManager.updateMotion(6.0, 7.0, 10.0, 10f, 45f)
            val movingAgain = system.refresh(2000)!!
            assertTrue(movingAgain.isStarted)
            PrefManager.onServiceDied()
            local.edit().clear().commit()
            prefs.edit().clear().commit()
            org.robolectric.shadows.ShadowSystemClock.advanceBy(1, TimeUnit.SECONDS)
            PrefManager.bindRemote(prefs, { it.run() }) { file = it }
            val afterReinstall = system.refresh(3000)!!
            assertTrue(afterReinstall.revision > movingAgain.revision)
            assertFalse(afterReinstall.isStarted)
        } finally {
            PrefManager.onServiceDied()
        }
    }
}
