package com.phonetts.core.text.extract

/**
 * Reads Markdown (.md/.markdown, `text/markdown`) and strips the formatting syntax so the
 * synthesizer speaks the prose, not the punctuation (e.g. "## Title" → "Title", "**bold**" →
 * "bold", "[text](url)" → "text"). Deliberately lightweight — it cleans the common marks, it is
 * not a full CommonMark parser.
 */
class MarkdownTextExtractor : TextExtractor {
    override val id: String = "markdown"

    override fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean {
        if (fileExtension(fileName) in EXTENSIONS) return true
        return mimeType == "text/markdown"
    }

    override fun extract(bytes: ByteArray): String = stripMarkdown(bytes.toString(Charsets.UTF_8))

    private fun stripMarkdown(text: String): String {
        var out = text
        out = IMAGE.replace(out, "") // ![alt](src) -> (dropped)
        out = LINK.replace(out, "$1") // [text](url) -> text
        out = FENCED_CODE.replace(out, "") // ``` code fences ``` -> (dropped)
        out = HEADING_PREFIX.replace(out, "") // leading #, >, list markers
        out = EMPHASIS.replace(out, "$2") // **x** *x* _x_ `x` -> x
        out = HORIZONTAL_RULE.replace(out, "") // --- *** ___
        return out.lines().joinToString("\n") { it.trim() }.trim()
    }

    companion object {
        val EXTENSIONS = setOf("md", "markdown")
        private val IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
        private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val FENCED_CODE = Regex("""```[\s\S]*?```""")
        private val HEADING_PREFIX = Regex("""(?m)^\s{0,3}(#{1,6}\s+|>\s+|[-*+]\s+|\d+\.\s+)""")
        private val EMPHASIS = Regex("""([*_`]{1,3})(.*?)\1""")
        private val HORIZONTAL_RULE = Regex("""(?m)^\s*([-*_])\1{2,}\s*$""")
    }
}
