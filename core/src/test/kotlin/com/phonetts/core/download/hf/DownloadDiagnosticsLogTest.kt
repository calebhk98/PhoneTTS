package com.phonetts.core.download.hf

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadDiagnosticsLogTest {
    private fun tempLogFile(): File {
        val dir = Files.createTempDirectory("diagnostics").toFile()
        return File(dir, "diagnostics.json")
    }

    @Test
    fun startsEmptyWhenNoFileExistsYet() {
        val log = DownloadDiagnosticsLog(tempLogFile())
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun recordsAndPersistsEntriesNewestFirst() {
        val log = DownloadDiagnosticsLog(tempLogFile())
        log.record(
            DiagnosticsEntry(atMs = 1L, modelId = "owner/first", kind = DiagnosticsKind.FAILURE, detail = "boom"),
        )
        log.record(
            DiagnosticsEntry(
                atMs = 2L,
                modelId = "owner/second",
                kind = DiagnosticsKind.NO_ENGINE_YET,
                detail = "no engine",
            ),
        )

        val entries = log.entries()
        assertEquals(2, entries.size)
        assertEquals("owner/second", entries.first().modelId, "most recent entry should be first")
        assertEquals("owner/first", entries.last().modelId)
    }

    @Test
    fun survivesACleanReloadFromANewInstance() {
        val file = tempLogFile()
        DownloadDiagnosticsLog(file).record(
            DiagnosticsEntry(
                atMs = 5L,
                modelId = "owner/repo",
                kind = DiagnosticsKind.FAILURE,
                detail = "connection abort",
            ),
        )

        val reloaded = DownloadDiagnosticsLog(file).entries()
        assertEquals(1, reloaded.size)
        assertEquals("connection abort", reloaded.single().detail)
    }

    @Test
    fun clearRemovesEveryEntry() {
        val log = DownloadDiagnosticsLog(tempLogFile())
        log.record(DiagnosticsEntry(atMs = 1L, modelId = "owner/repo", kind = DiagnosticsKind.FAILURE, detail = "x"))

        log.clear()

        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun boundsEntryCountSoTheLogCannotGrowUnbounded() {
        val log = DownloadDiagnosticsLog(tempLogFile())
        repeat(150) { i ->
            val entry =
                DiagnosticsEntry(atMs = i.toLong(), modelId = "repo$i", kind = DiagnosticsKind.FAILURE, detail = "x")
            log.record(entry)
        }

        assertEquals(100, log.entries().size)
        assertEquals("repo149", log.entries().first().modelId, "newest entries are kept, oldest dropped")
    }
}
