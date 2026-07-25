package com.phonetts.app.playback

/**
 * In-process (never persisted) hand-off of live generation progress between [GenerationNotifier]
 * (writer, on every chunk) and [PlaybackNotificationFactory] (reader) so the two can agree on a
 * single visible row instead of two competing ones.
 *
 * The rule the two-notification bug asked to fix: while generating AND playing, the user should
 * see ONE media notification with transport controls, generation progress folded into its
 * subtitle - not a second "Generating speech…" row racing it for the top slot. [GenerationNotifier]
 * checks [PlaybackService.isForegroundActive] and, whenever it's true, skips posting its own row
 * (still writing [current] here) and instead lets [PlaybackNotificationFactory] read it back into
 * the "Playing"/"Paused" text the next time the media notification rebuilds. When no playback
 * session is active (e.g. a future generation-only flow, mirroring today's export path which never
 * touches [GenerationNotifier] at all), [GenerationNotifier] posts its own standalone row exactly as
 * before and this object is just an unread side channel.
 */
object GenerationProgressSignal {
    @Volatile
    var current: Progress? = null

    data class Progress(
        val chunksDone: Int,
        val totalChunks: Int,
    ) {
        /**
         * "Generating sentence N of M" (or "…" while the total isn't known yet) - the exact wording
         * [GenerationNotifier] always showed, now shared so [PlaybackNotificationFactory] folds in
         * an identical phrase rather than inventing a second one.
         */
        fun describe(): String {
            if (totalChunks <= 0) return "Generating sentence ${chunksDone + 1}…"
            val inFlight = (chunksDone + 1).coerceAtMost(totalChunks)
            return "Generating sentence $inFlight of $totalChunks"
        }
    }
}
