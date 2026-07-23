package com.phonetts.app.playback

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

private const val TILE_IDLE_LABEL = "Resume reading"

/**
 * Quick Settings tile (issue #27): toggles play/pause on whatever was last loaded without opening
 * the app. A tap fires [PLAYBACK_ACTION_PLAY_PAUSE] at [PlaybackService] - the same action the
 * notification and the home-screen widget use - so it routes to the attached [PlaybackController]
 * with no second control path. The tile's active/inactive look and its subtitle come from the
 * [PlaybackStateStore] snapshot the service keeps current, so the shade reflects real playback
 * state even when the app UI isn't running.
 */
class ResumeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        syncTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, PlaybackService::class.java).setAction(PLAYBACK_ACTION_PLAY_PAUSE)
        startService(intent)
        // Reflect the toggle immediately; the service also republishes the snapshot on the state
        // change, which repaints on the next onStartListening.
        syncTile()
    }

    private fun syncTile() {
        val tile = qsTile ?: return
        val snapshot = PlaybackStateStore.read(this)
        val active = snapshot.playing && !snapshot.paused
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = TILE_IDLE_LABEL
        // Tile.subtitle is API 29+; older shades just show the label.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = snapshot.title
        tile.updateTile()
    }
}
