package com.phonetts.core.prefs

/**
 * One saved document in the reading library (issue #19-5): a stable [id] (see
 * [com.phonetts.core.text.DocumentId] - callers use the SAME content-derived id [DocumentMemory]
 * keys resume positions by, so opening a library document lines up with any saved resume point for
 * free), a display [title], the full [text], and [savedAtMillis] for ordering. Timestamps are
 * supplied by the caller (`:app` passes `System.currentTimeMillis()`) so this stays deterministic
 * and unit-testable on a plain JVM.
 */
data class LibraryDocument(
    val id: String,
    val title: String,
    val text: String,
    val savedAtMillis: Long,
)

/**
 * Persists the user's saved documents over an injected [PreferenceStore], mirroring
 * [BlendedVoiceStore]'s pattern: `:core` holds the pure logic, `:app` supplies the
 * SharedPreferences-backed store. Every document is encoded as one delimited record in a single
 * string set - a malformed record (wrong field count, unparseable timestamp) is skipped on read,
 * never thrown, so a corrupt entry can't crash the library.
 */
class DocumentLibrary(private val store: PreferenceStore) {
    /** Every saved document, most recently saved first. */
    fun list(): List<LibraryDocument> = allDocuments().sortedByDescending { it.savedAtMillis }

    /** One saved document by [id], or null if it was never saved (or has since been deleted). */
    fun get(id: String): LibraryDocument? = allDocuments().firstOrNull { it.id == id }

    /**
     * Save [text] under [id] (adding it, or replacing any existing document with the same id - so
     * re-saving the same document, e.g. via [com.phonetts.core.text.DocumentId], updates it in
     * place rather than duplicating it). [title] defaults to a title derived from [text] when the
     * caller doesn't supply one (blank/null).
     */
    fun add(
        id: String,
        text: String,
        savedAtMillis: Long,
        title: String? = null,
    ): LibraryDocument {
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: deriveTitle(text)
        val document = LibraryDocument(id, resolvedTitle, text, savedAtMillis)
        persist(allDocuments().filterNot { it.id == id } + document)
        return document
    }

    /** Rename a saved document. Returns the updated document, or null if [id] isn't saved or [newTitle] is blank. */
    fun rename(
        id: String,
        newTitle: String,
    ): LibraryDocument? {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return null
        val existing = get(id) ?: return null
        val renamed = existing.copy(title = trimmed)
        persist(allDocuments().map { if (it.id == id) renamed else it })
        return renamed
    }

    /** Removes the document with [id], if present. A no-op if it was never saved. */
    fun delete(id: String) {
        persist(allDocuments().filterNot { it.id == id })
    }

    private fun allDocuments(): List<LibraryDocument> = store.getStringSet(KEY).mapNotNull(::decode)

    private fun persist(documents: List<LibraryDocument>) {
        store.putStringSet(KEY, documents.map(::encode).toSet())
    }

    private fun encode(document: LibraryDocument): String =
        listOf(document.id, document.title, document.savedAtMillis.toString(), document.text)
            .joinToString(FIELD_SEP.toString())

    // limit = FIELD_COUNT so a stray separator inside the TEXT field (the last one) never truncates
    // the document - only a separator inside id/title (never expected in practice) would corrupt a
    // record, and that record is simply dropped rather than crashing.
    private fun decode(record: String): LibraryDocument? {
        val parts = record.split(FIELD_SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        val savedAt = parts[SAVED_AT_INDEX].toLongOrNull() ?: return null
        return LibraryDocument(
            id = parts[ID_INDEX],
            title = parts[TITLE_INDEX],
            text = parts[TEXT_INDEX],
            savedAtMillis = savedAt,
        )
    }

    companion object {
        /** Default title when the user doesn't name a document: its first non-blank line, truncated. */
        fun deriveTitle(text: String): String {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (firstLine.isEmpty()) return UNTITLED
            if (firstLine.length <= TITLE_MAX_CHARS) return firstLine
            return firstLine.take(TITLE_MAX_CHARS).trimEnd() + "…"
        }

        private const val UNTITLED = "Untitled"
        private const val TITLE_MAX_CHARS = 60
        private const val KEY = "reading_library"

        // Unit Separator (U+001F): a control char that never appears in real document text/titles,
        // so records split unambiguously without escaping (same idiom as BlendedVoiceStore).
        private const val FIELD_SEP = '\u001F'
        private const val FIELD_COUNT = 4
        private const val ID_INDEX = 0
        private const val TITLE_INDEX = 1
        private const val SAVED_AT_INDEX = 2
        private const val TEXT_INDEX = 3
    }
}
