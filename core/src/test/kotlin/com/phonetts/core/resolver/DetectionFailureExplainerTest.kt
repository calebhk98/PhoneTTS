package com.phonetts.core.resolver

import com.phonetts.core.model.ModelBundle
import com.phonetts.core.testing.FakeEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectionFailureExplainerTest {
    private val explainer = DetectionFailureExplainer()

    @Test
    fun `a bare weights file with no companion files is reported as such`() {
        val bundle = ModelBundle(id = "mystery-1", fileNames = setOf("weights.onnx"))
        val engineA = FakeEngine(id = "engine-a")
        val engineB = FakeEngine(id = "engine-b")

        val report = explainer.explain(bundle, listOf(engineA, engineB))

        assertTrue(report.isBareWeightsFile)
        assertTrue(report.presentCompanionFiles.isEmpty())
        assertTrue(report.claimedByEngineIds.isEmpty())
        assertEquals(listOf("engine-a", "engine-b"), report.checkedEngineIds)
        assertTrue(report.summary.contains("bare weights file", ignoreCase = true))
        assertFalse(report.summary.contains("spec"))
        assertFalse(report.summary.contains("inspect()"))
    }

    @Test
    fun `present and missing companion file categories are both reported`() {
        val bundle =
            ModelBundle(
                id = "partial-1",
                fileNames = setOf("weights.onnx", "config.json", "tokenizer.json"),
            )
        val engine = FakeEngine(id = "engine-a")

        val report = explainer.explain(bundle, listOf(engine))

        assertTrue(report.presentCompanionFiles.containsAll(listOf("config.json", "tokenizer")))
        assertTrue(report.missingCompanionFiles.containsAll(listOf("phoneme map", "voice/speaker table")))
        assertFalse(report.isBareWeightsFile)
        assertTrue(report.summary.contains("phoneme map"))
        assertTrue(report.summary.contains("pick an engine", ignoreCase = true))
        assertFalse(report.summary.contains("spec"))
        assertFalse(report.summary.contains("inspect()"))
    }

    @Test
    fun `no registered engines is reported distinctly from a rejected bundle`() {
        val bundle = ModelBundle(id = "orphan-1", fileNames = setOf("weights.onnx"))

        val report = explainer.explain(bundle, engines = emptyList())

        assertTrue(report.checkedEngineIds.isEmpty())
        assertTrue(report.summary.contains("no engines", ignoreCase = true))
        assertFalse(report.summary.contains("spec"))
    }

    @Test
    fun `an engine that actually claims the bundle is surfaced, not hidden`() {
        val bundle = ModelBundle(id = "claimed-1", fileNames = setOf("weights.onnx"))
        val claimingEngine = FakeEngine(id = "engine-a", claims = { it.id == bundle.id })

        val report = explainer.explain(bundle, listOf(claimingEngine))

        assertEquals(listOf("engine-a"), report.claimedByEngineIds)
        assertTrue(report.summary.contains("recognized", ignoreCase = true))
        assertTrue(report.summary.contains("wasn't really a failure", ignoreCase = true))
    }

    @Test
    fun `a raw PyTorch or safetensors bundle is explained by its format, not missing companions`() {
        val bundle =
            ModelBundle(id = "kokoro-torch", fileNames = setOf("model.safetensors", "config.json"))
        val engine = FakeEngine(id = "engine-a")

        val report = explainer.explain(bundle, listOf(engine))

        assertTrue(report.weightFormatIssue!!.contains("PyTorch"))
        assertTrue(report.summary.contains("PyTorch"))
        assertTrue(report.summary.contains("cannot run", ignoreCase = true))
        // "cannot run", never "coming soon" - raw torch has no on-device runtime here, ever.
        assertFalse(report.summary.contains("coming soon", ignoreCase = true))
    }

    @Test
    fun `an Apple MLX bundle is called out as wrong-platform, not not-yet`() {
        val bundle =
            ModelBundle(id = "Kokoro-82M-MLX-bf16", fileNames = setOf("model.safetensors", "config.json"))

        val report = explainer.explain(bundle, listOf(FakeEngine(id = "engine-a")))

        assertTrue(report.summary.contains("MLX"))
        assertTrue(report.summary.contains("Apple"))
        assertTrue(report.summary.contains("cannot run", ignoreCase = true))
        assertFalse(report.summary.contains("coming soon", ignoreCase = true))
    }

    @Test
    fun `an Apple CoreML mlpackage is called out as wrong-platform`() {
        val bundle =
            ModelBundle(
                id = "coreml-voice",
                fileNames = setOf("Model.mlpackage/weights.bin", "Model.mlpackage/Manifest.json"),
            )

        val report = explainer.explain(bundle, listOf(FakeEngine(id = "engine-a")))

        assertTrue(report.summary.contains("CoreML"))
        assertTrue(report.summary.contains("Apple"))
        assertTrue(report.summary.contains("cannot run", ignoreCase = true))
    }

    @Test
    fun `a bare gguf without its manifest is explained by the missing sidecar`() {
        val bundle = ModelBundle(id = "orpheus-tts", fileNames = setOf("orpheus-3b.gguf"))

        val report = explainer.explain(bundle, listOf(FakeEngine(id = "engine-a")))

        assertTrue(report.summary.contains("GGUF"))
        assertTrue(report.summary.contains("manifest") || report.summary.contains(".gguf.json"))
    }

    @Test
    fun `a plain onnx bundle keeps the companion-file explanation, not a format one`() {
        val bundle = ModelBundle(id = "onnx-only", fileNames = setOf("model.onnx"))

        val report = explainer.explain(bundle, listOf(FakeEngine(id = "engine-a")))

        assertEquals(null, report.weightFormatIssue)
        assertTrue(report.summary.contains("bare weights file", ignoreCase = true))
    }

    @Test
    fun `explaining does not mutate resolver state or require a Resolver at all`() {
        // The explainer takes only a bundle and a plain engine list -- no Resolver, no
        // OverrideStore -- proving it is read-only narration bolted onto nothing stateful.
        val bundle = ModelBundle(id = "standalone-1", fileNames = setOf("weights.bin"))
        val engine = FakeEngine(id = "engine-a")

        explainer.explain(bundle, listOf(engine))
        val secondCallReport = explainer.explain(bundle, listOf(engine))

        assertEquals(listOf("engine-a"), secondCallReport.checkedEngineIds)
    }
}
