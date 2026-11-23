package io.github.jqssun.gpssetter.utils

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dagger.hilt.android.AndroidEntryPoint
import io.github.jqssun.gpssetter.R
import io.github.jqssun.gpssetter.repository.FavoriteRepository
import io.github.jqssun.gpssetter.room.Favorite
import io.github.jqssun.gpssetter.utils.ext.isLocationOn
import javax.inject.Inject

// One-tap QS tile that spoofs to a saved favorite. Android requires static tile declaration, so
// there is a fixed set (slots 0..N) each mapping to the Nth favorite; subclasses pick the slot.
abstract class FavoriteTileService : TileService() {

    @Inject lateinit var favorites: FavoriteRepository
    protected abstract val index: Int

    // DB read is synchronous (allowMainThreadQueries); the table is tiny
    private fun favoriteAt(): Favorite? = favorites.getAllSync().getOrNull(index)

    override fun onStartListening() = refresh()

    override fun onClick() {
        val fav = favoriteAt()
        val lat = fav?.lat
        val lng = fav?.lng
        if (lat != null && lng != null) {
            if (!isLocationOn()) return openAppToTurnOnLocation()
            PrefManager.update(true, lat, lng)
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val fav = favoriteAt()
        if (fav?.lat == null || fav.lng == null) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.favorite_tile_default, index + 1)
        } else {
            val active = PrefManager.isStarted &&
                PrefManager.getLat == fav.lat && PrefManager.getLng == fav.lng
            tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.label = fav.address?.takeIf { it.isNotBlank() }
                ?: getString(R.string.favorite_tile_default, index + 1)
        }
        tile.updateTile()
    }
}

@AndroidEntryPoint class FavoriteTileService1 : FavoriteTileService() { override val index = 0 }
@AndroidEntryPoint class FavoriteTileService2 : FavoriteTileService() { override val index = 1 }
@AndroidEntryPoint class FavoriteTileService3 : FavoriteTileService() { override val index = 2 }
