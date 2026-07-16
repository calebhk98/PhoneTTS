package com.phonetts.core.text.extract

/**
 * Extracts readable plain text from an imported file so the user can feed a document to the
 * synthesizer instead of typing. One implementation per file family; adding a new type is
 * implementing this and registering it (spec-style modularity — see [TextExtractorRegistry]),
 * with no change anywhere else.
 */
interface TextExtractor {
    val id: String

    /** True if this extractor handles a file with the given name and/or MIME type. */
    fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean

    /** Extract readable plain text from the file's raw bytes. */
    fun extract(bytes: ByteArray): String
}

/** Lower-cased extension of [fileName] without the dot, or "" if it has none. */
internal fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()
