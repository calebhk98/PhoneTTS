package com.phonetts.core.text.extract

/**
 * Extracts readable plain text from an imported file so the user can feed a document to the
 * synthesizer instead of typing. One implementation per file family; adding a new type is
 * implementing this and registering it (spec-style modularity - see [TextExtractorRegistry]),
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

/**
 * Opt-in capability (issue #138): a [TextExtractor] whose format has a natural page count implements
 * this too, so callers that want one - [com.phonetts.core.text.quality.ImportQualityAnalyzer] uses
 * page count to judge whether extraction produced a plausible amount of text per page - can ask for
 * it via `(extractor as? PageCountAwareTextExtractor)`. Deliberately separate from [TextExtractor]
 * itself: most formats (plain text, Markdown, HTML, .docx) have no meaningful page count, and rule 5
 * (no format-specific branching outside the seam) means the caller must not special-case "is this a
 * PDF" - it asks the capability instead, which any current or future paginated format can implement.
 */
interface PageCountAwareTextExtractor {
    /** The source's page count, or null if it couldn't be determined. */
    fun pageCount(bytes: ByteArray): Int?
}

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
