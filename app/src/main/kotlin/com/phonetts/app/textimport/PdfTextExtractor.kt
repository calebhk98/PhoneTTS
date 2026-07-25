package com.phonetts.app.textimport

import com.phonetts.core.text.extract.PageCountAwareTextExtractor
import com.phonetts.core.text.extract.TextExtractor
import com.phonetts.core.text.extract.matchesExtensionOrMime
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * The one extractor that needs a library: PDF text extraction via PDFBox-Android (Android has no
 * built-in PDF text API - PdfRenderer only rasterizes). Lives in :app because of that dependency;
 * the plain-text/Markdown/.docx extractors are pure-JVM and live in :core.
 *
 * Requires `PDFBoxResourceLoader.init(applicationContext)` once at app startup (Application.onCreate)
 * before the first extraction.
 *
 * Implements [PageCountAwareTextExtractor] (issue #138) so [FileTextImporter] can feed the real page
 * count into `ImportQualityAnalyzer` - a scanned/image-only PDF still reports its page count even
 * though [extract] returns almost nothing for it, which is exactly the signal that tells the two
 * apart from a short legitimate document.
 */
class PdfTextExtractor : TextExtractor, PageCountAwareTextExtractor {
    override val id: String = "pdf"

    override fun supports(fileName: String, mimeType: String?): Boolean =
        matchesExtensionOrMime(fileName, mimeType, EXTENSIONS, "application/pdf")

    override fun extract(bytes: ByteArray): String =
        PDDocument.load(bytes).use { document -> PDFTextStripper().getText(document).trim() }

    override fun pageCount(bytes: ByteArray): Int? =
        runCatching { PDDocument.load(bytes).use { it.numberOfPages } }.getOrNull()

    companion object {
        val EXTENSIONS = setOf("pdf")
    }
}

