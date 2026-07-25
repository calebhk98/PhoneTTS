package com.phonetts.core.text.extract

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpubTextExtractorTest {
    private val extractor = EpubTextExtractor()

    // --- helpers: build a real .epub zip in memory (java.util.zip), no binary fixtures ---

    private fun epubBytes(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun containerXml(opfPath: String = "OEBPS/content.opf"): String =
        """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.trimIndent()

    private fun opfXml(
        manifestItems: List<Pair<String, String>>,
        spineIds: List<String>,
    ): String {
        val manifest =
            manifestItems.joinToString("\n") { (id, href) ->
                """<item id="$id" href="$href" media-type="application/xhtml+xml"/>"""
            }
        val spine = spineIds.joinToString("\n") { id -> """<itemref idref="$id"/>""" }
        return """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata/>
              <manifest>
              $manifest
              </manifest>
              <spine>
              $spine
              </spine>
            </package>
            """.trimIndent()
    }

    private fun chapterXhtml(bodyText: String): String = """<html><body><p>$bodyText</p></body></html>"""

    // --- supports() ---

    @Test
    fun supportsByExtensionAndMime() {
        assertTrue(extractor.supports("book.epub", null))
        assertTrue(extractor.supports("whatever", EpubTextExtractor.EPUB_MIME))
        assertFalse(extractor.supports("book.pdf", "application/pdf"))
    }

    // --- a minimal valid EPUB ---

    @Test
    fun extractsAMinimalValidEpub() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "chapter1.xhtml"), listOf("c1")),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("Hello, book."),
                ),
            )
        assertEquals("Hello, book.", extractor.extract(bytes))
    }

    // --- spine order wins over manifest declaration order ---

    @Test
    fun spineOrderWinsOverManifestDeclarationOrder() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to
                        opfXml(
                            // manifest declares chapter 2 before chapter 1...
                            manifestItems = listOf("c2" to "chapter2.xhtml", "c1" to "chapter1.xhtml"),
                            // ...but the spine says chapter 1 is read first.
                            spineIds = listOf("c1", "c2"),
                        ),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("First chapter."),
                    "OEBPS/chapter2.xhtml" to chapterXhtml("Second chapter."),
                ),
            )
        val text = extractor.extract(bytes)
        assertEquals("First chapter.\n\nSecond chapter.", text)
    }

    // --- chapter boundaries preserved as a blank line ---

    @Test
    fun chaptersAreSeparatedByABlankLine() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "a.xhtml", "c2" to "b.xhtml"), listOf("c1", "c2")),
                    "OEBPS/a.xhtml" to chapterXhtml("Chapter A text."),
                    "OEBPS/b.xhtml" to chapterXhtml("Chapter B text."),
                ),
            )
        val text = extractor.extract(bytes)
        assertTrue(text.contains("Chapter A text.\n\nChapter B text."), "expected a blank line between chapters: $text")
    }

    // --- relative paths from a nested OPF directory resolve correctly ---

    @Test
    fun resolvesChapterHrefsRelativeToTheOpfDirectory() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "text/chapter1.xhtml"), listOf("c1")),
                    "OEBPS/text/chapter1.xhtml" to chapterXhtml("Nested chapter."),
                ),
            )
        assertEquals("Nested chapter.", extractor.extract(bytes))
    }

    // --- fail-closed: missing OPF ---

    @Test
    fun failsClosedWhenContainerNamesAMissingOpf() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
                    // content.opf deliberately not included
                    "OEBPS/chapter1.xhtml" to chapterXhtml("orphaned"),
                ),
            )
        val error = assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
        assertTrue(error.message?.contains("content.opf") == true, "expected the opf path named: ${error.message}")
    }

    // --- fail-closed: missing container.xml entirely ---

    @Test
    fun failsClosedWhenContainerXmlIsMissing() {
        val bytes =
            epubBytes(
                mapOf(
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "chapter1.xhtml"), listOf("c1")),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("text"),
                ),
            )
        assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
    }

    // --- fail-closed: spine references a file the archive doesn't actually contain ---

    @Test
    fun failsClosedWhenSpineReferencesAMissingFile() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to
                        opfXml(
                            listOf("c1" to "chapter1.xhtml", "c2" to "chapter2.xhtml"),
                            listOf("c1", "c2"),
                        ),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("only this one exists"),
                    // chapter2.xhtml is declared in the manifest/spine but never written to the zip
                ),
            )
        val error = assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
        assertTrue(
            error.message?.contains("chapter2.xhtml") == true,
            "expected the missing file named: ${error.message}",
        )
    }

    // --- fail-closed: spine idref not declared in the manifest ---

    @Test
    fun failsClosedWhenSpineIdrefIsNotInTheManifest() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "chapter1.xhtml"), listOf("ghost")),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("text"),
                ),
            )
        assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
    }

    // --- fail-closed: no spine at all ---

    @Test
    fun failsClosedWhenOpfHasNoSpine() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to
                        """
                        <?xml version="1.0"?>
                        <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                          <manifest><item id="c1" href="chapter1.xhtml" media-type="application/xhtml+xml"/></manifest>
                        </package>
                        """.trimIndent(),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("text"),
                ),
            )
        assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
    }

    // --- fail-closed: malformed zip, never a crash ---

    @Test
    fun failsClosedOnAMalformedZip() {
        val garbage = "this is not a zip file at all, just plain bytes".toByteArray(Charsets.UTF_8)
        assertFailsWith<IllegalArgumentException> { extractor.extract(garbage) }
    }

    @Test
    fun failsClosedOnEmptyInput() {
        assertFailsWith<IllegalArgumentException> { extractor.extract(ByteArray(0)) }
    }

    // --- zip-slip: a spine href that tries to escape the archive is refused ---

    @Test
    fun refusesAChapterHrefThatEscapesTheArchiveRoot() {
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "../../../etc/passwd"), listOf("c1")),
                ),
            )
        assertFailsWith<IllegalArgumentException> { extractor.extract(bytes) }
    }

    // --- zip bomb: total decompressed bytes across wanted entries is capped while reading ---

    @Test
    fun refusesAnOversizedChapterProvingTheDecompressionCap() {
        val cappedExtractor = EpubTextExtractor(maxExtractedBytes = 1_000)
        // Highly compressible: this deflates to a tiny entry but decompresses far past the 1000-byte cap.
        val hugeBody = "a".repeat(200_000)
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "chapter1.xhtml"), listOf("c1")),
                    "OEBPS/chapter1.xhtml" to chapterXhtml(hugeBody),
                ),
            )
        val error = assertFailsWith<IllegalArgumentException> { cappedExtractor.extract(bytes) }
        assertTrue(error.message?.contains("zip bomb") == true, "expected a zip-bomb message: ${error.message}")
    }

    @Test
    fun aReasonablySizedEpubIsUnaffectedByATightGlobalDefaultCap() {
        // Sanity: the default cap is generous enough that a normal-sized book is never affected.
        val bytes =
            epubBytes(
                mapOf(
                    "META-INF/container.xml" to containerXml(),
                    "OEBPS/content.opf" to opfXml(listOf("c1" to "chapter1.xhtml"), listOf("c1")),
                    "OEBPS/chapter1.xhtml" to chapterXhtml("Not remotely close to the cap."),
                ),
            )
        assertEquals("Not remotely close to the cap.", extractor.extract(bytes))
    }

    // --- registry wiring ---

    @Test
    fun registryRoutesEpubFilesToThisExtractor() {
        val registry = TextExtractorRegistry.withDefaults()
        assertEquals("epub", registry.extractorFor("book.epub")?.id)
    }
}
