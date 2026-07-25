package com.phonetts.core.metrics

import com.phonetts.core.store.DurableStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole persisted cumulative-usage document. One small JSON blob; see [UsageStatsStore].
 * [playCountByModelId] is the only unbounded-shaped field, but it is bounded in practice by the
 * number of distinct models the user has ever played - never an event list that grows without limit.
 */
@Serializable
private data class UsageStatsState(
    val totalListeningMillis: Long = 0L,
    val totalWordsSpoken: Long = 0L,
    val generationCount: Long = 0L,
    val playCountByModelId: Map<String, Long> = emptyMap(),
)

/**
 * A read-only snapshot of the cumulative usage totals, for a Stats screen to render. Deliberately a
 * plain data class (not [UsageStatsState] itself) so callers outside this file never depend on the
 * private serialized shape.
 */
data class UsageStatsSnapshot(
    val totalListeningMillis: Long,
    val totalWordsSpoken: Long,
    val generationCount: Long,
    val playCountByModelId: Map<String, Long>,
) {
    /** Every model that has ever been played, most-played first - what a "top models" list reads. */
    val playCountsDescending: List<Pair<String, Long>>
        get() = playCountByModelId.toList().sortedByDescending { it.second }

    companion object {
        /** The all-zero/empty snapshot - what a fresh install (or a corrupt document) reads back as. */
        val EMPTY = UsageStatsSnapshot(0L, 0L, 0L, emptyMap())
    }
}

/**
 * Durable, cumulative usage counters (issue #130 - Stats page): **total listening time**, **total
 * words spoken**, **per-model play counts**, and **generation count**. Nothing accumulated these
 * before this store - [ListeningTimeEstimator] and [GenerationStats] both compute per-run figures
 * live and then discard them; this is the durable tally layered on top, fed by the app each time a
 * generation or a playback actually completes.
 *
 * Pure `:core` logic over an injected [DurableStore] (mirrors [com.phonetts.core.store.FavoritesStore]
 * exactly): state is loaded once at construction and every mutation rewrites the whole (small,
 * bounded) document straight back, so a relaunch that constructs a fresh instance over the same
 * backing document sees exactly what the previous session left - the durable-store contract's
 * stand-in for surviving process death. Fails closed: an unreadable/corrupt document loads as the
 * all-zero [UsageStatsSnapshot.EMPTY] rather than throwing, and a failed write is swallowed (the
 * in-memory total for the rest of this process still advances - only persistence silently fails).
 *
 * Never reads the wall clock: every duration/count here is caller-supplied (elapsed millis, a word
 * count), matching every other `:core` seam - this class has no notion of "now".
 */
class UsageStatsStore(
    private val store: DurableStore,
    private val documentName: String = DOCUMENT_NAME,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: UsageStatsState = load()

    /** The current cumulative totals, ready to render. */
    fun snapshot(): UsageStatsSnapshot =
        UsageStatsSnapshot(
            totalListeningMillis = state.totalListeningMillis,
            totalWordsSpoken = state.totalWordsSpoken,
            generationCount = state.generationCount,
            playCountByModelId = state.playCountByModelId,
        )

    /**
     * Records that a synthesis run just finished producing [wordCount] words of speech - bumps
     * [UsageStatsSnapshot.generationCount] by one and adds [wordCount] to the cumulative words-spoken
     * total. Call this once per completed generation (export or real-time), regardless of whether
     * the result is ever played back.
     */
    fun recordGeneration(wordCount: Int) {
        require(wordCount >= 0) { "wordCount must not be negative, was $wordCount" }
        persist(
            state.copy(
                generationCount = state.generationCount + 1,
                totalWordsSpoken = state.totalWordsSpoken + wordCount,
            ),
        )
    }

    /**
     * Records that [modelId] was just played back for [listenedMillis] of wall-clock listening time
     * - adds to the cumulative listening-time total and bumps that model's play count by one. Call
     * this once per completed (or stopped) playback, with the elapsed playback duration the caller
     * actually measured - never a resampled/estimated figure.
     */
    fun recordPlayback(
        modelId: String,
        listenedMillis: Long,
    ) {
        require(modelId.isNotBlank()) { "modelId must not be blank" }
        require(listenedMillis >= 0L) { "listenedMillis must not be negative, was $listenedMillis" }
        val counts = state.playCountByModelId
        val updatedCounts = counts + (modelId to (counts[modelId] ?: 0L) + 1)
        persist(
            state.copy(
                totalListeningMillis = state.totalListeningMillis + listenedMillis,
                playCountByModelId = updatedCounts,
            ),
        )
    }

    private fun persist(next: UsageStatsState) {
        state = next
        runCatching { store.write(documentName, json.encodeToString(UsageStatsState.serializer(), next)) }
    }

    private fun load(): UsageStatsState {
        val raw = store.read(documentName) ?: return UsageStatsState()
        return runCatching { json.decodeFromString(UsageStatsState.serializer(), raw) }.getOrDefault(UsageStatsState())
    }

    companion object {
        /** The [DurableStore] document name (one file) cumulative usage stats are persisted under. */
        const val DOCUMENT_NAME = "usage_stats"
    }
}
