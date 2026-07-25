package com.phonetts.app.textimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.phonetts.core.text.extract.PageCountAwareTextExtractor
import com.phonetts.core.text.extract.TextExtractorRegistry
import com.phonetts.core.text.extract.UnsupportedFileTypeException
import com.phonetts.core.text.quality.CleanupOptions
import com.phonetts.core.text.quality.DocumentCleanup
import com.phonetts.core.text.quality.ImportQualityAnalyzer
import com.phonetts.core.text.quality.ImportQualityReport
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/** The result of importing a document (issue #138): the cleaned-up text, plus how the import went. */
data class ImportedDocument(
    val text: String,
    val quality: ImportQualityReport,
)

/**
 * Reads a user-picked file (via the Storage Access Framework) and turns it into text for the
 * synthesizer input box. The format logic is entirely the modular [TextExtractorRegistry]'s job -
 * this class only bridges a content Uri to bytes + name + MIME. Adding a new file type is
 * registering one more extractor, nothing here changes.
 */
class FileTextImporter(
    private val context: Context,
    private val registry: TextExtractorRegistry = defaultRegistry(),
    private val cleanupOptions: CleanupOptions = CleanupOptions(),
) {
    init {
        // PDFBox-Android needs its resource loader initialized once before any PDF extraction.
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /**
     * Extract text from [uri], or throw (UnsupportedFileTypeException) if no extractor handles it.
     * Kept String-returning for existing callers (`TtsViewModel.importTextFrom`); it already benefits
     * from [DocumentCleanup] (header/footer stripping, page-number removal, hyphenation repair,
     * wrapped-line joining, citation-marker stripping) since that changes only the CONTENT of the
     * string, not its type. It does NOT surface [ImportQualityReport] - callers that want to tell the
     * user when an import likely didn't go well (issue #138 priority 3) should call
     * [importWithReport] instead. See that function's doc for the one-line change that needs.
     */
    fun importText(uri: Uri): String = importWithReport(uri).text

    /**
     * Same extraction as [importText], but also returns [ImportQualityReport] - the honest signal
     * for "did this import actually go well" (issue #138 priority 1: never silently hand back
     * near-empty or mangled text with no explanation). [ImportedDocument.text] is already the
     * cleaned-up version ([DocumentCleanup]); the quality verdict is judged against the RAW
     * extraction, since cleanup fixes exactly the symptoms the verdict looks for.
     *
     * `TtsViewModel.importTextFrom` (out of this task's file ownership) currently calls [importText]
     * and only ever sees the text. To surface the verdict to the user, swap that one call site to
     * this function and show `result.quality.message` in status whenever `result.quality.isProblem`
     * is true - e.g.:
     * ```
     * val result = graph.fileTextImporter.importWithReport(uri)
     * val status = if (result.quality.isProblem) result.quality.message else "Imported text"
     * mutableState.update { it.copy(busy = false, text = result.text, status = status) }
     * ```
     */
    fun importWithReport(uri: Uri): ImportedDocument {
        val fileName = displayName(uri) ?: uri.lastPathSegment ?: "file"
        val mimeType = context.contentResolver.getType(uri)
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("could not read $uri")
        val extractor =
            registry.extractorFor(fileName, mimeType) ?: throw UnsupportedFileTypeException(fileName, mimeType)
        val rawText = extractor.extract(bytes)
        val pageCount = (extractor as? PageCountAwareTextExtractor)?.pageCount(bytes)
        val quality = ImportQualityAnalyzer.analyze(rawText, bytes.size.toLong(), pageCount)
        val cleanedText = DocumentCleanup.clean(rawText, cleanupOptions)
        return ImportedDocument(cleanedText, quality)
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }

    companion object {
        /** The built-in pure-JVM extractors (:core) plus the PDF extractor (:app). */
        fun defaultRegistry(): TextExtractorRegistry =
            TextExtractorRegistry.withDefaults().apply { register(PdfTextExtractor()) }
    }
}
