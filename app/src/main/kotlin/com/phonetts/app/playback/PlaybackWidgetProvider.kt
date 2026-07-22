package com.phonetts.app.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.phonetts.app.MainActivity
import com.phonetts.app.R

private const val WIDGET_TITLE_FALLBACK = "PhoneTTS"
private const val WIDGET_IDLE_TITLE = "Nothing loaded"

/**
 * Home-screen widget (issue #25): the current/last document title plus a Play/Pause button. The
 * button reuses the *exact* control surface the notification uses — it fires
 * [PLAYBACK_ACTION_PLAY_PAUSE] at [PlaybackService], which toggles the attached
 * [PlaybackController] — so there is no second control path. Title + play/paused icon come from
 * the [PlaybackStateStore] snapshot the service keeps current, since the widget lives in the
 * launcher's process and can't be pushed to directly; [refresh] repaints it whenever that snapshot
 * changes. Tapping the body opens the app, mirroring the notification's content intent.
 */
class PlaybackWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> updateWidget(context, appWidgetManager, id) }
    }

    companion object {
        /** Repaint every placed instance from the latest [PlaybackStateStore] snapshot. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val component = ComponentName(context, PlaybackWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id -> updateWidget(context, manager, id) }
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
        ) {
            val snapshot = PlaybackStateStore.read(context)
            val views = RemoteViews(context.packageName, R.layout.playback_widget)
            views.setTextViewText(R.id.playback_widget_title, titleFor(snapshot))
            views.setImageViewResource(R.id.playback_widget_play_pause, iconFor(snapshot))
            views.setOnClickPendingIntent(R.id.playback_widget_play_pause, playPauseIntent(context))
            views.setOnClickPendingIntent(R.id.playback_widget_root, openAppIntent(context))
            manager.updateAppWidget(widgetId, views)
        }

        private fun titleFor(snapshot: PlaybackStateStore.Snapshot): String =
            snapshot.title ?: if (snapshot.playing) WIDGET_TITLE_FALLBACK else WIDGET_IDLE_TITLE

        // Show Play while idle/paused (tap to start/resume), Pause while actively playing.
        private fun iconFor(snapshot: PlaybackStateStore.Snapshot): Int =
            if (snapshot.playing && !snapshot.paused) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }

        private fun playPauseIntent(context: Context): PendingIntent {
            val intent = Intent(context, PlaybackService::class.java).setAction(PLAYBACK_ACTION_PLAY_PAUSE)
            return PendingIntent.getService(context, PLAYBACK_ACTION_PLAY_PAUSE.hashCode(), intent, flags())
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
            return PendingIntent.getActivity(context, 0, intent, flags())
        }

        private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
