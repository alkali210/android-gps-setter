package io.github.jqssun.gpssetter.utils

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.jqssun.gpssetter.ui.MapActivity
import io.github.jqssun.gpssetter.utils.ext.isLocationOn

// Quick Settings tile: toggle spoofing to the last-set location without opening the app.
class SpoofTileService : TileService() {

    override fun onStartListening() = updateTile(PrefManager.isStarted)

    override fun onClick() {
        val newStarted = !PrefManager.isStarted
        if (newStarted && !isLocationOn()) return openAppToTurnOnLocation()
        PrefManager.update(newStarted, PrefManager.getLat, PrefManager.getLng)
        if (!newStarted) NotificationsChannel().cancelAllNotifications(this)
        // use the intended state: PrefManager.update writes asynchronously
        updateTile(newStarted)
    }

    private fun updateTile(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (on) "On" else "Off"
        }
        tile.updateTile()
    }
}

internal const val EXTRA_ASK_LOCATION = "ask_location"

internal fun TileService.openAppToTurnOnLocation() {
    val intent = Intent(this, MapActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(EXTRA_ASK_LOCATION, true)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    } else {
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
