package com.phonetts.app.speech

import android.net.Uri
import com.phonetts.app.AppGraph
import com.phonetts.app.UserPickRequiredException
import com.phonetts.core.diagnostics.FailureContext
import com.phonetts.core.diagnostics.FailureExplainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sideloading a model folder, importing text from a file, and saving the current document to the
 * reading library - three independent "bring something into the app" actions that had no reason to
 * live in the same 1500-line file as playback/export (mission: split the god object).
 */
class SideloadController(private val graph: AppGraph) {
    /** Outcome of [importFolder]. */
    sealed interface ImportResult {
        data object Success : ImportResult

        data class Failure(val status: String, val explanation: String?) : ImportResult
    }

    /** Import a sideloaded model folder at [uri] (issue: bug #1's manual-pick fallback lives in
     * [com.phonetts.core.resolver.Resolver]; this is just the app-side entry point). */
    suspend fun importFolder(uri: Uri): ImportResult =
        runCatching { withContext(Dispatchers.IO) { graph.sideloadCoordinator.importFromTree(uri) } }
            .fold(
                onSuccess = { ImportResult.Success },
                onFailure = { e ->
                    // A failed auto-detection carries the DetectionFailureExplainer's narration
                    // (issue #19-2) - surfaced alongside the generic explained status.
                    val explanation = (e as? UserPickRequiredException)?.explanation
                    ImportResult.Failure(FailureExplainer.explain(e, FailureContext.IMPORTING), explanation)
                },
            )

    /** Import readable text from [uri] (txt/pdf/docx - see [com.phonetts.app.textimport.FileTextImporter]). */
    suspend fun importText(uri: Uri): Result<String> =
        runCatching { withContext(Dispatchers.IO) { graph.fileTextImporter.importText(uri) } }

    /**
     * Save [text] into the reading library (issue #19-5), under the SAME content-derived id
     * [documentId] the resume-position store keys by, so a document saved here lines up with any
     * resume point once reopened. A no-op on blank text.
     */
    fun saveToLibrary(
        text: String,
        documentId: String,
    ) {
        if (text.isBlank()) return
        graph.documentLibrary.add(id = documentId, text = text, savedAtMillis = System.currentTimeMillis())
    }
}
