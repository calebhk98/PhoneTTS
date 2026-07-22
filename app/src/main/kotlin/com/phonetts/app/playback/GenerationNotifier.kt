package com.phonetts.app.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phonetts.app.R
import com.phonetts.core.metrics.GenerationStats

/**
 * Shows a system notification with live progress WHILE [TtsViewModel][com.phonetts.app.ui.TtsViewModel]
 * is generating (Play/Generate) — a generation over more than a few sentences is easily long enough
 * that the user switches away from the app, and the in-screen [GenerationStats] readout alone gives
 * them no way to check progress without returning to this exact screen.
 *
 * Mirrors the existing notification pattern in [PlaybackNotificationFactory] /
 * [com.phonetts.app.hf.DownloadNotifier]: a single low-importance channel created once in [init],
 * reused by every notification this class posts. Deliberately its OWN channel and notification id
 * ([GENERATION_NOTIFICATION_ID], distinct from [PLAYBACK_NOTIFICATION_ID]) — generation and playback
 * are related but separate states (generation keeps running ahead of/behind playback, spec §6.1's
 * dual-consumer buffer), so they get separate rows instead of one clobbering the other.
 *
 * Reuses the `POST_NOTIFICATIONS` permission already declared in AndroidManifest.xml for playback —
 * no new permission or manifest change needed. Fails closed exactly like [PlaybackNotificationFactory]
 * and [com.phonetts.app.hf.DownloadNotifier]: every method checks
 * [NotificationManagerCompat.areNotificationsEnabled] first, so a denied permission (or notifications
 * disabled for the app) just means no notification appears, never a `SecurityException` crash.
 */
class GenerationNotifier(context: Context) {
    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    init {
        createChannel()
    }

    /**
     * Start (or restart) the progress row for a fresh generation of [totalChunks] sentences.
     * [totalChunks] <= 0 means the total isn't known yet — shown as an indeterminate bar rather than
     * a fabricated fraction, same fallback [applyProgress] uses per-update.
     */
    fun start(totalChunks: Int) = post(chunksDone = 0, totalChunks = totalChunks)

    /** Update the row from a live [GenerationStats] snapshot — the ONE generation path's own progress
     * signal (spec §6.1), never a separately computed number. */
    fun update(stats: GenerationStats) = post(chunksDone = stats.chunksDone, totalChunks = stats.totalChunks ?: 0)

    /** Dismiss the row — call on generation completion, stop/barge-in, or failure alike, so nothing
     * lingers claiming a generation is still in progress once it definitely isn't. */
    fun clear() = manager.cancel(GENERATION_NOTIFICATION_ID)

    private fun post(
        chunksDone: Int,
        totalChunks: Int,
    ) {
        if (!hasPermission()) return
        val builder =
            NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Generating speech…")
                .setContentText(progressText(chunksDone, totalChunks))
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
        applyProgress(builder, chunksDone, totalChunks)
        manager.notify(GENERATION_NOTIFICATION_ID, builder.build())
    }

    private fun applyProgress(
        builder: NotificationCompat.Builder,
        chunksDone: Int,
        totalChunks: Int,
    ) {
        if (totalChunks <= 0) {
            builder.setProgress(0, 0, true) // indeterminate — the sentence count isn't known yet
            return
        }
        builder.setProgress(totalChunks, chunksDone.coerceIn(0, totalChunks), false)
    }

    private fun progressText(
        chunksDone: Int,
        totalChunks: Int,
    ): String {
        if (totalChunks <= 0) return "Generating sentence ${chunksDone + 1}…"
        // chunksDone counts FULLY finished sentences (AbstractVoiceEngine emits one chunk per
        // sentence), so the sentence actually in flight is one past that, clamped to the total.
        val inFlight = (chunksDone + 1).coerceAtMost(totalChunks)
        return "Generating sentence $inFlight of $totalChunks"
    }

    private fun hasPermission(): Boolean = manager.areNotificationsEnabled()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        systemManager.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "phonetts_generation"
        const val CHANNEL_NAME = "Generation progress"

        // Distinct from PLAYBACK_NOTIFICATION_ID (1, see PlaybackNotificationFactory) so the two
        // states never clobber each other's row in the shade.
        const val GENERATION_NOTIFICATION_ID = 2
    }
}
