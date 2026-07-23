package com.phonetts.core.convert

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SafetensorsReaderTest {
    // A hand-built two-tensor container: tensor "a" = float32[2] {1.0, 2.0}, tensor "b" = int64[1] {42},
    // plus a __metadata__ block. data_offsets are relative to the start of the raw buffer.
    private val header =
        """
        {"__metadata__":{"format":"pt"},""" +
            """"a":{"dtype":"F32","shape":[2],"data_offsets":[0,8]},""" +
            """"b":{"dtype":"I64","shape":[1],"data_offsets":[8,16]}}"""

    private fun dataBlock(): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putFloat(1.0f)
        buf.putFloat(2.0f)
        buf.putLong(42L)
        return buf.array()
    }

    private fun container(
        headerText: String = header,
        data: ByteArray = dataBlock(),
    ): ByteArray {
        val headerBytes = headerText.toByteArray(Charsets.UTF_8)
        val out = ByteBuffer.allocate(8 + headerBytes.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putLong(headerBytes.size.toLong())
        out.put(headerBytes)
        out.put(data)
        return out.array()
    }

    @Test
    fun `parses tensor names, metadata and per-tensor shape and dtype`() {
        val reader = SafetensorsReader(container())

        assertEquals(setOf("a", "b"), reader.names())
        assertEquals(mapOf("format" to "pt"), reader.metadata)
        assertEquals("F32", reader.entry("a")?.dtype)
        assertEquals(listOf(2L), reader.entry("a")?.shape)
        assertEquals(listOf(1L), reader.entry("b")?.shape)
        assertEquals(8L, reader.entry("b")?.byteStart)
        assertEquals(16L, reader.entry("b")?.byteEnd)
        assertEquals(8L, reader.entry("b")?.byteLength)
    }

    @Test
    fun `__metadata__ is excluded from tensors`() {
        val reader = SafetensorsReader(container())

        assertEquals(2, reader.tensors.size)
        assertTrue(reader.tensors.none { it.name == "__metadata__" })
    }

    @Test
    fun `rawBytes returns the exact float payload of a tensor`() {
        val reader = SafetensorsReader(container())

        val raw = reader.rawBytes("a")
        val floats = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(8, raw.size)
        assertEquals(1.0f, floats.getFloat(0))
        assertEquals(2.0f, floats.getFloat(4))
    }

    @Test
    fun `slice gives a little-endian view of the int64 tensor`() {
        val reader = SafetensorsReader(container())

        assertEquals(42L, reader.slice("b").long)
    }

    @Test
    fun `entry for an absent tensor is null`() {
        val reader = SafetensorsReader(container())

        assertNull(reader.entry("missing"))
    }

    @Test
    fun `rawBytes for an absent tensor fails`() {
        val reader = SafetensorsReader(container())

        assertFailsWith<IllegalStateException> { reader.rawBytes("missing") }
    }

    @Test
    fun `a buffer too small for the length prefix fails closed`() {
        assertFailsWith<IllegalArgumentException> { SafetensorsReader(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `a header length running past the buffer fails closed`() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(9999L).array()
        assertFailsWith<IllegalArgumentException> { SafetensorsReader(bytes) }
    }

    @Test
    fun `data_offsets past the tensor buffer fail closed`() {
        val bad = """{"a":{"dtype":"F32","shape":[2],"data_offsets":[0,999]}}"""
        assertFailsWith<IllegalArgumentException> { SafetensorsReader(container(headerText = bad)) }
    }
}
