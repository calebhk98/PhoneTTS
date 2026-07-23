package com.phonetts.app.playback

/**
 * How far into the current document playback has reached, both in milliseconds. Fed to the
 * lock-screen media session so it can render a scrubber (issue #26). `totalMillis <= 0` means the
 * estimated total isn't known yet (still generating), in which case the session shows position
 * without a determinate bar.
 */
data class PlaybackProgress(
    val elapsedMillis: Long,
    val totalMillis: Long,
)

/**
 * The playback surface [PlaybackService] drives from its notification and lock-screen media
 * controls. Whoever owns a running playback (today that is `TtsViewModel`, which already exposes
 * `pausePlayback()` / `resumePlayback()` / `stop()` over its own `BufferedPlayback`) implements
 * this with those same methods and hands it to the service via
 * [PlaybackService.LocalBinder.attachController]. That is the whole integration surface: a tap
 * on the notification's Play/Pause/Stop action - or a lock-screen control, or the sleep timer
 * expiring - reaches exactly the same pause/resume/stop semantics as the in-app buttons. No
 * second control path.
 *
 * The paragraph-skip and [progress] members are added for the richer lock-screen surface
 * (issue #26). They are **default no-op / unknown** so a controller that can't yet honour them
 * (e.g. one that hasn't wired position tracking) still satisfies the interface unchanged - the
 * lock screen simply omits the scrubber / the skip actions become inert, never a compile break.
 */
interface PlaybackController {
    /** Mirrors `TtsViewModel.UiState.playing`: true while a generate-and-play run is active. */
    val isPlaying: Boolean

    /** Mirrors `TtsViewModel.UiState.paused`: true while playback is paused mid-run. */
    val isPaused: Boolean

    /**
     * Elapsed / estimated-total position for the lock-screen scrubber, or null if the owner
     * doesn't track it. Read (not pushed) so the service can poll it while rebuilding the session.
     */
    val progress: PlaybackProgress? get() = null

    /** Pause playback; generation (if still running) is expected to keep filling the buffer. */
    fun pause()

    /** Resume already-generated audio; must not re-synthesize. */
    fun resume()

    /** Stop this playback run for good. */
    fun stop()

    /** Jump playback forward one paragraph. No-op if the owner doesn't support paragraph skipping. */
    fun skipForwardParagraph() {}

    /** Jump playback back one paragraph. No-op if the owner doesn't support paragraph skipping. */
    fun skipBackParagraph() {}
}
