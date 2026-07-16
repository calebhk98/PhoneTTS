package com.phonetts.app.playback

/**
 * The playback surface [PlaybackService] drives from its notification and lock-screen media
 * controls. Whoever owns a running playback (today that is `TtsViewModel`, which already exposes
 * `pausePlayback()` / `resumePlayback()` / `stop()` over its own `BufferedPlayback`) implements
 * this with those same methods and hands it to the service via
 * [PlaybackService.LocalBinder.attachController]. That is the whole integration surface: a tap
 * on the notification's Play/Pause/Stop action — or a lock-screen control, or the sleep timer
 * expiring — reaches exactly the same pause/resume/stop semantics as the in-app buttons. No
 * second control path.
 */
interface PlaybackController {
    /** Mirrors `TtsViewModel.UiState.playing`: true while a generate-and-play run is active. */
    val isPlaying: Boolean

    /** Mirrors `TtsViewModel.UiState.paused`: true while playback is paused mid-run. */
    val isPaused: Boolean

    /** Pause playback; generation (if still running) is expected to keep filling the buffer. */
    fun pause()

    /** Resume already-generated audio; must not re-synthesize. */
    fun resume()

    /** Stop this playback run for good. */
    fun stop()
}
