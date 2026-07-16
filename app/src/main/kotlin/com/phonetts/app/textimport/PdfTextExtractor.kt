package com.phonetts.app.textimport

import com.phonetts.core.text.extract.TextExtractor
import com.phonetts.core.text.extract.fileExtension
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * The one extractor that needs a library: PDF text extraction via PDFBox-Android (Android has no
 * built-in PDF text API — PdfRenderer only rasterizes). Lives in :app because of that dependency;
 * the plain-text/Markdown/.docx extractors are pure-JVM and live in :core.
 *
 * Requires `PDFBoxResourceLoader.init(applicationContext)` once at app startup (Application.onCreate)
 * before the first extraction.
 */
class PdfTextExtractor : TextExtractor {
    override val id: String = "pdf"

    override fun supports(fileName: String, mimeType: String?): Boolean {
        if (fileExtension(fileName) == "pdf") return true
        return mimeType == "application/pdf"
    }

    override fun extract(bytes: ByteArray): String =
        PDDocument.load(bytes).use { document -> PDFTextStripper().getText(document).trim() }
}
