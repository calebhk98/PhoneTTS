package com.phonetts.core.sideload

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectoryBundleReaderTest {
    private fun tempModelDir(): File {
        val dir = Files.createTempDirectory("bundle").toFile()
        File(dir, "model.onnx").writeBytes(ByteArray(2048)) // "weights" - name only, not read in
        File(dir, "config.json").writeText("""{"model_type":"demo"}""")
        File(dir, "tokens.txt").writeText("a b c")
        return dir
    }

    @Test
    fun readsFileNamesAndTextSideFilesButNotWeights() {
        val reader = DirectoryBundleReader()
        val bundle = reader.read(tempModelDir().absolutePath)

        assertTrue(bundle.hasFile("model.onnx"))
        assertTrue(bundle.hasFileEndingWith(".onnx"))
        assertEquals("""{"model_type":"demo"}""", bundle.sideFile("config.json"))
        assertEquals("a b c", bundle.sideFile("tokens.txt"))
        // The .onnx is present as a name but never read into memory as a side file.
        assertNull(bundle.sideFile("model.onnx"))
    }

    @Test
    fun bundleIdIsTheFolderNameAndRootPathIsSet() {
        val dir = tempModelDir()
        val bundle = DirectoryBundleReader().read(dir.absolutePath)
        assertEquals(dir.name, bundle.id)
        assertEquals(dir.absolutePath, bundle.rootPath)
    }

    @Test
    fun oversizedSideFileIsNotReadIntoMemory() {
        val dir = Files.createTempDirectory("bundle").toFile()
        File(dir, "config.json").writeText("x".repeat(100))
        // A "text" file larger than the cap must be excluded from sideFiles (name still present).
        File(dir, "huge.json").writeText("y".repeat(200))
        val bundle = DirectoryBundleReader(maxSideFileBytes = 150).read(dir.absolutePath)

        assertEquals("x".repeat(100), bundle.sideFile("config.json"))
        assertNull(bundle.sideFile("huge.json"), "an oversized side file must not be read into memory")
        assertTrue(bundle.hasFile("huge.json"), "but it should still be listed by name")
    }

    @Test
    fun rejectsANonDirectory() {
        val file = Files.createTempFile("not-a-dir", ".onnx").toFile()
        try {
            DirectoryBundleReader().read(file.absolutePath)
            error("expected an exception for a non-directory location")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not a directory"))
        }
    }
}
