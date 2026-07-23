package com.phonetts.core.text.extract

/** Thrown when no registered extractor handles the imported file (fail-closed, spec §6.2 spirit). */
class UnsupportedFileTypeException(fileName: String, mimeType: String?) :
    Exception("no text extractor for '$fileName' (mime=$mimeType)")

/**
 * The modular registry of [TextExtractor]s. The file-import UI asks it to turn a picked file into
 * text; it picks the first extractor that claims the file and fails closed if none do. Register an
 * extractor for a new format and it is immediately usable - nothing else changes.
 */
class TextExtractorRegistry(extractors: List<TextExtractor> = emptyList()) {
    private val extractors = extractors.toMutableList()

    fun register(extractor: TextExtractor) {
        extractors.add(extractor)
    }

    fun list(): List<TextExtractor> = extractors.toList()

    fun extractorFor(
        fileName: String,
        mimeType: String? = null,
    ): TextExtractor? = extractors.firstOrNull { it.supports(fileName, mimeType) }

    /** Extract text from the file, or throw [UnsupportedFileTypeException] if nothing handles it. */
    fun extract(
        fileName: String,
        bytes: ByteArray,
        mimeType: String? = null,
    ): String {
        val extractor = extractorFor(fileName, mimeType) ?: throw UnsupportedFileTypeException(fileName, mimeType)
        return extractor.extract(bytes)
    }

    companion object {
        /** A registry seeded with the pure-JVM built-in extractors (plain text, Markdown, HTML, .docx). */
        fun withDefaults(): TextExtractorRegistry =
            TextExtractorRegistry(
                listOf(
                    PlainTextExtractor(),
                    MarkdownTextExtractor(),
                    HtmlTextExtractor(),
                    DocxTextExtractor(),
                ),
            )
    }
}
