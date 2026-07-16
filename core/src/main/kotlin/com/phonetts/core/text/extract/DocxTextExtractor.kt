package com.phonetts.core.text.extract

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Reads Microsoft Word .docx files. A .docx is a ZIP whose `word/document.xml` holds the body;
 * this pulls the text runs (`<w:t>`) out in document order, turning paragraph ends (`</w:p>`) into
 * line breaks. Pure JVM (java.util.zip), so no Office/parser dependency — enough for reading a
 * document aloud, not a full OOXML implementation. Legacy `.doc` (binary) is intentionally not
 * handled and falls through to the registry's fail-closed path.
 */
class DocxTextExtractor : TextExtractor {
    override val id: String = "docx"

    override fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean = matchesExtensionOrMime(fileName, mimeType, EXTENSIONS, DOCX_MIME)

    override fun extract(bytes: ByteArray): String {
        val documentXml =
            readZipEntry(bytes, DOCUMENT_ENTRY)
                ?: throw IllegalArgumentException("not a valid .docx: missing $DOCUMENT_ENTRY")
        return xmlToText(documentXml)
    }

    private fun readZipEntry(
        bytes: ByteArray,
        entryName: String,
    ): String? =
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == entryName }
                ?.let { zip.readBytes().toString(Charsets.UTF_8) }
        }

    // Walk text runs and paragraph breaks in order; ignore all other tags.
    private fun xmlToText(xml: String): String {
        val builder = StringBuilder()
        for (match in TEXT_OR_PARAGRAPH_END.findAll(xml)) {
            if (match.value == PARAGRAPH_END) {
                builder.append('\n')
                continue
            }
            builder.append(unescapeXml(match.groupValues[1]))
        }
        return builder.toString().lines().joinToString("\n") { it.trim() }.trim()
    }

    private fun unescapeXml(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private val EXTENSIONS = setOf("docx")
        private const val DOCUMENT_ENTRY = "word/document.xml"
        private const val PARAGRAPH_END = "</w:p>"
        private val TEXT_OR_PARAGRAPH_END = Regex("""<w:t[^>]*>(.*?)</w:t>|</w:p>""", RegexOption.DOT_MATCHES_ALL)
    }
}
