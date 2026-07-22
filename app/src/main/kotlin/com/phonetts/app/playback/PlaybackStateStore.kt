package com.phonetts.app.playback

import android.content.Context

/**
 * A tiny, durable snapshot of the current/last playback — the last document [title] and whether
 * it is [playing] / [paused]. [PlaybackService] writes it on every state change; the home-screen
 * widget ([PlaybackWidgetProvider], issue #25) and the Quick Settings tile ([ResumeTileService],
 * issue #27) read it, since those surfaces run as separate components that may be shown while the
 * service isn't bound and so can't be pushed to directly.
 *
 * This is deliberately *not* a `:core` [com.phonetts.core.prefs.PreferenceStore] concern: it holds
 * no model facts, only transient UI state the OS surfaces mirror, so it lives app-side next to the
 * components that use it.
 */
object PlaybackStateStore {
    private const val PREFS_NAME = "playback_state"
    private const val KEY_TITLE = "title"
    private const val KEY_PLAYING = "playing"
    private const val KEY_PAUSED = "paused"

    data class Snapshot(
        val title: String?,
        val playing: Boolean,
        val paused: Boolean,
    )

    fun write(
        context: Context,
        snapshot: Snapshot,
    ) {
        prefs(context).edit()
            .putString(KEY_TITLE, snapshot.title)
            .putBoolean(KEY_PLAYING, snapshot.playing)
            .putBoolean(KEY_PAUSED, snapshot.paused)
            .apply()
    }

    fun read(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            title = p.getString(KEY_TITLE, null),
            playing = p.getBoolean(KEY_PLAYING, false),
            paused = p.getBoolean(KEY_PAUSED, false),
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
