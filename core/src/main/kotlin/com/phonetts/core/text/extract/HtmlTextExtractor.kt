package com.phonetts.core.text.extract

/**
 * Reads HTML (.html/.htm/.xhtml, `text/html`) into readable text: drops `<script>`/`<style>`
 * content and comments, turns block elements (paragraphs, headings, list items, line breaks) into
 * line breaks so the structure is audible, strips the remaining tags, and decodes the common HTML
 * entities. Lightweight and dependency-free - enough to read a page's prose aloud, not a full DOM.
 */
class HtmlTextExtractor : TextExtractor {
    override val id: String = "html"

    override fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean = matchesExtensionOrMime(fileName, mimeType, EXTENSIONS, "text/html")

    override fun extract(bytes: ByteArray): String {
        val withoutNoise = stripScriptsStylesAndComments(bytes.toString(Charsets.UTF_8))
        val withBreaks = blockTagsToNewlines(withoutNoise)
        val plain = decodeEntities(TAG.replace(withBreaks, ""))
        return plain.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }

    private fun stripScriptsStylesAndComments(html: String): String =
        html
            .replace(HEAD, " ") // <head> holds <title>/meta we don't want spoken
            .replace(SCRIPT_OR_STYLE, " ")
            .replace(COMMENT, " ")

    private fun blockTagsToNewlines(html: String): String = BLOCK_BOUNDARY.replace(html, "\n")

    private fun decodeEntities(text: String): String {
        var out = text
        NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
        out = NUMERIC_ENTITY.replace(out) { match -> decodeNumericEntity(match) }
        return out
    }

    // Decode &#N; safely: reject out-of-range / overflowing / surrogate code points (leave them as-is)
    // and build astral code points (emoji, some CJK) as UTF-16 surrogate pairs rather than truncating.
    private fun decodeNumericEntity(match: MatchResult): String {
        val code = match.groupValues[1].toIntOrNull() ?: return match.value
        if (code !in 0..MAX_CODE_POINT || code in MIN_SURROGATE..MAX_SURROGATE) return match.value
        return String(Character.toChars(code))
    }

    companion object {
        val EXTENSIONS = setOf("html", "htm", "xhtml")
        private val HEAD = Regex("""<head[^>]*>[\s\S]*?</head>""", RegexOption.IGNORE_CASE)
        private val SCRIPT_OR_STYLE = Regex("""<(script|style)[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
        private val COMMENT = Regex("""<!--[\s\S]*?-->""")

        // Elements whose start or end should read as a line break (paragraphs, headings, lists, rows, breaks).
        private const val BLOCK_TAGS = "p|div|h[1-6]|li|ul|ol|tr|br|section|article|header|footer|blockquote"
        private val BLOCK_BOUNDARY = Regex("""</?($BLOCK_TAGS)[^>]*>""", RegexOption.IGNORE_CASE)
        private val TAG = Regex("""<[^>]+>""")
        private val NUMERIC_ENTITY = Regex("""&#(\d+);""")
        private const val MAX_CODE_POINT = 0x10FFFF
        private const val MIN_SURROGATE = 0xD800
        private const val MAX_SURROGATE = 0xDFFF
        private val NAMED_ENTITIES =
            mapOf(
                "&nbsp;" to " ",
                "&lt;" to "<",
                "&gt;" to ">",
                "&quot;" to "\"",
                "&#39;" to "'",
                "&apos;" to "'",
                // &amp; is decoded LAST (map is ordered) so "&amp;lt;" does not become "<"
                "&amp;" to "&",
            )
    }
}
