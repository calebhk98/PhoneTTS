package com.phonetts.core.text.extract

import com.phonetts.core.download.SafePath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Reads EPUB e-books (issue #144) - the one format every competing reader app (Speechify,
 * NaturalReader, Voice Aloud, eReader Prestigio) supports and this one, until now, did not.
 *
 * An EPUB is a ZIP - the same shape [DocxTextExtractor] already handles, so this is deliberately
 * built as its sibling: `META-INF/container.xml` names the OPF package file; the OPF's
 * `<manifest>` maps ids to files and its `<spine>` lists those ids in the book's actual reading
 * ORDER, which can differ from manifest declaration order - the spine is authoritative, never the
 * manifest. Each spine chapter is XHTML, stripped to text by the existing [HtmlTextExtractor]
 * (rule 1 SSOT: no second HTML-to-text stripper). Chapters are joined with a blank line between
 * them so the TTS chunker sees a paragraph break, never a run-on sentence, at a chapter boundary.
 *
 * Pure JVM (`java.util.zip` + regex over the small XML control files), matching
 * [DocxTextExtractor]'s no-XML-parser-dependency style rather than introducing one just for this.
 *
 * Fails closed (rule 4: never a crash, never a silently-empty document) for: a malformed/non-zip
 * file, a missing/unreadable `container.xml`, a container that doesn't name an OPF file, a missing
 * OPF, an OPF with no `<spine>`, a spine entry whose manifest id isn't declared, and a spine entry
 * whose file isn't actually present in the archive. DRM/encrypted EPUBs (Adobe ADEPT, LCP) are not
 * specially detected - they surface as one of the above (missing/garbled XML, or chapters that
 * decode to no readable text) rather than a dedicated check, which is an honest reflection of what
 * this extractor can actually verify.
 *
 * Guards against zip-slip (a manifest/spine `href` escaping the archive via `..` or an absolute
 * path - checked with [SafePath], both on every raw zip entry name and on every resolved chapter
 * path) and a zip bomb (each zip scan enforces [maxExtractedBytes] on total decompressed bytes
 * *while reading*, not after - so a bomb is caught before it is ever fully buffered in memory).
 * Only the entries actually needed (`container.xml`, the OPF, and the spine's chapter files) are
 * ever read into memory - an illustrated book's images/fonts/CSS are skipped without being
 * decompressed, so the byte cap only has to bound the book's actual TEXT, not its whole archive.
 */
class EpubTextExtractor(
    private val maxExtractedBytes: Long = DEFAULT_MAX_EXTRACTED_BYTES,
) : TextExtractor {
    override val id: String = "epub"

    override fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean = matchesExtensionOrMime(fileName, mimeType, EXTENSIONS, EPUB_MIME)

    override fun extract(bytes: ByteArray): String {
        val containerXml = requireEntry(bytes, CONTAINER_ENTRY).toString(Charsets.UTF_8)
        val opfPath = rootFilePath(containerXml)
        val opfXml = requireEntry(bytes, opfPath, namedBy = CONTAINER_ENTRY).toString(Charsets.UTF_8)
        val opfDir = opfPath.substringBeforeLast('/', "")
        val manifest = parseManifest(opfXml)
        val spineIds = parseSpineIds(opfXml)
        require(spineIds.isNotEmpty()) { "not a valid EPUB: its package file has no <spine> reading order" }
        val chapterPaths = spineIds.map { resolveChapterPath(manifest, opfDir, it) }
        val chapterBytes = readZipEntries(bytes, chapterPaths.toSet(), maxExtractedBytes)
        val chapters =
            chapterPaths.map { path ->
                val html =
                    chapterBytes[path]
                        ?: throw IllegalArgumentException(
                            "not a valid EPUB: its spine references '$path', which is missing from the archive",
                        )
                HtmlTextExtractor().extract(html)
            }
        val nonBlankChapters = chapters.filter { it.isNotBlank() }
        require(nonBlankChapters.isNotEmpty()) {
            "this EPUB produced no readable text - it may be image-only or DRM-protected"
        }
        return nonBlankChapters.joinToString("\n\n")
    }

    // --- container.xml / OPF lookup ---

    private fun requireEntry(
        bytes: ByteArray,
        name: String,
        namedBy: String? = null,
    ): ByteArray {
        val found = readZipEntries(bytes, setOf(name), maxExtractedBytes)[name]
        if (found != null) return found
        val suffix = if (namedBy != null) " (named by $namedBy)" else ""
        throw IllegalArgumentException("not a valid EPUB: missing or unreadable '$name'$suffix")
    }

    private fun rootFilePath(containerXml: String): String {
        val match =
            ROOTFILE_PATTERN.find(containerXml)
                ?: throw IllegalArgumentException(
                    "not a valid EPUB: $CONTAINER_ENTRY does not name a package (.opf) file",
                )
        return match.groupValues[1]
    }

    // --- OPF manifest / spine parsing ---

    private fun parseManifest(opfXml: String): Map<String, String> {
        val section = MANIFEST_SECTION.find(opfXml)?.groupValues?.get(1) ?: return emptyMap()
        // Expressed as mapNotNull rather than a loop with two `continue`s so it reads the same way
        // parseSpineIds does, and stays inside detekt's LoopWithTooManyJumpStatements rule.
        return ITEM_TAG.findAll(section)
            .mapNotNull { tag ->
                val id = ID_ATTR.find(tag.value)?.groupValues?.get(1)
                val href = HREF_ATTR.find(tag.value)?.groupValues?.get(1)
                if (id == null || href == null) null else id to href
            }
            .toMap()
    }

    private fun parseSpineIds(opfXml: String): List<String> {
        val section = SPINE_SECTION.find(opfXml)?.groupValues?.get(1) ?: return emptyList()
        return ITEMREF_TAG.findAll(section)
            .mapNotNull { IDREF_ATTR.find(it.value)?.groupValues?.get(1) }
            .toList()
    }

    private fun resolveChapterPath(
        manifest: Map<String, String>,
        opfDir: String,
        spineId: String,
    ): String {
        val href =
            manifest[spineId]
                ?: throw IllegalArgumentException(
                    "not a valid EPUB: its spine references id '$spineId', which its <manifest> never declares",
                )
        val decoded = decodePercent(href.substringBefore('#'))
        val resolved = resolveRelative(opfDir, decoded)
        SafePath.require(resolved)
        return resolved
    }

    private fun resolveRelative(
        baseDir: String,
        href: String,
    ): String {
        val combined =
            when {
                href.startsWith("/") -> href.removePrefix("/")
                baseDir.isEmpty() -> href
                else -> "$baseDir/$href"
            }
        val out = mutableListOf<String>()
        combined.split('/').forEach { segment -> applySegment(out, segment, href) }
        return out.joinToString("/")
    }

    // One path segment, applied to the accumulated path. Split out so [resolveRelative] stays flat -
    // the never-nesting rule (CLAUDE.md rule 9) that detekt's NestedBlockDepth enforces.
    private fun applySegment(
        out: MutableList<String>,
        segment: String,
        href: String,
    ) {
        if (segment.isEmpty() || segment == ".") return
        if (segment != "..") {
            out.add(segment)
            return
        }
        require(out.isNotEmpty()) { "not a valid EPUB: path '$href' escapes the archive root" }
        out.removeAt(out.lastIndex)
    }

    private fun decodePercent(value: String): String {
        if ('%' !in value) return value
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            i += writeCharAt(value, i, out)
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    // Writes the character (or decoded escape) starting at [index] and returns how many source
    // characters it consumed. Split out so decodePercent stays a flat loop with one exit - the
    // never-nesting rule (CLAUDE.md rule 9), which detekt enforces as NestedBlockDepth /
    // ComplexCondition / LoopWithTooManyJumpStatements.
    private fun writeCharAt(
        value: String,
        index: Int,
        out: ByteArrayOutputStream,
    ): Int {
        if (isEscapeAt(value, index)) {
            out.write(value.substring(index + 1, index + ESCAPE_LENGTH).toInt(HEX_RADIX))
            return ESCAPE_LENGTH
        }
        out.write(value[index].toString().toByteArray(Charsets.UTF_8))
        return 1
    }

    private fun isEscapeAt(
        value: String,
        index: Int,
    ): Boolean {
        if (value[index] != '%') return false
        if (index + ESCAPE_LENGTH > value.length) return false
        return isHex(value[index + 1]) && isHex(value[index + 2])
    }

    private fun isHex(c: Char): Boolean = c.isDigit() || c in 'a'..'f' || c in 'A'..'F'

    // --- capped, zip-slip-guarded zip reading ---

    /**
     * Reads only the entries whose name is in [wanted] from [bytes], guarding every entry name
     * against traversal ([SafePath]) and the running total of decompressed bytes against
     * [budget] (checked as each chunk streams in, so an oversized entry is caught mid-read, never
     * after it has already been buffered).
     */
    private fun readZipEntries(
        bytes: ByteArray,
        wanted: Set<String>,
        budget: Long,
    ): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> drainInto(zip, wanted, budget, result) }
        } catch (e: IOException) {
            throw IllegalArgumentException("not a valid EPUB: unreadable or corrupt zip archive", e)
        }
        return result
    }

    // The read loop, lifted out of the try/use nest above so no function exceeds detekt's depth limit.
    private fun drainInto(
        zip: ZipInputStream,
        wanted: Set<String>,
        budget: Long,
        into: MutableMap<String, ByteArray>,
    ) {
        var remaining = budget
        var entry = zip.nextEntry
        while (entry != null) {
            remaining -= takeEntry(zip, entry, wanted, remaining, into)
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    // Reads one entry if it is wanted, returning how much of the budget it consumed (0 when skipped).
    // Extracted so the read loop above stays flat (never-nesting, CLAUDE.md rule 9).
    private fun takeEntry(
        zip: ZipInputStream,
        entry: java.util.zip.ZipEntry,
        wanted: Set<String>,
        remaining: Long,
        into: MutableMap<String, ByteArray>,
    ): Int {
        if (entry.isDirectory) return 0
        if (entry.name !in wanted) return 0
        SafePath.require(entry.name)
        val data = readCapped(zip, remaining)
        into[entry.name] = data
        return data.size
    }

    private fun readCapped(
        zip: ZipInputStream,
        budget: Long,
    ): ByteArray {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val out = ByteArrayOutputStream()
        var remaining = budget
        var read = zip.read(buffer)
        while (read >= 0) {
            remaining -= read
            if (remaining < 0) {
                throw IllegalArgumentException(
                    "this EPUB decompresses past its ${maxExtractedBytes / BYTES_PER_MB} MB safety limit - " +
                        "refusing to extract further (possible zip bomb)",
                )
            }
            out.write(buffer, 0, read)
            read = zip.read(buffer)
        }
        return out.toByteArray()
    }

    companion object {
        // "%XX" - the escape marker plus its two hex digits.
        private const val BYTES_PER_MB = 1024 * 1024
        private const val ESCAPE_LENGTH = 3
        private const val HEX_RADIX = 16

        const val EPUB_MIME = "application/epub+zip"
        val EXTENSIONS = setOf("epub")

        /** Generous for even a very long, illustrated book's TEXT alone (images/fonts aren't read). */
        const val DEFAULT_MAX_EXTRACTED_BYTES: Long = 200L * 1024 * 1024
        private const val READ_BUFFER_SIZE = 8192
        private const val CONTAINER_ENTRY = "META-INF/container.xml"

        private val ROOTFILE_PATTERN =
            Regex("""<(?:\w+:)?rootfile\b[^>]*\bfull-path\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        private val MANIFEST_SECTION =
            Regex("""<(?:\w+:)?manifest\b[^>]*>([\s\S]*?)</(?:\w+:)?manifest>""", RegexOption.IGNORE_CASE)
        private val SPINE_SECTION =
            Regex("""<(?:\w+:)?spine\b[^>]*>([\s\S]*?)</(?:\w+:)?spine>""", RegexOption.IGNORE_CASE)
        private val ITEM_TAG = Regex("""<(?:\w+:)?item\b[^>]*/?>""", RegexOption.IGNORE_CASE)
        private val ITEMREF_TAG = Regex("""<(?:\w+:)?itemref\b[^>]*/?>""", RegexOption.IGNORE_CASE)
        private val ID_ATTR = Regex("""\bid\s*=\s*"([^"]*)"""")
        private val HREF_ATTR = Regex("""\bhref\s*=\s*"([^"]*)"""")
        private val IDREF_ATTR = Regex("""\bidref\s*=\s*"([^"]*)"""")
    }
}
