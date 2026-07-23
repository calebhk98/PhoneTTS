package com.phonetts.core.convert

import com.phonetts.core.model.ModelBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class TranscoderRegistryTest {
    private val bundle = ModelBundle(id = "some-model", fileNames = setOf("model.safetensors", "config.json"))

    private class FakeTranscoder(
        override val id: String,
        private val claims: Boolean,
    ) : BundleTranscoder {
        var canTranscodeCalls = 0
            private set

        override fun canTranscode(bundle: ModelBundle): Boolean {
            canTranscodeCalls++
            return claims
        }

        override fun transcode(
            bundle: ModelBundle,
            outputDir: String,
        ): TranscodeResult = TranscodeResult.Converted(listOf("model.onnx"))
    }

    @Test
    fun `an empty registry claims nothing`() {
        val registry = TranscoderRegistry()

        assertNull(registry.transcoderFor(bundle))
        assertEquals(emptyList(), registry.all())
    }

    @Test
    fun `the first transcoder that claims the bundle is returned`() {
        val claiming = FakeTranscoder(id = "recipe-a", claims = true)
        val registry = TranscoderRegistry(listOf(claiming))

        assertSame(claiming, registry.transcoderFor(bundle))
    }

    @Test
    fun `a declining transcoder is skipped in favor of a later claimer`() {
        val declining = FakeTranscoder(id = "recipe-a", claims = false)
        val claiming = FakeTranscoder(id = "recipe-b", claims = true)
        val registry = TranscoderRegistry(listOf(declining, claiming))

        assertSame(claiming, registry.transcoderFor(bundle))
        assertEquals(1, declining.canTranscodeCalls)
    }

    @Test
    fun `dispatch stops at the first claimer and does not consult later transcoders`() {
        val first = FakeTranscoder(id = "recipe-a", claims = true)
        val second = FakeTranscoder(id = "recipe-b", claims = true)
        val registry = TranscoderRegistry(listOf(first, second))

        assertSame(first, registry.transcoderFor(bundle))
        assertEquals(0, second.canTranscodeCalls)
    }

    @Test
    fun `a bundle no transcoder claims resolves to null`() {
        val declining = FakeTranscoder(id = "recipe-a", claims = false)
        val registry = TranscoderRegistry(listOf(declining))

        assertNull(registry.transcoderFor(bundle))
    }
}
