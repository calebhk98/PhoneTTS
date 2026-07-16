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

// Public (not internal): the :app module's PdfTextExtractor implements this same interface
// outside :core and needs both helpers below to build its own supports() the same way.

/** Lower-cased extension of [fileName] without the dot, or "" if it has none. */
fun fileExtension(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

/**
 * The `supports()` check every [TextExtractor] repeats: claim the file if its extension is one of
 * [extensions], or otherwise if [mimeType] equals [mime]. Pulled out so each extractor states only
 * its own extensions/MIME type, not the extension-or-MIME logic itself.
 */
fun matchesExtensionOrMime(
    fileName: String,
    mimeType: String?,
    extensions: Set<String>,
    mime: String,
): Boolean {
    if (fileExtension(fileName) in extensions) return true
    return mimeType == mime
}
