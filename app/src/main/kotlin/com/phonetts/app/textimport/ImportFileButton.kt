package com.phonetts.app.textimport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.phonetts.core.text.extract.DocxTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A button that lets the user pick a document (.txt/.md/.docx/.pdf/…) and turns it into text for
 * the synthesizer input, in addition to typing/pasting. The set of accepted types comes from the
 * modular [FileTextImporter] registry, so adding a new extractor widens what this accepts with no
 * change here. Reading + extraction run off the main thread.
 */
@Composable
fun ImportFileButton(
    importer: FileTextImporter,
    onImported: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { importer.importText(uri) } }
                    .onSuccess(onImported)
                    .onFailure(onError)
            }
        }

    Button(onClick = { launcher.launch(ACCEPTED_MIME_TYPES) }) {
        Text("Import from file")
    }
}

// Broad enough to surface documents in the picker; the registry still fails closed on anything it
// can't actually extract, so an unsupported pick yields a clear error rather than silent garbage.
private val ACCEPTED_MIME_TYPES =
    arrayOf(
        "text/*",
        "application/pdf",
        DocxTextExtractor.DOCX_MIME,
    )
