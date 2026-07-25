package com.phonetts.core.prefs

import com.phonetts.core.text.DocumentId

/**
 * One-time migration off the pre-#139/#140 reading-library format (issues #139 and #140): document
 * identity used to be `text.hashCode().toString()` (Java's 32-bit `String.hashCode`, trivially
 * collidable), and every document's full text lived in a single SharedPreferences `StringSet` under
 * the key [LEGACY_KEY]. This reads THAT legacy format directly - it knows nothing about
 * [DocumentLibrary]'s current format beyond calling into it - re-keys every saved document under its
 * new SHA-256-based [DocumentId], and re-keys any [DocumentMemory] resume position saved under the
 * SAME legacy id, so a user's saved library and resume points survive the scheme change intact.
 *
 * **Idempotent:** a successful run deletes [LEGACY_KEY] from [legacyStore], so calling [migrate]
 * again (e.g. unconditionally on every app start, which is the intended usage) finds nothing left to
 * migrate and returns immediately having touched nothing.
 *
 * **Fail-closed:** the whole pass is wrapped so an unexpected exception never deletes [LEGACY_KEY] -
 * if anything throws partway through, the legacy data is left exactly as it was (nothing is lost),
 * and a later retry (e.g. the next app start) picks up where it left off. Re-processing an entry
 * that was already migrated on a previous, interrupted attempt is itself harmless: [DocumentLibrary.add]
 * upserts by id, and re-keying a [DocumentMemory] position that's already been moved simply finds
 * nothing left under the old id.
 *
 * **One narrow, unavoidable gap:** a resume position for text that was read in the main reader but
 * never saved to the library has no corresponding text anywhere to re-derive its new id from, so it
 * cannot be re-keyed - it is left under its old key, unreachable once the app only ever computes the
 * new-scheme id going forward. That is a loss of transient "where was I" state, never of the
 * persisted library itself, which this migration fully preserves.
 */
object DocumentLibraryMigration {
    /** What one [migrate] call did. */
    data class Result(
        /** How many documents were (re-)written into the new library this call. */
        val migratedDocuments: Int,
        /** True when there was nothing to migrate - either already done, or never had legacy data. */
        val alreadyDone: Boolean,
    )

    /**
     * Migrates [legacyStore]'s old-format library (if any) into [library], re-keying any matching
     * [memory] resume positions along the way. Safe to call unconditionally on every app start - see
     * class kdoc for the idempotency and fail-closed guarantees.
     */
    fun migrate(
        legacyStore: PreferenceStore,
        library: DocumentLibrary,
        memory: DocumentMemory,
    ): Result {
        val legacyRecords = legacyStore.getStringSet(LEGACY_KEY)
        if (legacyRecords.isEmpty()) return Result(migratedDocuments = 0, alreadyDone = true)

        return runCatching { migrateAll(legacyRecords, legacyStore, library, memory) }
            .getOrElse { Result(migratedDocuments = 0, alreadyDone = false) }
    }

    private fun migrateAll(
        legacyRecords: Set<String>,
        legacyStore: PreferenceStore,
        library: DocumentLibrary,
        memory: DocumentMemory,
    ): Result {
        var migrated = 0
        legacyRecords.mapNotNull(::decodeLegacy).forEach { legacy ->
            if (migrateOne(legacy, library, memory)) migrated++
        }
        // Only removed once every entry above completed without throwing - see class kdoc.
        legacyStore.remove(LEGACY_KEY)
        return Result(migratedDocuments = migrated, alreadyDone = false)
    }

    // Re-keys one legacy document onto the new id scheme, and its resume position (if any) along
    // with it. Returns whether the document itself was (re-)written.
    private fun migrateOne(
        legacy: LegacyDocument,
        library: DocumentLibrary,
        memory: DocumentMemory,
    ): Boolean {
        val newId = DocumentId.of(legacy.text)
        val existing = library.get(newId)
        val wroteDocument = existing == null || legacy.savedAtMillis >= existing.savedAtMillis
        if (wroteDocument) {
            library.add(id = newId, text = legacy.text, savedAtMillis = legacy.savedAtMillis, title = legacy.title)
        }

        if (legacy.id != newId) {
            memory.resume(legacy.id)?.let { position ->
                memory.record(position.copy(documentId = newId))
                memory.forget(legacy.id)
            }
        }
        return wroteDocument
    }

    private fun decodeLegacy(record: String): LegacyDocument? {
        val parts = record.split(FIELD_SEP, limit = FIELD_COUNT)
        if (parts.size != FIELD_COUNT) return null
        val savedAt = parts[SAVED_AT_INDEX].toLongOrNull() ?: return null
        return LegacyDocument(
            id = parts[ID_INDEX],
            title = parts[TITLE_INDEX],
            text = parts[TEXT_INDEX],
            savedAtMillis = savedAt,
        )
    }

    private data class LegacyDocument(
        val id: String,
        val title: String,
        val text: String,
        val savedAtMillis: Long,
    )

    // The exact key/encoding the pre-#139 DocumentLibrary used - duplicated here (not shared code)
    // because this migration is the ONLY thing that should ever again understand the old format.
    private const val LEGACY_KEY = "reading_library"
    private const val FIELD_SEP = '\u001F'
    private const val FIELD_COUNT = 4
    private const val ID_INDEX = 0
    private const val TITLE_INDEX = 1
    private const val SAVED_AT_INDEX = 2
    private const val TEXT_INDEX = 3
}
