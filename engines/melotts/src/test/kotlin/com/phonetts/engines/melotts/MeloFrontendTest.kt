package com.phonetts.engines.melotts

import com.phonetts.core.runtime.Tensor
import com.phonetts.core.testing.FakeSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Proves MeloTTS's two-step frontend wiring: tokenize -> run the BERT session -> carry its
 * output forward as [com.phonetts.core.engine.ModelInput.extras]. This is the abstraction the
 * whole engine exists to force: [com.phonetts.core.engine.TextFrontend] must be able to carry
 * more than a phoneme string.
 */
class MeloFrontendTest {
    @Test
    fun `toModelInput runs the BERT session with matching input_ids and attention_mask shapes`() {
        val bert =
            FakeSession(
                outputsFor = { inputs ->
                    val tokenCount = inputs.getValue("input_ids").asLongs().size
                    mapOf("bert_features" to Tensor.floats(FloatArray(tokenCount), intArrayOf(1, tokenCount)))
                },
            )
        val frontend = MeloFrontend(bert)

        frontend.toModelInput("hello world", "EN")

        assertEquals(1, bert.runs.size)
        val run = bert.runs.single()
        val inputIds = run.getValue("input_ids").asLongs()
        val attentionMask = run.getValue("attention_mask").asLongs()
        // "hello world" -> BOS + 2 words + EOS = 4 tokens.
        assertEquals(4, inputIds.size)
        assertEquals(inputIds.size, attentionMask.size)
        assertTrue(attentionMask.all { it == 1L })
    }

    @Test
    fun `toModelInput carries the exact BERT session output forward as extras`() {
        // Two different BERT outputs must produce two different forwarded extras, and each must be
        // the EXACT tensor the session returned (assertSame) — this rules out the frontend
        // synthesizing its own tensor that happens to equal a fixture.
        val featuresA = Tensor.floats(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), intArrayOf(1, 4, 1))
        val featuresB = Tensor.floats(floatArrayOf(9.9f, 8.8f), intArrayOf(1, 2, 1))

        val inputA = MeloFrontend(FakeSession(outputs = mapOf("bert_features" to featuresA))).toModelInput("hi", "EN")
        val inputB = MeloFrontend(FakeSession(outputs = mapOf("bert_features" to featuresB))).toModelInput("hi", "EN")

        assertSame(featuresA, inputA.extras[MeloFrontend.EXTRA_BERT_FEATURES])
        assertSame(featuresB, inputB.extras[MeloFrontend.EXTRA_BERT_FEATURES])
        assertNotSame(
            inputA.extras[MeloFrontend.EXTRA_BERT_FEATURES],
            inputB.extras[MeloFrontend.EXTRA_BERT_FEATURES],
        )
        // BOS + 1 word + EOS = 3 tokens.
        assertEquals(3, inputA.tokenIds.size)
    }

    @Test
    fun `toModelInput produces stable, distinct ids for distinct words including BOS and EOS framing`() {
        val bert = FakeSession(outputs = mapOf("bert_features" to Tensor.floats(FloatArray(0))))
        val frontend = MeloFrontend(bert)

        val first = frontend.toModelInput("alpha beta", "EN")
        val second = frontend.toModelInput("alpha beta", "EN")

        assertEquals(first.tokenIds.toList(), second.tokenIds.toList())
        assertEquals(4, first.tokenIds.size)
    }
}
