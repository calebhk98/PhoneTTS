package com.phonetts.app.automation

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.phonetts.app.AppGraph
import com.phonetts.app.PhoneTtsApplication
import com.phonetts.core.audio.export.AudioEncoder
import com.phonetts.core.automation.AutomationPlanner
import com.phonetts.core.automation.AutomationRequest
import com.phonetts.core.automation.AutomationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent as AndroidIntent

/**
 * The Tasker/`adb`/MacroDroid automation entry point (issue #41). A headless, exported activity:
 * it takes an intent with the documented [AutomationIntent] extras, drives the EXISTING
 * `synthesize()` flow to the caller's file, and returns a result intent — it is simply a THIRD
 * consumer of the one generation path (spec §6.1), alongside playback and file export. It adds NO
 * synthesis logic: it reuses [AppGraph]'s `engineManager` + the shared [AudioEncoder]s untouched.
 *
 * Everything the caller supplied is parsed once into a single [AutomationRequest] and threaded
 * through as that object (owner's request on the issue). All inference runs off the main thread on
 * [Dispatchers.IO]. Fail-closed: bad input or a synthesis error returns RESULT_CANCELED with a
 * reason, never a crash or a half-written guess.
 */
class TtsAutomationActivity : ComponentActivity() {
    // No UI: this activity exists only to run one synthesis job and report the result.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PhoneTtsApplication).graph
        val request = AutomationIntent.parse(intent)
        lifecycleScope.launch {
            val outcome =
                runCatching { synthesizeToFile(graph, request) }
                    .getOrElse { Outcome(success = false, outputUri = null, error = it.message ?: "synthesis failed") }
            report(outcome)
        }
    }

    // Plan the request against the live catalog, then drain the ONE synthesis flow into the caller's
    // URI with the shared encoder. Guard-claused (never-nesting): each failure returns an Outcome.
    private suspend fun synthesizeToFile(
        graph: AppGraph,
        request: AutomationRequest,
    ): Outcome {
        val planned = AutomationPlanner.plan(request, graph.catalog.list())
        if (planned is AutomationResult.Invalid) return Outcome(false, null, planned.reason)
        val plan = (planned as AutomationResult.Planned).plan

        val uri = Uri.parse(request.outputUri)
        val stream = contentResolver.openOutputStream(uri) ?: return Outcome(false, null, "cannot open outputUri '$uri'")
        val encoder = encoderFor(graph, uri)

        withContext(Dispatchers.IO) {
            graph.engineManager.switchTo(plan.descriptor.engineId, plan.descriptor)
            val engine = graph.engineManager.currentEngine ?: error("engine failed to load")
            stream.use { out ->
                encoder.encode(
                    engine.synthesize(request.text, plan.voiceId, plan.params),
                    plan.descriptor.sampleRate,
                    out,
                )
            }
        }
        return Outcome(true, request.outputUri, null)
    }

    // Pick the export encoder whose container matches the output file's extension; fall back to the
    // always-available first encoder (WAV). The list + its extensions come from AppGraph.exportFormats
    // (SSOT) — no format string is hardcoded here.
    private fun encoderFor(
        graph: AppGraph,
        uri: Uri,
    ): AudioEncoder {
        val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "")
        return graph.exportFormats.firstOrNull { it.format.fileExtension.equals(extension, ignoreCase = true) }
            ?: graph.exportFormats.first()
    }

    private fun report(outcome: Outcome) {
        val data = AndroidIntent().putExtra(AutomationIntent.RESULT_SUCCESS, outcome.success)
        outcome.outputUri?.let { data.putExtra(AutomationIntent.RESULT_OUTPUT_URI, it) }
        outcome.error?.let { data.putExtra(AutomationIntent.RESULT_ERROR, it) }
        setResult(if (outcome.success) RESULT_OK else RESULT_CANCELED, data)
        finish()
    }

    // The single result carried back to the caller — mirrors the RESULT_* extras of the reply intent.
    private data class Outcome(
        val success: Boolean,
        val outputUri: String?,
        val error: String?,
    )
}
