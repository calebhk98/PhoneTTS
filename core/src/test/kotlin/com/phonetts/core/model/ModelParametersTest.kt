package com.phonetts.core.model

import com.phonetts.core.engine.Voice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The dynamic/introspective parameter model (CLAUDE.md: everything the UI shows is derived from
 * descriptors). Parameters are the SSOT for "what knobs does this model have?" - speed is derived
 * from them, a model with no knobs reports a locked speed, and an arbitrary future parameter (e.g.
 * emotion) is representable with no new descriptor field.
 */
class ModelParametersTest {
    private fun descriptor(parameters: List<ModelParameter>) =
        ModelDescriptor(
            modelId = "m",
            engineId = "e",
            displayName = "M",
            origin = Origin.BUILT_IN,
            sampleRate = 24_000,
            voices = listOf(Voice("v", "V", "en")),
            defaultVoiceId = "v",
            parameters = parameters,
        )

    @Test
    fun `speed range and default are derived from the declared speed parameter`() {
        val d = descriptor(listOf(ModelParameter.speed(0.5f..2.0f, 1.25f)))

        assertEquals(0.5f..2.0f, d.speedRange)
        assertEquals(1.25f, d.defaultSpeed)
    }

    @Test
    fun `a model with no parameters reports a locked speed and no speed parameter`() {
        val d = descriptor(emptyList())

        assertNull(d.speedParameter, "a model without a speed knob must expose none")
        assertEquals(1.0f..1.0f, d.speedRange, "speed must be locked, not faked")
        assertEquals(1.0f, d.defaultSpeed)
    }

    @Test
    fun `an arbitrary CHOICE parameter (e_g_ emotion) is representable with no new field`() {
        val emotion =
            ModelParameter(
                id = "emotion",
                displayName = "Emotion",
                kind = ModelParameter.Kind.CHOICE,
                choices = listOf("neutral", "happy", "sad"),
                default = 0f,
            )
        val d = descriptor(listOf(ModelParameter.speed(0.5f..2.0f, 1.0f), emotion))

        assertEquals(listOf("speed", "emotion"), d.parameters.map { it.id })
        assertEquals(listOf("neutral", "happy", "sad"), d.parameters.first { it.id == "emotion" }.choices)
    }

    @Test
    fun `a continuous parameter rejects a default outside its range`() {
        assertFailsWith<IllegalArgumentException> { ModelParameter.speed(0.5f..2.0f, 3.0f) }
    }

    @Test
    fun `a choice parameter rejects a default index outside its choices`() {
        assertFailsWith<IllegalArgumentException> {
            ModelParameter("x", "X", ModelParameter.Kind.CHOICE, choices = listOf("a", "b"), default = 5f)
        }
    }

    @Test
    fun `duplicate parameter ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            descriptor(listOf(ModelParameter.speed(0.5f..2.0f, 1.0f), ModelParameter.speed(0.5f..1.5f, 1.0f)))
        }
    }
}
