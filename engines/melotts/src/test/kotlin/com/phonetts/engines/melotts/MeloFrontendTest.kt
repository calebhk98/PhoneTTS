package com.phonetts.engines.melotts

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakePhonemizer
import com.phonetts.core.testing.FakeSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves MeloTTS's real frontend contract (docs/research/onnx-io.md): `x`/`tone`/`language` built
 * from the real [MeloSymbolTable] with VITS-style blank-interspersing (`commons.intersperse`
 * upstream), and the BERT session's output shaped into `bert` (always zeroed — real MeloTTS zeros
 * the zh tensor for English too) / `ja_bert` (the actual BERT run, resampled to the phoneme count).
 */
class MeloFrontendTest {
    private fun bertSessionReturning(
        tokenCount: Int,
        value: Float,
    ): FakeSession {
        val hidden = FloatArray(tokenCount * 768) { value }
        return FakeSession(outputs = mapOf("1467" to Tensor.floats(hidden, intArrayOf(1, tokenCount, 768))))
    }

    @Test
    fun `toModelInput runs the BERT session with matching input_ids, token_type_ids and attention_mask shapes`() {
        val bert = bertSessionReturning(tokenCount = 3, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer())

        frontend.toModelInput("hello world", "EN")

        assertEquals(1, bert.runs.size)
        val run = bert.runs.single()
        val inputIds = run.getValue("input_ids").asLongs()
        // "hello world" -> BOS + 2 words + EOS = 4 tokens.
        assertEquals(4, inputIds.size)
        assertEquals(inputIds.size, run.getValue("token_type_ids").asLongs().size)
        assertTrue(run.getValue("token_type_ids").asLongs().all { it == 0L })
        assertEquals(inputIds.size, run.getValue("attention_mask").asLongs().size)
        assertTrue(run.getValue("attention_mask").asLongs().all { it == 1L })
    }

    @Test
    fun `toModelInput maps a consonant-vowel IPA string to real symbol ids, BOS-EOS padded and blank-interspersed`() {
        val bert = bertSessionReturning(tokenCount = 1, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "pi" })

        val input = frontend.toModelInput("ignored", "EN")

        val pad = MeloSymbolTable.PAD_ID.toLong()
        val pId = MeloSymbolTable.idFor("p").toLong()
        val iyId = MeloSymbolTable.idFor("iy").toLong()
        // core = [_ , p, iy, _] (BOS/EOS pad framing) -> intersperse(0) -> length 2*4+1 = 9.
        assertEquals(listOf(pad, 0L, pad, pId, 0L, iyId, 0L, pad, 0L), input.tokenIds.toList())

        val consonantTone = (MeloSymbolTable.EN_TONE_OFFSET + 0).toLong()
        val unstressedVowelTone = (MeloSymbolTable.EN_TONE_OFFSET + 1).toLong()
        val tones = input.extras.getValue(MeloFrontend.EXTRA_TONE) as LongArray
        // core tones = [_ (0+offset), p (0+offset), iy (1+offset), _ (0+offset)], blank-interspersed with
        // RAW 0 (not offset) at every even index — matches upstream `commons.intersperse(tones, 0)`.
        assertEquals(
            listOf(0L, consonantTone, 0L, consonantTone, 0L, unstressedVowelTone, 0L, consonantTone, 0L),
            tones.toList(),
        )

        val enLang = MeloSymbolTable.EN_LANGUAGE_ID.toLong()
        val languages = input.extras.getValue(MeloFrontend.EXTRA_LANGUAGE) as LongArray
        assertEquals(listOf(0L, enLang, 0L, enLang, 0L, enLang, 0L, enLang, 0L), languages.toList())
    }

    @Test
    fun `a primary stress mark raises the tone class of the vowel that follows it`() {
        val bert = bertSessionReturning(tokenCount = 1, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "pˈi" })

        val input = frontend.toModelInput("ignored", "EN")

        val tones = input.extras.getValue(MeloFrontend.EXTRA_TONE) as LongArray
        val stressedVowelTone = (MeloSymbolTable.EN_TONE_OFFSET + 2).toLong()
        // core tones = [_, p(unstressed cons), i(STRESSED), _] -> interspersed at odd indices 1,3,5,7.
        assertEquals(stressedVowelTone, tones[5])
    }

    @Test
    fun `a whitespace word break maps to the SP symbol`() {
        val bert = bertSessionReturning(tokenCount = 1, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "p i" })

        val input = frontend.toModelInput("ignored", "EN")

        val spId = MeloSymbolTable.SP_ID.toLong()
        // core = [_, p, SP, i, _] -> interspersed ids at odd indices 1,3,5,7,9.
        assertEquals(spId, input.tokenIds[5])
    }

    @Test
    fun `an unmapped IPA codepoint falls back to the UNK symbol instead of an invalid id`() {
        val bert = bertSessionReturning(tokenCount = 1, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "q" })

        val input = frontend.toModelInput("ignored", "EN")

        val unkId = MeloSymbolTable.UNK_ID.toLong()
        // core = [_, UNK, _] -> interspersed ids at odd indices 1, 3.
        assertEquals(unkId, input.tokenIds[3])
    }

    @Test
    fun `bert is always zero-filled at the acoustic model's 1024-dim shape, matching real MeloTTS's English path`() {
        val bert = bertSessionReturning(tokenCount = 5, value = 9f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "pi" })

        val input = frontend.toModelInput("ignored", "EN")

        val bertTensor = input.extras.getValue(MeloFrontend.EXTRA_BERT) as Tensor
        val tokenCount = input.tokenIds.size
        assertEquals(listOf(1, MeloFrontend.BERT_DIM, tokenCount), bertTensor.shape.toList())
        assertTrue(bertTensor.asFloats().all { it == 0f })
    }

    @Test
    fun `ja_bert carries the real BERT session output resampled to the phoneme count, not zeros`() {
        val bert = bertSessionReturning(tokenCount = 5, value = 9f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "pi" })

        val input = frontend.toModelInput("ignored", "EN")

        val jaBert = input.extras.getValue(MeloFrontend.EXTRA_JA_BERT) as Tensor
        val tokenCount = input.tokenIds.size
        assertEquals(listOf(1, MeloFrontend.JA_BERT_DIM, tokenCount), jaBert.shape.toList())
        assertTrue(jaBert.asFloats().all { it == 9f }, "a constant BERT output must resample to a constant ja_bert")
    }

    @Test
    fun `toModelInput produces stable ids for the same phonemizer output across calls`() {
        val bert = bertSessionReturning(tokenCount = 1, value = 0f)
        val frontend = MeloFrontend(bert, FakePhonemizer { "pi" })

        val first = frontend.toModelInput("alpha beta", "EN")
        val second = frontend.toModelInput("alpha beta", "EN")

        assertEquals(first.tokenIds.toList(), second.tokenIds.toList())
    }
}
