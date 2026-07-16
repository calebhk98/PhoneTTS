package com.phonetts.engines.piper

import com.phonetts.core.engine.ModelInput
import com.phonetts.core.engine.TextFrontend
import com.phonetts.core.text.Phonemizer

/**
 * Piper's text frontend: espeak-ng phonemization (shared, via [EngineContext.phonemizer] —
 * the [phonemizer] injected here) followed by the model's OWN phoneme->id mapping, which is
 * per-voice data ([phonemeIdMap], read from that voice's `.onnx.json` sidecar) — never a
 * shared/global table (spec §5.2).
 *
 * Mirrors Piper's own `phonemes_to_ids`: wrap the phoneme sequence in BOS/EOS markers and
 * interleave a PAD id between every phoneme. Any phoneme absent from [phonemeIdMap] is dropped
 * rather than crashing synthesis on one unrecognized symbol.
 */
internal class PiperFrontend(
    private val phonemizer: Phonemizer,
    private val phonemeIdMap: Map<String, List<Long>>,
) : TextFrontend {
    override fun toModelInput(
        text: String,
        language: String,
    ): ModelInput {
        val phonemeString = phonemizer.phonemize(text, language)
        return ModelInput(tokenIds = buildIdSequence(phonemeString).toLongArray())
    }

    private fun buildIdSequence(phonemeString: String): List<Long> {
        val ids = mutableListOf<Long>()
        phonemeIdMap[BOS]?.let(ids::addAll)
        val pad = phonemeIdMap[PAD]
        for (ch in phonemeString) {
            appendPhoneme(ids, ch.toString(), pad)
        }
        phonemeIdMap[EOS]?.let(ids::addAll)
        return ids
    }

    private fun appendPhoneme(
        ids: MutableList<Long>,
        phoneme: String,
        pad: List<Long>?,
    ) {
        val mapped = phonemeIdMap[phoneme] ?: return
        ids.addAll(mapped)
        pad?.let(ids::addAll)
    }

    private companion object {
        // Piper's well-known special symbols (rhasspy/piper1-gpl `phonemize_codepoints`/`phonemes_to_ids`).
        const val BOS = "^"
        const val EOS = "$"
        const val PAD = "_"
    }
}
