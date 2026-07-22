package com.phonetts.engines.pytorch

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [RawPyTorchBundle.looksLikeRawPyTorch] is a pure shape detector, deliberately decoupled from any
 * claim of runnability (see [PyTorchEngine]'s kdoc) — these tests only exercise the shape logic
 * itself: a recognized weight extension + `config.json`, and never an `.onnx` file (which belongs
 * to a different, actually-runnable engine).
 */
class RawPyTorchBundleTest {
    @Test
    fun `recognizes a pth weight file with a config json`() {
        assertTrue(RawPyTorchBundle.looksLikeRawPyTorch(setOf("checkpoint.pth", "config.json")))
    }

    @Test
    fun `recognizes ckpt and bin weight files with a config json`() {
        assertTrue(RawPyTorchBundle.looksLikeRawPyTorch(setOf("model.ckpt", "config.json")))
        assertTrue(RawPyTorchBundle.looksLikeRawPyTorch(setOf("pytorch_model.bin", "config.json")))
    }

    @Test
    fun `does not recognize a weight file with no config json`() {
        assertFalse(RawPyTorchBundle.looksLikeRawPyTorch(setOf("checkpoint.pth")))
    }

    @Test
    fun `does not recognize a bare config json with no weight file`() {
        assertFalse(RawPyTorchBundle.looksLikeRawPyTorch(setOf("config.json")))
    }

    @Test
    fun `does not recognize an unrelated bundle`() {
        assertFalse(RawPyTorchBundle.looksLikeRawPyTorch(setOf("readme.txt")))
    }

    @Test
    fun `never claims a bundle that also carries an onnx graph`() {
        // An ONNX-packaged bundle belongs to a different, actually runnable engine — even if it
        // happens to ship a raw .pth/.ckpt/.bin alongside it (e.g. the original training checkpoint
        // next to its exported graph), this is not "raw PyTorch" for this helper's purposes.
        assertFalse(RawPyTorchBundle.looksLikeRawPyTorch(setOf("checkpoint.pth", "config.json", "model.onnx")))
    }

    @Test
    fun `empty file set is not recognized`() {
        assertFalse(RawPyTorchBundle.looksLikeRawPyTorch(emptySet()))
    }
}
