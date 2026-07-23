package com.phonetts.core.download.hf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HfFileFormatsTest {
    private fun file(path: String) = HfTreeEntry(type = "file", path = path, size = 8_000_000)

    @Test
    fun derivesFormatsFromFileExtensions() {
        val files = listOf(file("onnx/model.onnx"), file("config.json"))
        assertEquals(setOf(HfFileFormat.ONNX), HfFileFormats.formatsOf(files))
    }

    @Test
    fun recognizesGgufSafetensorsAndTorch() {
        assertEquals(setOf(HfFileFormat.GGUF), HfFileFormats.formatsOf(listOf(file("m.gguf"))))
        assertEquals(setOf(HfFileFormat.SAFETENSORS), HfFileFormats.formatsOf(listOf(file("m.safetensors"))))
        assertEquals(setOf(HfFileFormat.PYTORCH), HfFileFormats.formatsOf(listOf(file("pytorch_model.bin"))))
        assertEquals(setOf(HfFileFormat.TFLITE), HfFileFormats.formatsOf(listOf(file("m.tflite"))))
        assertEquals(setOf(HfFileFormat.NEMO), HfFileFormats.formatsOf(listOf(file("m.nemo"))))
    }

    @Test
    fun detectsMlxByRepoNamespaceEvenWhenWeightsAreSafetensors() {
        val formats = HfFileFormats.formatsOf(listOf(file("model.safetensors")), repoId = "mlx-community/Kokoro-82M")
        assertTrue(HfFileFormat.MLX in formats)
        assertTrue(HfFileFormat.SAFETENSORS in formats)
    }

    @Test
    fun detectsCoreMlPackageDirectoryBundle() {
        val files =
            listOf(
                HfTreeEntry(type = "directory", path = "Model.mlpackage"),
                file("Model.mlpackage/weights.bin"),
            )
        assertTrue(HfFileFormat.COREML in HfFileFormats.formatsOf(files))
    }

    @Test
    fun ignoresDirectoriesAndIsCaseInsensitive() {
        val files =
            listOf(
                HfTreeEntry(type = "directory", path = "ONNX"),
                HfTreeEntry(type = "file", path = "model.ONNX", size = 8_000_000),
            )
        assertEquals(setOf(HfFileFormat.ONNX), HfFileFormats.formatsOf(files))
    }

    @Test
    fun availableFormatsAreDerivedFromResultsAndOrderedByEnum() {
        val byId =
            mapOf(
                "a" to setOf(HfFileFormat.GGUF),
                "b" to setOf(HfFileFormat.ONNX, HfFileFormat.SAFETENSORS),
            )
        val expected = listOf(HfFileFormat.ONNX, HfFileFormat.GGUF, HfFileFormat.SAFETENSORS)
        assertEquals(expected, HfFileFormats.availableFormats(byId))
    }

    @Test
    fun filterByFormatKeepsOnlyMatchingAndDropsUnfetched() {
        val results =
            listOf(
                HfModelSummary(id = "onnx-one"),
                HfModelSummary(id = "gguf-one"),
                HfModelSummary(id = "not-fetched"),
            )
        val byId = mapOf("onnx-one" to setOf(HfFileFormat.ONNX), "gguf-one" to setOf(HfFileFormat.GGUF))
        val filtered = HfFileFormats.filterByFormat(results, byId, HfFileFormat.ONNX)
        assertEquals(listOf("onnx-one"), filtered.map { it.id })
    }

    @Test
    fun filterByFormatWithNullIsANoOp() {
        val results = listOf(HfModelSummary(id = "a"), HfModelSummary(id = "b"))
        assertEquals(results.map { it.id }, HfFileFormats.filterByFormat(results, emptyMap(), null).map { it.id })
        assertFalse(HfFileFormat.ONNX in HfFileFormats.formatsOf(emptyList()))
    }
}
