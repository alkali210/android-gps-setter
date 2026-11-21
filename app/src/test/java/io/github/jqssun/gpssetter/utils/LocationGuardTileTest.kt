package io.github.jqssun.gpssetter.utils

import android.app.PendingIntent
import android.content.Intent
import android.location.LocationManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.ui.MapActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTileService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationGuardTileTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before @After fun resetSpoof() {
        PrefManager.update(false, 12.0, 34.0)
    }

    private fun setLocationOn(enabled: Boolean) =
        shadowOf(app.getSystemService(LocationManager::class.java)!!).setLocationEnabled(enabled)

    private fun assertMapHandoff(intent: Intent) {
        assertEquals(MapActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(EXTRA_ASK_LOCATION, false))
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        assertEquals(flags, intent.flags and flags)
    }

    @Test fun spoofTileGuardsOnlyStarts() {
        val tile = Robolectric.buildService(SpoofTileService::class.java).create().get()
        setLocationOn(false)
        tile.onClick()
        assertFalse(PrefManager.isStarted)
        assertMapHandoff(shadowOf(app).nextStartedActivity)

        setLocationOn(true)
        tile.onClick()
        assertTrue(PrefManager.isStarted)
        setLocationOn(false)
        tile.onClick()
        assertFalse(PrefManager.isStarted)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun onlyPopulatedFavoriteOpensTheMapAndDoesNotOverwriteLocation() {
        val tile = Robolectric.buildService(FavoriteTileService1::class.java).create().get()
        setLocationOn(false)
        tile.onClick()
        assertEquals(Tile.STATE_UNAVAILABLE, tile.qsTile?.state)
        assertNull(shadowOf(app).nextStartedActivity)

        val latBefore = PrefManager.getLat
        val lngBefore = PrefManager.getLng
        runBlocking { tile.favorites.addNewFavorite(Favorite(1L, "Work", 1.25, 2.5)) }
        tile.onClick()
        assertFalse(PrefManager.isStarted)
        assertEquals(latBefore, PrefManager.getLat, 0.0)
        assertEquals(lngBefore, PrefManager.getLng, 0.0)
        assertMapHandoff(shadowOf(app).nextStartedActivity)

        setLocationOn(true)
        tile.onClick()
        assertTrue(PrefManager.isStarted)
        assertEquals(1.25, PrefManager.getLat, 0.0)
        assertEquals(2.5, PrefManager.getLng, 0.0)
        assertEquals(Tile.STATE_ACTIVE, tile.qsTile?.state)
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test @Config(sdk = [35], shadows = [PendingIntentTileShadow::class])
    fun modernTileHandsAnImmutableActivityPendingIntentToSystemUi() {
        val tile = Robolectric.buildService(SpoofTileService::class.java).create().get()
        setLocationOn(false)
        tile.onClick()
        assertFalse(PrefManager.isStarted)
        val pending = Shadow.extract<PendingIntentTileShadow>(tile).handoff
        assertTrue(pending.isImmutable)
        assertTrue(pending.isActivity)
        assertMapHandoff(shadowOf(pending).savedIntent)
    }

    // Robolectric 4.14 shadows the legacy Intent overload but not the API 34 PendingIntent overload.
    @Implements(TileService::class)
    class PendingIntentTileShadow : ShadowTileService() {
        lateinit var handoff: PendingIntent

        @Implementation(minSdk = 34)
        protected fun startActivityAndCollapse(intent: PendingIntent) {
            handoff = intent
        }
    }
}
