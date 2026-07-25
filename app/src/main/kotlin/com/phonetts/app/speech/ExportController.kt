package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.audio.transform.TransformChain
import com.phonetts.core.diagnostics.FailureContext
import com.phonetts.core.diagnostics.FailureExplainer
import com.phonetts.core.engine.SynthesisParams
import com.phonetts.core.metrics.RtfEstimator
import com.phonetts.core.metrics.RtfResult
import com.phonetts.core.model.ModelDescriptor
import com.phonetts.core.prefs.DocumentPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Export + voice-sampling, split out of `TtsViewModel` (mission: break up the god object). These are
 * all one-shot consumers of the SAME one generation path ([com.phonetts.core.engine.VoiceEngine.synthesize],
 * spec §6.1) that never attach to playback, so they have no reason to share a file with the
 * [ReaderPlaybackController]/transport concern - export code and playback code can now be reviewed
 * and changed independently.
 *
 * [player] is used ONLY to barge in on whatever might currently be audible before a batch export
 * starts (matching the old `TtsViewModel.sampleAllModels`'s `stop()` call) - export itself always
 * writes straight to a file/encoder, never to the shared audio output.
 */
class ExportController(
    private val graph: AppGraph,
    private val player: SpeechPlayer,
) {
    /** Creates one output file per model for [sampleAllModels], named [fileName] (no extension -
     * the sink adds the export format's). Returning null skips that model without aborting the batch. */
    fun interface FileSink {
        fun create(fileName: String): OutputStream?
    }

    /** Export [text] (already voiced by [descriptor]/[voiceId]/[params]) to [output] as [format],
     * applying [transforms]. Always finalizes [output], even on failure or blank text, so a picked
     * export destination is never left half-open (issue #15/#17-adjacent). */
    suspend fun export(
        descriptor: ModelDescriptor?,
        voiceId: String,
        params: SynthesisParams,
        text: String,
        format: AudioEncoder,
        transforms: TransformChain,
        documentId: String,
        output: OutputStream,
        onResult: (ExportResult) -> Unit,
    ) {
        if (descriptor == null || text.isBlank()) {
            runCatching { output.close() }
            onResult(ExportResult.Skipped)
            return
        }
        var chunksDone = 0
        val result =
            runCatching {
                // output.use wraps the WHOLE body so the destination is finalized on every path,
                // including a switchTo()/engine-load failure before any audio is even generated.
                output.use {
                    withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
                    val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                    val tracked = engine.synthesize(text, voiceId, params).onEach { chunksDone++ }
                    withContext(Dispatchers.IO) { format.encode(tracked, descriptor.sampleRate, it, transforms) }
                }
            }
        result
            .onSuccess {
                graph.documentMemory.forget(documentId)
                onResult(ExportResult.Success)
            }.onFailure { e ->
                graph.documentMemory.record(
                    DocumentPosition(documentId, descriptor.engineId, voiceId, params.speed, chunksDone, descriptor.modelId),
                )
                onResult(ExportResult.Failure(FailureExplainer.explain(e, FailureContext.EXPORTING), chunksDone))
            }
    }

    /** Measure this engine+voice's real render speed on a short phrase (never a guessed RTF). */
    suspend fun sampleVoice(
        descriptor: ModelDescriptor,
        voiceId: String,
        params: SynthesisParams,
    ): Result<RtfResult> =
        runCatching {
            withContext(Dispatchers.IO) {
                graph.engineManager.switchTo(descriptor.engineId, descriptor)
                val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
                RtfEstimator.estimate(engine, voiceId, params, VOICE_SAMPLE_PHRASE, descriptor.sampleRate)
            }
        }

    /**
     * Generate a short spoken sample of EVERY [models] and save each to its own file via [sink], so
     * they can be audited back-to-back. Loads one engine at a time (spec rule 6, [EngineManager.switchTo]
     * unloads the previous), synthesizing the shared [VOICE_SAMPLE_PHRASE] with each model's default
     * voice/params. Best-effort per model - one that fails is skipped and named in the summary.
     */
    suspend fun sampleAllModels(
        models: List<ModelDescriptor>,
        format: AudioEncoder,
        sink: FileSink,
        onProgress: (index: Int, total: Int, displayName: String) -> Unit,
    ): SampleAllResult {
        player.stop()
        val failures = mutableListOf<String>()
        models.forEachIndexed { index, descriptor ->
            onProgress(index + 1, models.size, descriptor.displayName)
            if (!sampleOneModel(descriptor, format, sink)) failures += descriptor.displayName
        }
        return SampleAllResult(saved = models.size - failures.size, total = models.size, failures = failures)
    }

    private suspend fun sampleOneModel(
        descriptor: ModelDescriptor,
        format: AudioEncoder,
        sink: FileSink,
    ): Boolean =
        runCatching {
            withContext(Dispatchers.IO) { graph.engineManager.switchTo(descriptor.engineId, descriptor) }
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            val voiceId = descriptor.defaultVoiceId
            val params = SynthesisParams(descriptor.parameters.associate { it.id to it.default })
            val output = sink.create(sampleBaseName(descriptor)) ?: error("could not create file")
            val audio = engine.synthesize(VOICE_SAMPLE_PHRASE, voiceId, params)
            withContext(Dispatchers.IO) { output.use { format.encode(audio, descriptor.sampleRate, it) } }
        }.isSuccess

    private fun sampleBaseName(descriptor: ModelDescriptor): String =
        descriptor.displayName.replace(FILE_UNSAFE, "_").trim().ifEmpty { descriptor.modelId }

    /** Outcome of one [export] call. */
    sealed interface ExportResult {
        data object Success : ExportResult

        data object Skipped : ExportResult

        data class Failure(val status: String, val resumeSentenceIndex: Int) : ExportResult
    }

    /** Outcome of a whole [sampleAllModels] batch. */
    data class SampleAllResult(val saved: Int, val total: Int, val failures: List<String>) {
        val summary: String
            get() =
                if (failures.isEmpty()) {
                    "Saved $saved sample${if (saved == 1) "" else "s"}"
                } else {
                    "Saved $saved of $total - skipped: ${failures.joinToString()}"
                }
    }

    private companion object {
        const val VOICE_SAMPLE_PHRASE = "The quick brown fox jumps over the lazy dog."
        val FILE_UNSAFE = Regex("""[^A-Za-z0-9 _-]""")
    }
}
