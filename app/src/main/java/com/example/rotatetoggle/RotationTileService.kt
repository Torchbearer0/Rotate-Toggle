package com.example.rotatetoggle

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap to start/stop the rotate button.
 * Still works after Vivo force-stops the app, because the system binds
 * this service on demand.
 */
class RotationTileService : TileService() {

    override fun onStartListening() {
        render(RotationService.running)
    }

    override fun onClick() {
        if (RotationService.running) {
            RotationService.stop(this)
            render(false)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            launch(MainActivity::class.java) // lets the user grant the permission
            return
        }
        try {
            RotationService.start(this)
            render(true)
        } catch (e: Exception) {
            // Background-start was refused: fall back to a visible trampoline activity.
            launch(StartActivity::class.java)
        }
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Rotate button"
        tile.updateTile()
    }

    private fun launch(cls: Class<*>) {
        val intent = Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
