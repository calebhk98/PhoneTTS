package com.phonetts.app.textimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.phonetts.core.text.extract.TextExtractorRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * Reads a user-picked file (via the Storage Access Framework) and turns it into text for the
 * synthesizer input box. The format logic is entirely the modular [TextExtractorRegistry]'s job —
 * this class only bridges a content Uri to bytes + name + MIME. Adding a new file type is
 * registering one more extractor, nothing here changes.
 */
class FileTextImporter(
    private val context: Context,
    private val registry: TextExtractorRegistry = defaultRegistry(),
) {
    init {
        // PDFBox-Android needs its resource loader initialized once before any PDF extraction.
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Extract text from [uri], or throw (UnsupportedFileTypeException) if no extractor handles it. */
    fun importText(uri: Uri): String {
        val fileName = displayName(uri) ?: uri.lastPathSegment ?: "file"
        val mimeType = context.contentResolver.getType(uri)
        val bytes =
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("could not read $uri")
        return registry.extract(fileName, bytes, mimeType)
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
