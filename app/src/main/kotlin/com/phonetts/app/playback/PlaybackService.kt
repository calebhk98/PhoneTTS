package com.phonetts.app.playback

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver

private const val MEDIA_SESSION_TAG = "PhoneTtsPlayback"
private const val PLAYBACK_SPEED_NORMAL = 1f
private val PLAYBACK_ACTIONS =
    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_STOP

/**
 * Foreground service that keeps TTS playback alive with the screen off, and gives the OS a
 * notification + lock-screen media session to control it from (spec: background playback,
 * issue #19). It owns no audio or generation logic of its own — [PlaybackController] (attached by
 * whoever is actually playing, currently `TtsViewModel`) is where a Play/Pause/Stop tap actually
 * routes to, so this class's job is only to turn those taps into calls on that interface and keep
 * the notification ([PlaybackNotificationFactory]) / [MediaSessionCompat] / audio focus
 * ([PlaybackAudioFocus]) in sync with the reported state.
 *
 * Sleep timer: [startSleepTimer] arms a [SleepTimerRunner] (the `:app`-side clock for `:core`'s
 * [com.phonetts.core.playback.SleepTimer]) whose expiry stops the attached controller — "stop
 * after N minutes" is just another route into the same `stop()` call a Stop tap uses.
 */
class PlaybackService : Service() {
    private val binder = LocalBinder()
    private var controller: PlaybackController? = null
    private var mediaSession: MediaSessionCompat? = null
    private val sleepTimer = SleepTimerRunner()
    private val audioFocus by lazy { PlaybackAudioFocus(this) { controller?.pause() } }
    private val notifications by lazy { PlaybackNotificationFactory(this) }

    /** Binder surface the owner of a playback (e.g. `TtsViewModel`) binds to. */
    inner class LocalBinder : Binder() {
        fun attachController(controller: PlaybackController) {
            this@PlaybackService.controller = controller
        }

        fun detachController() {
            this@PlaybackService.controller = null
        }

        /** Call whenever playback state changes, to keep the notification/session/focus in sync. */
        fun onStateChanged(
            playing: Boolean,
            paused: Boolean,
            title: String? = null,
        ) = this@PlaybackService.onStateChanged(playing, paused, title)

        fun startSleepTimer(durationMillis: Long) = this@PlaybackService.startSleepTimer(durationMillis)

        fun cancelSleepTimer() = sleepTimer.cancel()

        fun sleepTimerRemainingMillis(): Long = sleepTimer.remainingMillis()

        val isSleepTimerRunning: Boolean get() = sleepTimer.isRunning
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        mediaSession = buildMediaSession()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        mediaSession?.let { MediaButtonReceiver.handleIntent(it, intent) }
        handleAction(intent?.action)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sleepTimer.cancel()
        audioFocus.abandon()
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun handleAction(action: String?) {
        when (action) {
            PLAYBACK_ACTION_PLAY_PAUSE -> togglePlayPause()
            PLAYBACK_ACTION_STOP -> controller?.stop()
        }
    }

    private fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPaused) active.resume() else active.pause()
    }

    // The single place notification/session/focus/foreground state is reconciled against the
    // controller's reported state. Not playing at all (stopped or finished) tears everything
    // down; playing (whether paused or not) (re)posts the ongoing notification.
    private fun onStateChanged(
        playing: Boolean,
        paused: Boolean,
        title: String?,
    ) {
        if (!playing) {
            stopPlaybackForeground()
            return
        }
        audioFocus.request()
        updateSessionPlaybackState(paused)
        val notification = notifications.build(paused, title, mediaSession?.sessionToken)
        startForeground(PLAYBACK_NOTIFICATION_ID, notification)
    }

    private fun stopPlaybackForeground() {
        sleepTimer.cancel()
        audioFocus.abandon()
        stopForegroundCompat()
        stopSelf()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    fun startSleepTimer(durationMillis: Long) {
        sleepTimer.start(durationMillis) { controller?.stop() }
    }

    private fun buildMediaSession(): MediaSessionCompat =
        MediaSessionCompat(this, MEDIA_SESSION_TAG).apply {
            setCallback(sessionCallback)
            isActive = true
        }

    private val sessionCallback =
        object : MediaSessionCompat.Callback() {
            override fun onPlay() = controller?.resume() ?: Unit

            override fun onPause() = controller?.pause() ?: Unit

            override fun onStop() = controller?.stop() ?: Unit
        }

    private fun updateSessionPlaybackState(paused: Boolean) {
        val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, PLAYBACK_SPEED_NORMAL)
                .build(),
        )
    }
}
