package com.phonetts.core.convert

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnnxProtoWriterTest {
    @Test
    fun `encodeVarint matches known protobuf encodings`() {
        assertContentEquals(byteArrayOf(0), ProtoWire.encodeVarint(0))
        assertContentEquals(byteArrayOf(1), ProtoWire.encodeVarint(1))
        assertContentEquals(byteArrayOf(0x7F), ProtoWire.encodeVarint(127))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0x01), ProtoWire.encodeVarint(128))
        assertContentEquals(byteArrayOf(0x96.toByte(), 0x01), ProtoWire.encodeVarint(150))
        assertContentEquals(byteArrayOf(0xAC.toByte(), 0x02), ProtoWire.encodeVarint(300))
    }

    @Test
    fun `tag encodes field number and wire type`() {
        // field 8, length-delimited (wire 2) -> (8 shl 3) or 2 = 66.
        assertEquals(66L, ProtoWire.tag(8, ProtoWire.WIRE_LEN))
        // field 2, varint (wire 0) -> 16.
        assertEquals(16L, ProtoWire.tag(2, ProtoWire.WIRE_VARINT))
    }

    @Test
    fun `TensorProto round-trips its dims, dtype, name and raw bytes`() {
        val raw = ByteArray(24) { it.toByte() }
        val encoded =
            OnnxProto.tensorProto(name = "w", dataType = OnnxDataType.FLOAT, dims = listOf(2, 3), rawData = raw)

        val fields = decodeFields(encoded)
        // dims (field 1) written unpacked: two varints, 2 then 3.
        assertEquals(listOf(2L, 3L), fields.filterIsInstance<VarintField>().filter { it.field == 1 }.map { it.value })
        // data_type (field 2) = FLOAT (1).
        assertEquals(1L, fields.filterIsInstance<VarintField>().single { it.field == 2 }.value)
        // name (field 8) = "w".
        assertEquals("w", fields.filterIsInstance<LenField>().single { it.field == 8 }.bytes.toString(Charsets.UTF_8))
        // raw_data (field 9) is the exact input bytes.
        assertContentEquals(raw, fields.filterIsInstance<LenField>().single { it.field == 9 }.bytes)
    }

    @Test
    fun `attributeInt carries name, int value and INT type discriminator`() {
        val encoded = OnnxProto.attributeInt(name = "axis", value = 7)

        val fields = decodeFields(encoded)
        val name = fields.filterIsInstance<LenField>().single { it.field == 1 }.bytes.toString(Charsets.UTF_8)
        assertEquals("axis", name)
        assertEquals(7L, fields.filterIsInstance<VarintField>().single { it.field == 3 }.value)
        // type (field 20) = AttributeType.INT (2).
        assertEquals(2L, fields.filterIsInstance<VarintField>().single { it.field == 20 }.value)
    }

    @Test
    fun `float attribute uses a 32-bit little-endian payload`() {
        val encoded = OnnxProto.attributeFloat(name = "scale", value = 1.0f)

        val fields = decodeFields(encoded)
        val f = fields.filterIsInstance<I32Field>().single { it.field == 2 }
        // 1.0f little-endian is 00 00 80 3F.
        assertContentEquals(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x3F), f.bytes)
    }

    @Test
    fun `a full model wraps a graph and is non-empty`() {
        val initializer = OnnxProto.tensorProto("w", OnnxDataType.FLOAT, listOf(1), ByteArray(4))
        val input = OnnxProto.valueInfo("x", OnnxDataType.FLOAT, listOf(null, 1))
        val output = OnnxProto.valueInfo("y", OnnxDataType.FLOAT, listOf(null, 1))
        val node = OnnxProto.node("MatMul", listOf("x", "w"), listOf("y"), "matmul0")
        val graph = OnnxProto.graph("g", listOf(node), listOf(initializer), listOf(input), listOf(output))
        val model = OnnxProto.model(graph, opsetVersion = 17, producer = "phonetts", irVersion = 8)

        val fields = decodeFields(model)
        assertTrue(model.isNotEmpty())
        // ir_version (field 1) and graph (field 7) are present.
        assertEquals(8L, fields.filterIsInstance<VarintField>().single { it.field == 1 }.value)
        assertTrue(fields.filterIsInstance<LenField>().any { it.field == 7 })
    }
}
