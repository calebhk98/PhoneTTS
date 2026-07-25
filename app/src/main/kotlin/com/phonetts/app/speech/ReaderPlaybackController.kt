package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.app.playback.GenerationNotifier
import com.phonetts.core.audio.AudioSink
import com.phonetts.core.audio.buffer.ChunkSpill
import com.phonetts.core.audio.buffer.GeneratedAudio
import com.phonetts.core.diagnostics.FailureContext
import com.phonetts.core.diagnostics.FailureExplainer
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.metrics.GenerationStats
import com.phonetts.core.metrics.ListeningTimeEstimator
import com.phonetts.core.metrics.WordCounter
import com.phonetts.core.metrics.trackGeneration
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.prefs.DocumentPosition
import com.phonetts.core.speech.SpeechSession
import com.phonetts.core.text.TextChunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main reader's transport + generation, rebuilt on [SpeechSession] (mission: adopt core/speech,
 * and in doing so stop `TtsViewModel` being the one file every playback-shaped change has to touch).
 *
 * Replaces `TtsViewModel`'s old `GenSession`/`playback`/`genJob`/`playJob`/`playbackRunToken` quartet.
 * [State.playing]/[State.paused]/[State.currentSentenceIndex] are DERIVED from one [SpeechSession]'s
 * `state` (never hand-written - see [trackProgress]), which is what let the token guard against a
 * superseded run clobbering shared flags be deleted rather than ported: a stale run's flows simply
 * are not observed anymore once a new [Attempt] replaces it (see [SpeechSession]'s own class doc).
 *
 * One [Attempt] (a [SpeechSession] plus the document sentence its buffer starts at) is retained at a
 * time, keyed by model+voice+text+params ([GenKey]) exactly like the old `GenSession`/`GenKey` pair -
 * so skipping/seeking within one document reuses the same generation instead of restarting it, and a
 * document/voice/param change abandons the old one via [Attempt.covers] failing to match.
 *
 * Playback goes through the shared [player] ([SpeechPlayer]) so this screen barges in on (and is
 * barged in on by) Compare/Mix Voices sharing the same physical output.
 *
 * Deliberate, disclosed behavior change from adopting [SpeechSession] (see the mission report): the
 * transport [stop] no longer cancels in-flight generation - it only halts audible playback. The
 * producer keeps filling the buffer in the background exactly as [SpeechSession]'s own class doc
 * describes, so a subsequent [playFrom] resumes instantly with MORE audio already generated than
 * before, rather than starting over. Switching model/voice/params (which unloads the engine) still
 * needs the stronger [abandon], which fully cancels generation.
 */
class ReaderPlaybackController(
    private val graph: AppGraph,
    private val player: SpeechPlayer,
    private val scope: CoroutineScope,
    private val notifier: GenerationNotifier,
    private val statsRecorder: PlaybackStatsRecorder = PlaybackStatsRecorder(graph),
) {
    /** One document/voice/parameter selection a caller wants to play or generate. */
    data class Selection(
        val descriptor: ModelDescriptor,
        val voiceId: String,
        val text: String,
        val paramValues: Map<String, Float>,
        val longDocumentMode: Boolean,
    ) {
        val speed: Float get() = SynthesisParams(paramValues).speed
    }

    /** The transport's one SSOT snapshot - see this class's kdoc for why every field here is
     * derived from [SpeechSession.state], never written by hand from an action function. */
    data class State(
        val playing: Boolean = false,
        val paused: Boolean = false,
        /**
         * Generation is still filling the buffer. Deliberately independent of [playing]: stopping
         * audio does NOT stop generation, so a user can listen, stop, come back later and find the
         * document already synthesized ahead. Derived from the session, never written by hand.
         */
        val generating: Boolean = false,
        /** Sentences generated so far, for the "still generating" readout. */
        val sentencesReady: Int = 0,
        val currentSentenceIndex: Int? = null,
        val playbackElapsedMillis: Long = 0,
        val playbackTotalMillis: Long = 0,
        val stats: GenerationStats? = null,
        val status: String? = null,
        val resumeSentenceIndex: Int? = null,
    )

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()

    private data class GenKey(
        val modelId: String,
        val voiceId: String,
        val text: String,
        val paramValues: Map<String, Float>,
    )

    // One retained generation attempt: a SpeechSession plus the document sentence its buffer's
    // index 0 corresponds to, and a running cache of chunk sizes so elapsed-sample math (used for
    // both the scrubber and listening-stats deltas below) is O(1) amortized rather than re-summed
    // from scratch on every tick.
    private class Attempt(
        val key: GenKey,
        val fromSentence: Int,
        val session: SpeechSession,
        val sampleRate: Int,
    ) {
        private var cachedUpTo = 0
        private var cachedSamples = 0L
        var lastRecordedSamples = 0L

        fun covers(sentence: Int): Boolean {
            if (sentence < fromSentence) return false
            return sentence - fromSentence < session.state.value.sentencesReady
        }

        fun bufferIndexFor(sentence: Int): Int = sentence - fromSentence

        /** Exact sample count delivered up to local buffer index [localIndex] (a seek backward
         * resets and re-sums; forward progress is O(1) amortized). */
        fun sampleCountUpTo(localIndex: Int): Long {
            if (localIndex < cachedUpTo) {
                cachedUpTo = 0
                cachedSamples = 0L
            }
            while (cachedUpTo < localIndex) {
                cachedSamples += session.producer.audio.chunkAt(cachedUpTo).size
                cachedUpTo++
            }
            return cachedSamples
        }
    }

    private var attempt: Attempt? = null
    private var generationJob: Job? = null
    private var progressJob: Job? = null

    /** The saved resume point for [documentId], but only if it still describes what would be
     * generated now (same engine/voice/speed) - see `TtsViewModel.play`'s original kdoc for why. */
    fun validatedResumeIndex(
        descriptor: ModelDescriptor,
        voiceId: String,
        speed: Float,
        documentId: String,
    ): Int? {
        val saved = graph.documentMemory.resume(documentId) ?: return null
        if (saved.sentenceIndex <= 0) return null
        if (saved.engineId != descriptor.engineId) return null
        if (saved.voiceId != voiceId) return null
        if (saved.speed != speed) return null
        return saved.sentenceIndex
    }

    /**
     * Play (generate-and-stream, or seek within what is already generated) [selection] from
     * document sentence [startSentenceIndex]. Reuses the retained [Attempt] when its key matches
     * and it already covers [startSentenceIndex] (pure seek, no re-synthesis); otherwise abandons
     * whatever was retained and starts a fresh one.
     */
    fun playFrom(
        selection: Selection,
        startSentenceIndex: Int,
        documentId: String,
        outputSink: AudioSink,
        onEngineLoaded: (ModelDescriptor) -> Unit,
        onModelLoaded: (String?) -> Unit,
    ) {
        val key = keyFor(selection)
        setTotalEstimate(selection, startSentenceIndex)
        val reusable = attempt?.takeIf { it.key == key && it.covers(startSentenceIndex) }
        if (reusable != null) {
            player.play(reusable.session, outputSink, reusable.bufferIndexFor(startSentenceIndex))
            return
        }
        val fresh = createAttempt(selection, startSentenceIndex)
        mutableState.update { it.copy(status = "Loading voice…", stats = null) }
        player.play(fresh.session, outputSink, fromIndex = 0)
        launchGeneration(fresh, selection, startSentenceIndex, documentId, onEngineLoaded, onModelLoaded)
    }

    /** Generate [selection] WITHOUT playing it (the "Generate" action) - the same one generation
     * path, just never attached to the shared player. */
    fun generateOnly(
        selection: Selection,
        documentId: String,
        onEngineLoaded: (ModelDescriptor) -> Unit,
        onModelLoaded: (String?) -> Unit,
    ) {
        val fresh = createAttempt(selection, startSentenceIndex = 0)
        mutableState.update { it.copy(status = "Generating…", stats = null) }
        launchGeneration(fresh, selection, startSentenceIndex = 0, documentId, onEngineLoaded, onModelLoaded)
    }

    /** Pause whichever run is currently attached to the shared player. */
    fun pause() = player.pause()

    /** Resume whichever run is currently attached to the shared player - no re-synthesis. */
    fun resume() = player.resume()

    /**
     * Stop audible playback NOW (hardware halt + consumer teardown) and remember where it got to.
     * Generation, if still running, is deliberately left alone - see this class's kdoc.
     */
    fun stop(
        descriptor: ModelDescriptor?,
        voiceId: String?,
        speed: Float,
        documentId: String?,
    ) {
        rememberPosition(descriptor, voiceId, speed, documentId)
        player.stop(attempt?.session)
        notifier.clear()
        mutableState.update {
            it.copy(playbackElapsedMillis = 0, playbackTotalMillis = 0, currentSentenceIndex = null)
        }
    }

    /**
     * The stronger teardown for a document/voice/parameter change (or ViewModel destruction):
     * [stop] plus fully cancelling generation and releasing the retained buffer (its spill scratch
     * file, if any). Never call this for a pause/seek - it throws away ahead-generated audio.
     */
    fun abandon(
        descriptor: ModelDescriptor?,
        voiceId: String?,
        speed: Float,
        documentId: String?,
    ) {
        stop(descriptor, voiceId, speed, documentId)
        val current = attempt
        if (current != null) checkpointListening(current, current.session.state.value.currentSentence)
        generationJob?.cancel()
        generationJob = null
        progressJob?.cancel()
        progressJob = null
        if (current != null) {
            player.release(current.session)
            current.session.close()
        }
        attempt = null
    }

    private fun keyFor(selection: Selection) =
        GenKey(selection.descriptor.modelId, selection.voiceId, selection.text, selection.paramValues)

    private fun createAttempt(
        selection: Selection,
        startSentenceIndex: Int,
    ): Attempt {
        val descriptor = selection.descriptor
        val audio = newGeneratedAudio(descriptor, selection.longDocumentMode)
        val session = SpeechSession(scope, Dispatchers.IO, descriptor.sampleRate, audio)
        val fresh = Attempt(keyFor(selection), startSentenceIndex, session, descriptor.sampleRate)
        val previous = attempt
        if (previous != null && previous !== fresh) previous.session.close()
        attempt = fresh
        trackProgress(fresh)
        return fresh
    }

    private fun newGeneratedAudio(
        descriptor: ModelDescriptor,
        longDocumentMode: Boolean,
    ): GeneratedAudio {
        if (!longDocumentMode) return GeneratedAudio()
        return GeneratedAudio(ChunkSpill(graph.newSpillFile(), descriptor.sampleRate * SPILL_WINDOW_SECONDS))
    }

    private fun launchGeneration(
        fresh: Attempt,
        selection: Selection,
        startSentenceIndex: Int,
        documentId: String,
        onEngineLoaded: (ModelDescriptor) -> Unit,
        onModelLoaded: (String?) -> Unit,
    ) {
        val effectiveText = sentencesFrom(selection.text, startSentenceIndex)
        generationJob?.cancel()
        generationJob =
            scope.launch {
                val ok =
                    runGeneration(
                        fresh,
                        selection.descriptor,
                        selection.voiceId,
                        selection.paramValues,
                        effectiveText,
                        startSentenceIndex,
                        documentId,
                        onEngineLoaded,
                    )
                onModelLoaded(if (ok) selection.descriptor.modelId else null)
            }
    }

    @Suppress("LongParameterList")
    private suspend fun runGeneration(
        attempt: Attempt,
        descriptor: ModelDescriptor,
        voiceId: String,
        paramValues: Map<String, Float>,
        effectiveText: String,
        startSentenceIndex: Int,
        documentId: String,
        onEngineLoaded: (ModelDescriptor) -> Unit,
    ): Boolean {
        val totalSentencesHint = TextChunker.intoSentences(effectiveText).size
        val loaded =
            runCatching {
                // switchTo()/engine.load() blocks on synchronous weight loading (issue #18-4b) - off
                // Main so generation never freezes the UI.
                withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                graph.engineManager.currentEngine ?: error("engine failed to load")
            }
        val engine = loaded.getOrNull()
        if (engine == null) {
            recordFailure(attempt, descriptor, voiceId, paramValues, documentId, startSentenceIndex, loaded.exceptionOrNull()!!)
            return false
        }
        onEngineLoaded(descriptor)
        val params = SynthesisParams(paramValues)
        val totalWords = WordCounter.count(effectiveText)
        notifier.start(totalSentencesHint)
        val flow =
            engine.synthesize(effectiveText, voiceId, params)
                .trackGeneration(descriptor.sampleRate, totalWords = totalWords, totalChunks = totalSentencesHint)
                .onEach { (_, stats) ->
                    notifier.update(stats)
                    // "Loading voice…"/"Generating…" only cover the gap before audio exists; once
                    // the first chunk lands, the live stats readout takes over.
                    mutableState.update { it.copy(stats = stats, status = null) }
                }.map { it.first }
        attempt.session.start(flow, totalSentencesHint)
        attempt.session.state.first { it.isComplete || it.generationError != null }
        notifier.clear()
        val error = attempt.session.state.value.generationError
        if (error != null) {
            recordFailure(attempt, descriptor, voiceId, paramValues, documentId, startSentenceIndex, error)
            return false
        }
        graph.documentMemory.forget(documentId)
        mutableState.update { it.copy(resumeSentenceIndex = null) }
        statsRecorder.recordGeneration(effectiveText)
        return true
    }

    // The resume point must follow what has been HEARD, not what has been generated (issue #28's
    // original bug) - min(sentencesReady, currentSentence) is exactly that: currentSentence only
    // ever advances via the consumer actually delivering a chunk to the sink.
    @Suppress("LongParameterList")
    private fun recordFailure(
        attempt: Attempt,
        descriptor: ModelDescriptor,
        voiceId: String,
        paramValues: Map<String, Float>,
        documentId: String,
        startSentenceIndex: Int,
        error: Throwable,
    ) {
        notifier.clear()
        val s = attempt.session.state.value
        val heard = minOf(s.sentencesReady, s.currentSentence)
        val resumeIndex = startSentenceIndex + heard
        val speed = SynthesisParams(paramValues).speed
        graph.documentMemory.record(DocumentPosition(documentId, descriptor.engineId, voiceId, speed, resumeIndex, descriptor.modelId))
        mutableState.update {
            it.copy(
                status = FailureExplainer.explain(error, FailureContext.GENERATING),
                resumeSentenceIndex = resumeIndex.takeIf { idx -> idx > 0 },
            )
        }
    }

    private fun rememberPosition(
        descriptor: ModelDescriptor?,
        voiceId: String?,
        speed: Float,
        documentId: String?,
    ) {
        val a = attempt ?: return
        if (descriptor == null || voiceId == null || documentId == null) return
        val heard = a.fromSentence + a.session.state.value.currentSentence
        if (heard <= 0) return
        graph.documentMemory.record(DocumentPosition(documentId, descriptor.engineId, voiceId, speed, heard, descriptor.modelId))
        mutableState.update { it.copy(resumeSentenceIndex = heard) }
    }

    // One collector per Attempt, for its whole lifetime - reused across every seek within the same
    // session (state reactively follows whichever consumer run is current; see SpeechSession's own
    // class doc), so this never needs restarting on a seek the way the old per-call progressJob did.
    private fun trackProgress(attempt: Attempt) {
        progressJob?.cancel()
        progressJob =
            scope.launch {
                var wasActive = false
                attempt.session.state.collect { s ->
                    val active = s.isPlaying || s.isPaused
                    val elapsedSamples = attempt.sampleCountUpTo(s.currentSentence)
                    // A run just ended (natural completion, a barge-in from another screen, or an
                    // explicit stop) - checkpoint listening time here so every ending is counted
                    // exactly once, not just an explicit Stop tap.
                    if (wasActive && !active) checkpointListening(attempt, s.currentSentence)
                    wasActive = active
                    val elapsedMillis = if (attempt.sampleRate > 0) elapsedSamples * MILLIS_PER_SECOND / attempt.sampleRate else 0L
                    mutableState.update {
                        it.copy(
                            playing = s.isPlaying,
                            paused = s.isPaused,
                            generating = s.isGenerating,
                            sentencesReady = s.sentencesReady,
                            currentSentenceIndex = if (active) attempt.fromSentence + s.currentSentence else null,
                            playbackElapsedMillis = if (active) elapsedMillis else it.playbackElapsedMillis,
                        )
                    }
                }
            }
    }

    private fun checkpointListening(
        attempt: Attempt,
        localIndex: Int,
    ) {
        val samples = attempt.sampleCountUpTo(localIndex)
        val delta = samples - attempt.lastRecordedSamples
        attempt.lastRecordedSamples = samples
        if (delta <= 0) return
        val millis = if (attempt.sampleRate > 0) delta * MILLIS_PER_SECOND / attempt.sampleRate else 0L
        statsRecorder.recordListening(attempt.key.modelId, millis)
    }

    private fun setTotalEstimate(
        selection: Selection,
        startSentenceIndex: Int,
    ) {
        val effectiveText = sentencesFrom(selection.text, startSentenceIndex)
        val seconds = ListeningTimeEstimator.estimateSeconds(WordCounter.count(effectiveText), selection.speed)
        mutableState.update {
            it.copy(playbackTotalMillis = (seconds * MILLIS_PER_SECOND).toLong(), playbackElapsedMillis = 0)
        }
    }

    // The text from sentence [startIndex] onward, re-joined for the one generation path - identical
    // to the old TtsViewModel.sentencesFrom, moved here since generation now lives here.
    private fun sentencesFrom(
        text: String,
        startIndex: Int,
    ): String {
        if (startIndex <= 0) return text
        val remaining = TextChunker.intoSentences(text).drop(startIndex)
        return remaining.joinToString(" ").ifBlank { text }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L

        // Seconds of audio kept resident in RAM before long-document mode spills older chunks to
        // disk (issue #34) - same policy constant the old TtsViewModel used.
        const val SPILL_WINDOW_SECONDS = 30
    }
}
