package com.phonetts.app.hf

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.phonetts.app.R

/**
 * Shows a system notification with live byte-progress while a Browse download is in flight, and
 * clears/completes it when the download finishes, fails, or is cancelled. A model download can take
 * several minutes (issue: download progress notification) - long enough that the user reasonably
 * switches away from the app, and the in-screen progress bar ([HfBrowseScreen]'s `DownloadProgress`)
 * alone gives them no way to check on it without returning to this exact screen.
 *
 * Mirrors the existing notification pattern in
 * [com.phonetts.app.playback.PlaybackNotificationFactory]: a single low-importance channel created
 * once in [init], reused by every notification this class posts. Unlike playback (one notification
 * for the one active session), downloads can run several at once (issue #2) - so each repo id gets
 * its own notification id ([notificationId]) instead of one shared row that the last-started
 * download would clobber.
 *
 * Reuses the `POST_NOTIFICATIONS` permission already declared in AndroidManifest.xml for playback -
 * no new permission needed. Fails closed on Android 13+ when the user denied it (or on any API level
 * where notifications are otherwise disabled for the app): every method checks
 * [NotificationManagerCompat.areNotificationsEnabled] first, so a denied permission just means no
 * notification appears, never a `SecurityException` crash.
 */
class DownloadNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    /** Post/update the progress notification for [modelId]. [bytesTotal] null means the repo's
     * total size isn't known yet (see [com.phonetts.core.download.hf.HfSizeEstimate]) - shown as an
     * indeterminate bar, same fallback the in-screen progress bar uses, never a fabricated percent. */
    fun updateProgress(
        modelId: String,
        displayName: String,
        bytesDone: Long,
        bytesTotal: Long?,
    ) {
        if (!hasPermission()) return
        val builder =
            baseBuilder(displayName)
                .setContentTitle("Downloading $displayName")
                .setContentText(progressText(bytesDone, bytesTotal))
                .setOngoing(true)
        applyProgress(builder, bytesDone, bytesTotal)
        manager.notify(notificationId(modelId), builder.build())
    }

    /** Replace the progress row with a brief "done" notification that the user can dismiss (unlike
     * the ongoing progress one, which isn't swipeable while a download is active). */
    fun complete(
        modelId: String,
        displayName: String,
    ) {
        if (!hasPermission()) return
        val notification =
            baseBuilder(displayName)
                .setContentTitle("$displayName downloaded")
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        manager.notify(notificationId(modelId), notification)
    }

    /** Replace the progress row with a failure notification naming the reason (mirrors
     * [HfDownloader]'s own rule of never surfacing a bare/contextless error). */
    fun failed(
        modelId: String,
        displayName: String,
        reason: String,
    ) {
        if (!hasPermission()) return
        val notification =
            baseBuilder(displayName)
                .setContentTitle("$displayName failed to download")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setOngoing(false)
                .setAutoCancel(true)
                .build()
        manager.notify(notificationId(modelId), notification)
    }

    /** Drops [modelId]'s notification outright - used when the user cancels from the in-app row, so
     * no stale "downloading"/"failed" notification lingers for a download the user chose to abandon. */
    fun cancel(modelId: String) = manager.cancel(notificationId(modelId))

    private fun baseBuilder(displayName: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentTitle(displayName)

    private fun applyProgress(
        builder: NotificationCompat.Builder,
        bytesDone: Long,
        bytesTotal: Long?,
    ) {
        if (bytesTotal == null || bytesTotal <= 0L) {
            builder.setProgress(0, 0, true) // indeterminate - total isn't known yet
            return
        }
        val percent = ((bytesDone.toDouble() / bytesTotal) * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX)
        builder.setProgress(PERCENT_MAX, percent, false)
    }

    private fun progressText(
        bytesDone: Long,
        bytesTotal: Long?,
    ): String {
        val total = bytesTotal ?: return formatMb(bytesDone)
        return "${formatMb(bytesDone)} / ${formatMb(total)}"
    }

    private fun formatMb(bytes: Long): String = "%.1f MB".format(bytes / BYTES_PER_MB)

    // One id per repo (rather than one shared id) so concurrent downloads (issue #2) each keep their
    // own row in the shade instead of the latest update overwriting an unrelated repo's notification.
    private fun notificationId(modelId: String): Int = BASE_NOTIFICATION_ID + modelId.hashCode()

    private fun hasPermission(): Boolean = manager.areNotificationsEnabled()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val systemManager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
        systemManager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "phonetts_downloads"
        private const val CHANNEL_NAME = "Model downloads"
        private const val BASE_NOTIFICATION_ID = 20_000
        private const val PERCENT_MAX = 100
        private const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
