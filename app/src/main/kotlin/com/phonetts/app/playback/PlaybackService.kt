package com.phonetts.app.playback

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.session.MediaButtonReceiver
import com.phonetts.app.PrefsPreferenceStore
import com.phonetts.core.prefs.PlaybackCuePreferences

private const val MEDIA_SESSION_TAG = "PhoneTtsPlayback"
private const val PLAYBACK_SPEED_NORMAL = 1f
private const val PLAYBACK_SPEED_PAUSED = 0f
private val PLAYBACK_ACTIONS =
    PlaybackStateCompat.ACTION_PLAY or
        PlaybackStateCompat.ACTION_PAUSE or
        PlaybackStateCompat.ACTION_STOP or
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

/**
 * Foreground service that keeps TTS playback alive with the screen off, and gives the OS a
 * notification + lock-screen media session to control it from (spec: background playback,
 * issue #19). It owns no audio or generation logic of its own - [PlaybackController] (attached by
 * whoever is actually playing, currently `TtsViewModel`) is where a Play/Pause/Stop tap actually
 * routes to, so this class's job is only to turn those taps into calls on that interface and keep
 * the notification ([PlaybackNotificationFactory]) / [MediaSessionCompat] / audio focus
 * ([PlaybackAudioFocus]) in sync with the reported state.
 *
 * Sleep timer: [startSleepTimer] arms a [SleepTimerRunner] (the `:app`-side clock for `:core`'s
 * [com.phonetts.core.playback.SleepTimer]) whose expiry stops the attached controller - "stop
 * after N minutes" is just another route into the same `stop()` call a Stop tap uses.
 *
 * It also publishes a [PlaybackStateStore] snapshot + refreshes the home-screen widget on every
 * state change (issues #25/#27, so the widget and Quick Settings tile mirror it), sets progress on
 * the session for the lock-screen scrubber (issue #26), and fires the end-of-document cue
 * ([PlaybackChime]) when a run finishes *naturally* rather than on a user Stop (issue #32).
 */
class PlaybackService : Service() {
    private val binder = LocalBinder()
    private var controller: PlaybackController? = null
    private var mediaSession: MediaSessionCompat? = null
    private val sleepTimer = SleepTimerRunner()
    private val audioFocus by lazy { PlaybackAudioFocus(this) { controller?.pause() } }
    private val notifications by lazy { PlaybackNotificationFactory(this) }
    private val chime by lazy { PlaybackChime(this) }
    private val cuePreferences by lazy { PlaybackCuePreferences(PrefsPreferenceStore(this)) }

    // Distinguishes a natural end-of-document (chime) from a user Stop (no chime): every Stop the
    // service itself routes - notification/lock-screen Stop, session onStop, sleep-timer expiry -
    // sets [userStopRequested] before calling stop(). A transition out of "playing" that was NOT
    // one of those is treated as the flow completing on its own. [wasPlaying] gates the cue to a
    // real playing→stopped edge, so a spurious "not playing" (e.g. the initial bound state) is silent.
    private var wasPlaying = false
    private var userStopRequested = false

    /** Binder surface the owner of a playback (e.g. `TtsViewModel`) binds to. */
    inner class LocalBinder : Binder() {
        fun attachController(controller: PlaybackController) {
            this@PlaybackService.controller = controller
        }

        fun detachController() {
            this@PlaybackService.controller = null
        }

        /**
         * Tell the service the stop about to be reflected in state was user-initiated (issue #32).
         * The in-app Stop button drives `TtsViewModel.stop()` directly, bypassing the service's own
         * stop routing, so without this flag the service would misread that manual Stop as a natural
         * end-of-document and fire the completion cue. Called before the `playing -> stopped` state
         * change reaches [onStateChanged].
         */
        fun notifyUserStop() {
            this@PlaybackService.userStopRequested = true
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
            PLAYBACK_ACTION_STOP -> requestStop()
            PLAYBACK_ACTION_PREV_PARAGRAPH -> controller?.skipBackParagraph()
            PLAYBACK_ACTION_NEXT_PARAGRAPH -> controller?.skipForwardParagraph()
        }
    }

    private fun togglePlayPause() {
        val active = controller ?: return
        if (active.isPaused) active.resume() else active.pause()
    }

    // A Stop the service knows the user asked for. Flagged so the completion branch below can tell
    // it apart from the flow ending on its own (which is what fires the end-of-document cue).
    private fun requestStop() {
        userStopRequested = true
        controller?.stop()
    }

    // The single place notification/session/focus/foreground state is reconciled against the
    // controller's reported state. Not playing at all (stopped or finished) tears everything
    // down; playing (whether paused or not) (re)posts the ongoing notification.
    private fun onStateChanged(
        playing: Boolean,
        paused: Boolean,
        title: String?,
    ) {
        publishSnapshot(playing, paused, title)
        if (!playing) {
            finishPlayback()
            return
        }
        wasPlaying = true
        // A fresh (or resumed) play clears any stale user-stop flag left by a barge-in restart
        // (select model / skip paragraph both stop() then immediately start again), so a later
        // natural completion of THIS run is still classified correctly and can fire the cue.
        userStopRequested = false
        audioFocus.request()
        updateSession(paused, title)
        val notification = notifications.build(paused, title, mediaSession?.sessionToken, controller?.progress)
        startForeground(PLAYBACK_NOTIFICATION_ID, notification)
    }

    // Mirror the latest state to the widget/tile surfaces, which read a persisted snapshot rather
    // than being pushed to (they may render while the service isn't even bound).
    private fun publishSnapshot(
        playing: Boolean,
        paused: Boolean,
        title: String?,
    ) {
        PlaybackStateStore.write(this, PlaybackStateStore.Snapshot(title, playing, paused))
        PlaybackWidgetProvider.refresh(this)
    }

    // The completion branch: fire the cue only on a genuine playing→stopped edge that wasn't a
    // user Stop, then tear the foreground down either way.
    private fun finishPlayback() {
        val completedNaturally = wasPlaying && !userStopRequested
        wasPlaying = false
        userStopRequested = false
        if (completedNaturally) maybeFireEndOfDocumentCue()
        stopPlaybackForeground()
    }

    private fun maybeFireEndOfDocumentCue() {
        if (!cuePreferences.endOfDocumentCueEnabled()) return
        chime.fire()
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
        sleepTimer.start(durationMillis) { requestStop() }
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

            override fun onStop() = requestStop()

            override fun onSkipToNext() = controller?.skipForwardParagraph() ?: Unit

            override fun onSkipToPrevious() = controller?.skipBackParagraph() ?: Unit
        }

    private fun updateSession(
        paused: Boolean,
        title: String?,
    ) {
        updateSessionMetadata(title)
        updateSessionPlaybackState(paused)
    }

    // The metadata's DURATION is what a lock screen uses as the scrubber's full width; only set it
    // once the estimated total is known so the bar isn't drawn against a placeholder length.
    private fun updateSessionMetadata(title: String?) {
        val builder = MediaMetadataCompat.Builder()
        title?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
        val total = controller?.progress?.totalMillis ?: 0L
        if (total > 0L) builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, total)
        mediaSession?.setMetadata(builder.build())
    }

    private fun updateSessionPlaybackState(paused: Boolean) {
        val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        val position = controller?.progress?.elapsedMillis ?: PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN
        val speed = if (paused) PLAYBACK_SPEED_PAUSED else PLAYBACK_SPEED_NORMAL
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PLAYBACK_ACTIONS)
                .setState(state, position, speed)
                .build(),
        )
    }
}
