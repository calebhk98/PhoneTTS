package com.phonetts.core.storage

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelStorageMigratorTest {
    private fun tempDir(): File = createTempDir(prefix = "phonetts_migrator_test_")

    @Test
    fun sameLocationIsDetectedForTheIdenticalDirectory() {
        val dir = tempDir()
        assertTrue(ModelStorageMigrator.sameLocation(dir, dir))
        assertTrue(ModelStorageMigrator.sameLocation(dir, File(dir.absolutePath)))
    }

    @Test
    fun sameLocationIsFalseForDifferentDirectories() {
        val a = tempDir()
        val b = tempDir()
        assertFalse(ModelStorageMigrator.sameLocation(a, b))
    }

    // Rule 2: re-picking the SAME folder must be a no-op - migrate() must short-circuit before
    // touching anything, even if (hypothetically) called on an old/new pair that resolve equal.
    @Test
    fun migrateIsANoOpWhenOldAndNewResolveToTheSameLocation() {
        val base = tempDir()
        val modelsDir = File(base, "models").apply { mkdirs() }
        File(modelsDir, "voice_a").apply { mkdirs() }
        File(File(modelsDir, "voice_a"), "weights.bin").writeText("hi")

        val outcome = ModelStorageMigrator.migrate(modelsDir, File(modelsDir.absolutePath))

        assertEquals(ModelStorageMigrator.Outcome.SameLocation, outcome)
        assertTrue(File(modelsDir, "voice_a/weights.bin").exists())
    }

    @Test
    fun migrateReportsNothingToMigrateWhenTheOldDirDoesNotExist() {
        val oldDir = File(tempDir(), "models")
        val newDir = File(tempDir(), "models")

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.NothingToMigrate, outcome)
    }

    @Test
    fun migrateReportsNothingToMigrateWhenTheOldDirIsEmpty() {
        val oldDir = File(tempDir(), "models").apply { mkdirs() }
        val newDir = File(tempDir(), "models")

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.NothingToMigrate, outcome)
    }

    // The core data-loss fix: a model folder physically moves from old base dir to new base dir.
    @Test
    fun migrateMovesEveryModelFolderToTheNewLocationAndRemovesItFromTheOld() {
        val oldDir = File(tempDir(), "models").apply { mkdirs() }
        val newDir = File(tempDir(), "models")
        val voiceDir = File(oldDir, "voice_a").apply { mkdirs() }
        File(voiceDir, "weights.bin").writeText("model bytes")
        File(voiceDir, "sub").mkdirs()
        File(File(voiceDir, "sub"), "extra.json").writeText("{}")

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.Migrated(1), outcome)
        assertFalse(File(oldDir, "voice_a").exists())
        assertEquals("model bytes", File(newDir, "voice_a/weights.bin").readText())
        assertEquals("{}", File(newDir, "voice_a/sub/extra.json").readText())
    }

    @Test
    fun migrateMovesMultipleModelFolders() {
        val oldDir = File(tempDir(), "models").apply { mkdirs() }
        val newDir = File(tempDir(), "models")
        listOf("voice_a", "voice_b", "voice_c").forEach { name ->
            File(oldDir, name).apply { mkdirs() }
            File(File(oldDir, name), "weights.bin").writeText(name)
        }

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.Migrated(3), outcome)
        listOf("voice_a", "voice_b", "voice_c").forEach { name ->
            assertFalse(File(oldDir, name).exists())
            assertEquals(name, File(newDir, "$name/weights.bin").readText())
        }
    }

    // Fail-safe: if the destination can't even be created, nothing is deleted and every entry is
    // reported as failed rather than guessed as migrated.
    @Test
    fun migrateLeavesSourceIntactWhenTheDestinationCannotBeCreated() {
        val oldDir = File(tempDir(), "models").apply { mkdirs() }
        File(oldDir, "voice_a").apply { mkdirs() }
        // Make the new dir's PARENT a plain file so mkdirs() for the new models dir must fail.
        val blocker = tempDir()
        val blockerFile = File(blocker, "blocked_parent").apply { writeText("not a directory") }
        val newDir = File(blockerFile, "models")

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.PartialFailure(0, listOf("voice_a")), outcome)
        assertTrue(File(oldDir, "voice_a").exists())
    }

    // Re-running migrate() after a folder already made it across (e.g. a previous partial-failure
    // retry) must not fail or duplicate - it's treated as already done for that entry.
    @Test
    fun migrateTreatsAnAlreadyPresentDestinationEntryAsDone() {
        val oldDir = File(tempDir(), "models").apply { mkdirs() }
        val newDir = File(tempDir(), "models").apply { mkdirs() }
        File(oldDir, "voice_a").apply { mkdirs() }
        File(newDir, "voice_a").apply { mkdirs() } // already migrated in a prior attempt

        val outcome = ModelStorageMigrator.migrate(oldDir, newDir)

        assertEquals(ModelStorageMigrator.Outcome.Migrated(1), outcome)
    }
}
