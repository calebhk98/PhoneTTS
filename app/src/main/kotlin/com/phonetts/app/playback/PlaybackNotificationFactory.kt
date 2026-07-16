package com.phonetts.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.phonetts.app.MainActivity
import com.phonetts.app.R

const val PLAYBACK_NOTIFICATION_ID = 1
const val PLAYBACK_ACTION_PLAY_PAUSE = "com.phonetts.app.playback.action.PLAY_PAUSE"
const val PLAYBACK_ACTION_STOP = "com.phonetts.app.playback.action.STOP"

private const val CHANNEL_ID = "phonetts_playback"
private const val CHANNEL_NAME = "Playback"
private const val PENDING_INTENT_REQUEST_CODE = 0

// The app has no @string/app_name resource (AndroidManifest's android:label is a plain literal
// today) — this is only the notification's fallback title when the caller passes none, not a
// model fact, so a local constant is the right home for it rather than adding a resource.
private const val DEFAULT_NOTIFICATION_TITLE = "PhoneTTS"

/**
 * Builds [PlaybackService]'s ongoing notification: Play/Pause/Stop actions, styled as a
 * [MediaStyle] notification tied to the session so it also renders lock-screen controls. Split
 * out of [PlaybackService] itself so that class's job stays "reconcile playback state", not
 * "assemble notification UI" — keeping both comfortably under the never-nesting/function-count
 * limits (spec §8/CLAUDE.md).
 */
class PlaybackNotificationFactory(private val context: Context) {
    init {
        createChannel()
    }

    fun build(
        paused: Boolean,
        title: String?,
        sessionToken: MediaSessionCompat.Token?,
    ): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title ?: DEFAULT_NOTIFICATION_TITLE)
            .setContentText(if (paused) "Paused" else "Playing")
            .setOngoing(!paused)
            .setContentIntent(openAppPendingIntent())
            .addAction(playPauseAction(paused))
            .addAction(stopAction())
            .setStyle(MediaStyle().setMediaSession(sessionToken).setShowActionsInCompactView(0, 1))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun playPauseAction(paused: Boolean): NotificationCompat.Action {
        val icon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val label = if (paused) "Play" else "Pause"
        return NotificationCompat.Action(icon, label, servicePendingIntent(PLAYBACK_ACTION_PLAY_PAUSE))
    }

    private fun stopAction(): NotificationCompat.Action {
        val icon = android.R.drawable.ic_menu_close_clear_cancel
        return NotificationCompat.Action(icon, "Stop", servicePendingIntent(PLAYBACK_ACTION_STOP))
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(context, PlaybackService::class.java).setAction(action)
        return PendingIntent.getService(context, action.hashCode(), intent, pendingIntentFlags())
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
        return PendingIntent.getActivity(context, PENDING_INTENT_REQUEST_CODE, intent, pendingIntentFlags())
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        manager.createNotificationChannel(channel)
    }
}
