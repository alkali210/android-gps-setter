package io.github.jqssun.gpssetter.xposed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

// one degree of latitude is ~111320 m everywhere
private const val METERS_PER_DEG = 111319.49

class GeoOffsetTest {
    @Test fun latOffsetIsLatitudeIndependent() {
        assertEquals(1.0, GeoOffset.latOffsetDegrees(METERS_PER_DEG), 1e-4)
        assertEquals(0.0, GeoOffset.latOffsetDegrees(0.0), 1e-9)
        assertEquals(-0.5, GeoOffset.latOffsetDegrees(-METERS_PER_DEG / 2), 1e-4)
    }

    @Test fun lngOffsetScalesWithLatitude() {
        // at the equator a degree of longitude spans the same metres as latitude
        assertEquals(1.0, GeoOffset.lngOffsetDegrees(METERS_PER_DEG, 0.0), 1e-4)
        // at 60 deg, cos(60)=0.5, so the same metres cover twice the longitude
        assertEquals(2.0, GeoOffset.lngOffsetDegrees(METERS_PER_DEG, 60.0), 1e-3)
    }

    @Test fun randomPositionKeepsItsOffsetBetweenFixesAndAppliesControlChangesImmediately() {
        val samples = listOf(30, 35, 40, 45).iterator()
        val randomPosition = RandomPosition(object : Random() {
            override fun nextInt(bound: Int): Int = samples.next()
        })
        val interval = 1250L
        val moving = HookSettings(isStarted = true, isRandomPosition = true, speed = 10f)
        val firstFix = randomPosition.apply(moving, 0, interval)

        val paused = moving.copy(speed = 0f)
        assertEquals(firstFix.copy(speed = 0f), randomPosition.apply(paused, 1000L, interval))
        assertEquals(interval, randomPosition.nextSampleMs)

        val nextFix = randomPosition.apply(paused, interval, interval)
        assertEquals(paused.latitude + GeoOffset.latOffsetDegrees(15.0), nextFix.latitude, 0.0)
        assertEquals(paused.longitude + GeoOffset.lngOffsetDegrees(20.0, paused.latitude), nextFix.longitude, 0.0)
        assertEquals(interval * 2, randomPosition.nextSampleMs)

        val stopped = paused.copy(isStarted = false)
        assertEquals(stopped, randomPosition.apply(stopped, 2000L, interval))
        assertEquals(Long.MAX_VALUE, randomPosition.nextSampleMs)
    }
}
