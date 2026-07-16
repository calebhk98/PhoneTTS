package com.phonetts.core.metrics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Wraps a `synthesize()` result (spec §6.1) with a live [GenerationStats] snapshot per chunk,
 * measured — never guessed — from the chunks that actually arrive and real `now()` deltas.
 *
 * Transparent: every audio [FloatArray] from the receiver is re-emitted completely unchanged,
 * paired with the stats snapshot computed right after that chunk landed. This wrapper never
 * mutates, drops, buffers, or reorders audio — it only observes — so a caller can still hand the
 * unzipped first element of each pair to `StreamingConsumer`/`WavWriter` exactly as before, while
 * a UI observes the second element for a "generated X.Xs / elapsed Y.Ys / ETA / words/sec"
 * readout.
 *
 * @param sampleRate the model's sample rate — always read from
 *   [com.phonetts.core.model.ModelDescriptor.sampleRate] by the caller, never a literal (SSOT) —
 *   used only to convert the accumulated sample count into audio-seconds.
 * @param totalWords total word count of the source text, if known (see [WordCounter]). Enables
 *   [GenerationStats.wordsPerSecond] and [GenerationStats.estimatedRemainingSeconds].
 * @param totalChunks total number of upstream emissions expected, if known — e.g. the size of
 *   the [com.phonetts.core.text.TextChunker] sentence list being driven through `synthesize()`.
 *   Enables progress-fraction-based estimates.
 * @param now nanosecond clock, injectable so tests are deterministic. Defaults to the real
 *   clock; production code should leave it default, tests should always supply a fake.
 */
fun Flow<FloatArray>.trackGeneration(
    sampleRate: Int,
    totalWords: Int? = null,
    totalChunks: Int? = null,
    now: () -> Long = { System.nanoTime() },
): Flow<Pair<FloatArray, GenerationStats>> {
    require(sampleRate > 0) { "sampleRate must be positive" }
    val upstream = this

    return flow {
        val startNanos = now()
        var samplesProduced = 0L
        var chunksDone = 0

        upstream.collect { chunk ->
            samplesProduced += chunk.size
            chunksDone++
            val elapsedSeconds = ((now() - startNanos) / NANOS_PER_SECOND).coerceAtLeast(0.0)
            val stats =
                GenerationStats(
                    audioSecondsProduced = samplesProduced / sampleRate.toDouble(),
                    wallClockElapsedSeconds = elapsedSeconds,
                    chunksDone = chunksDone,
                    totalChunks = totalChunks,
                    totalWords = totalWords,
                )
            emit(chunk to stats)
        }
    }
}
