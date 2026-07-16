package com.phonetts.core.text.extract

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextExtractorsTest {
    // --- plain text ---

    @Test
    fun plainTextSupportsByExtensionAndMimeAndDecodesUtf8() {
        val e = PlainTextExtractor()
        assertTrue(e.supports("notes.txt", null))
        assertTrue(e.supports("whatever", "text/plain"))
        assertFalse(e.supports("doc.pdf", "application/pdf"))
        assertEquals("héllo", e.extract("héllo".toByteArray(Charsets.UTF_8)))
    }

    // --- markdown ---

    @Test
    fun markdownStripsCommonSyntax() {
        val md =
            """
            # Title

            Some **bold** and _italic_ and a [link](https://example.com).

            - item one
            - item two
            """.trimIndent()
        val text = MarkdownTextExtractor().extract(md.toByteArray())
        assertTrue(text.contains("Title"))
        assertFalse(text.contains("#"))
        assertTrue(text.contains("Some bold and italic and a link."))
        assertFalse(text.contains("["), "link syntax should be gone: $text")
        assertTrue(text.contains("item one"))
    }

    // --- docx ---

    private fun docxBytes(documentXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(documentXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun docxExtractsParagraphsInOrder() {
        val xml =
            "<w:document><w:body>" +
                "<w:p><w:r><w:t>Hello there.</w:t></w:r></w:p>" +
                "<w:p><w:r><w:t>Second &amp; last</w:t></w:r></w:p>" +
                "</w:body></w:document>"
        val text = DocxTextExtractor().extract(docxBytes(xml))
        assertEquals("Hello there.\nSecond & last", text)
    }

    @Test
    fun docxRejectsAFileWithoutDocumentXml() {
        val notDocx = docxBytes("").let { ByteArray(4) } // 4 arbitrary bytes, not a zip with document.xml
        assertFailsWith<IllegalArgumentException> { DocxTextExtractor().extract(notDocx) }
    }

    // --- registry (modularity + fail-closed) ---

    @Test
    fun registryRoutesToTheRightExtractorAndFailsClosedOtherwise() {
        val registry = TextExtractorRegistry.withDefaults()
        assertEquals("plain-text", registry.extractorFor("a.txt")?.id)
        assertEquals("markdown", registry.extractorFor("a.md")?.id)
        assertEquals("docx", registry.extractorFor("a.docx")?.id)
        assertFailsWith<UnsupportedFileTypeException> { registry.extract("scan.pdf", ByteArray(0), "application/pdf") }
    }

    @Test
    fun aNewFileTypeIsAddedByRegisteringOneExtractor() {
        val csv =
            object : TextExtractor {
                override val id = "csv"

                override fun supports(
                    fileName: String,
                    mimeType: String?,
                ) = fileExtension(fileName) == "csv"

                override fun extract(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).replace(",", " ")
            }
        val registry = TextExtractorRegistry.withDefaults()
        registry.register(csv)
        assertEquals("a b c", registry.extract("data.csv", "a,b,c".toByteArray()))
    }
}
