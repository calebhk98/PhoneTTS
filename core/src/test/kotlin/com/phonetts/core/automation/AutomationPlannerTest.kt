package com.phonetts.core.automation

import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelParameter
import com.phonetts.core.testing.testDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam test for the automation entry point (issue #41). Proves the request-object parsing and the
 * fail-closed resolve/validate the Android activity relies on — all pure `:core`, no Android SDK.
 */
class AutomationPlannerTest {
    private val piper =
        testDescriptor(
            modelId = "piper-en",
            engineId = "piper",
            voices = listOf(Voice("amy", "Amy", "en"), Voice("ryan", "Ryan", "en")),
            speedRange = 0.5f..2.0f,
        )
    private val kokoro = testDescriptor(modelId = "kokoro-en", engineId = "kokoro")
    private val catalog = listOf(piper, kokoro)

    @Test
    fun ofNormalizesBlankOptionalsToNullAndTrimsText() {
        val request =
            AutomationRequest.of(text = "  hi  ", engineId = "  ", voiceId = "", speed = null, outputUri = " out ")
        assertEquals("hi", request.text)
        assertEquals(null, request.engineId)
        assertEquals(null, request.voiceId)
        assertEquals("out", request.outputUri)
    }

    @Test
    fun blankTextIsRejected() {
        val request = AutomationRequest.of("   ", "piper", null, null, "content://out")
        assertReason(AutomationPlanner.plan(request, catalog), "missing 'text'")
    }

    @Test
    fun missingOutputUriIsRejected() {
        val request = AutomationRequest.of("hello", "piper", null, null, null)
        assertReason(AutomationPlanner.plan(request, catalog), "missing 'outputUri'")
    }

    @Test
    fun unknownEngineFailsClosed() {
        val request = AutomationRequest.of("hello", "does-not-exist", null, null, "content://out")
        assertReason(AutomationPlanner.plan(request, catalog), "no model found for engineId 'does-not-exist'")
    }

    @Test
    fun unknownVoiceFailsClosed() {
        val request = AutomationRequest.of("hello", "piper", "nope", null, "content://out")
        assertReason(AutomationPlanner.plan(request, catalog), "voiceId 'nope'")
    }

    @Test
    fun emptyCatalogIsRejected() {
        val request = AutomationRequest.of("hello", null, null, null, "content://out")
        assertReason(AutomationPlanner.plan(request, emptyList()), "no models are installed")
    }

    @Test
    fun noEngineUsesFirstCatalogModelAndDefaultVoice() {
        val request = AutomationRequest.of("hello", null, null, null, "content://out")
        val plan = planned(AutomationPlanner.plan(request, catalog))
        assertEquals("piper-en", plan.descriptor.modelId)
        assertEquals(piper.defaultVoiceId, plan.voiceId)
    }

    @Test
    fun explicitEngineAndVoiceAreHonored() {
        val request = AutomationRequest.of("hello", "piper", "ryan", null, "content://out")
        val plan = planned(AutomationPlanner.plan(request, catalog))
        assertEquals("piper-en", plan.descriptor.modelId)
        assertEquals("ryan", plan.voiceId)
    }

    @Test
    fun speedRoutesToNativeParamAndIsCoercedIntoRange() {
        val request = AutomationRequest.of("hello", "piper", null, 5.0f, "content://out")
        val plan = planned(AutomationPlanner.plan(request, catalog))
        // Requested 5.0 is clamped to the model's advertised 0.5..2.0 speed range, not resampled.
        assertEquals(2.0f, plan.params.value(ModelParameter.SPEED_ID, -1f))
    }

    @Test
    fun unsetSpeedTakesTheModelDefault() {
        val request = AutomationRequest.of("hello", "piper", null, null, "content://out")
        val plan = planned(AutomationPlanner.plan(request, catalog))
        assertEquals(piper.defaultSpeed, plan.params.value(ModelParameter.SPEED_ID, -1f))
    }

    private fun planned(result: AutomationResult): AutomationPlan {
        assertTrue(result is AutomationResult.Planned, "expected Planned but was $result")
        return result.plan
    }

    private fun assertReason(
        result: AutomationResult,
        fragment: String,
    ) {
        assertTrue(result is AutomationResult.Invalid, "expected Invalid but was $result")
        assertTrue(fragment in result.reason, "reason '${result.reason}' should contain '$fragment'")
    }
}
