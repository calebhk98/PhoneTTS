package com.phonetts.app.backup

import android.content.Context
import android.net.Uri
import com.phonetts.core.backup.BackupCodec
import com.phonetts.core.backup.BackupDecodeResult
import com.phonetts.core.backup.BackupDocument
import com.phonetts.core.backup.BackupExporter
import com.phonetts.core.backup.BackupRestoreSummary
import com.phonetts.core.backup.BackupRestorer
import com.phonetts.core.backup.RestoreMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The `:app` half of "Export/Import my data" (issue #149) - SAF file I/O plus the `:core`
 * export/decode/restore calls, kept out of [BackupSection] so that Composable stays UI-only. Every
 * function here is a plain suspend function (no Composable state), so it is straightforward to move
 * off the main thread (rule 8: all inference/IO off main) via [Dispatchers.IO].
 */
object BackupIo {
    /** Builds the current snapshot and writes it to [uri] (an SAF `CreateDocument` result). Returns a
     * short human-readable result message - success or failure, never a silent no-op (control-path
     * rule: any user-triggered action reports what happened). */
    suspend fun exportTo(
        context: Context,
        uri: Uri,
    ): String =
        withContext(Dispatchers.IO) {
            val stores = BackupStoreFactory.create(context)
            val document = BackupExporter.export(stores, System.currentTimeMillis())
            val json = BackupCodec.encode(document)
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: error("could not open the chosen file for writing")
            }.fold(
                onSuccess = { exportSummary(document) },
                onFailure = { "Export failed: ${it.message ?: "unknown error"}." },
            )
        }

    /** Reads [uri] (an SAF `OpenDocument` result) and decodes it - never applies anything; that is
     * [applyImport]'s job once the user has confirmed a merge/replace choice. */
    suspend fun readAndDecode(
        context: Context,
        uri: Uri,
    ): BackupDecodeResult =
        withContext(Dispatchers.IO) {
            val text =
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
            if (text.isNullOrBlank()) return@withContext BackupDecodeResult.Corrupt("Couldn't read the chosen file.")
            BackupCodec.decode(text)
        }

    /** Applies an already-decoded, user-confirmed [document] in [mode]. Returns a short result message. */
    suspend fun applyImport(
        context: Context,
        document: BackupDocument,
        mode: RestoreMode,
    ): String =
        withContext(Dispatchers.IO) {
            val stores = BackupStoreFactory.create(context)
            restoreSummary(BackupRestorer.restore(document, stores, mode))
        }

    private fun exportSummary(document: BackupDocument): String {
        val favoriteCount = document.favorites.voices.size + document.favorites.models.size
        return "Exported ${document.library.size} document(s), $favoriteCount favorite(s), " +
            "${document.blendedVoices.size} blended voice(s). ${BackupDocument.WEIGHTS_NOTE}"
    }

    private fun restoreSummary(summary: BackupRestoreSummary): String {
        val favoriteCount = summary.favoriteVoicesWritten + summary.favoriteModelsWritten + summary.flaggedModelsWritten
        val removedNote = if (summary.documentsRemoved > 0) ", ${summary.documentsRemoved} removed" else ""
        val entryWord = if (summary.entriesSkipped == 1) "entry" else "entries"
        val skippedNote = if (summary.entriesSkipped > 0) " ${summary.entriesSkipped} malformed $entryWord skipped." else ""
        return "Restored (${summary.mode.name.lowercase()}): ${summary.documentsWritten} document(s)$removedNote, " +
            "${summary.resumePositionsWritten} resume position(s), $favoriteCount favorite(s), " +
            "${summary.blendedVoicesWritten} blended voice(s), ${summary.benchmarkRecordsWritten} benchmark sample(s)." +
            skippedNote
    }
}
