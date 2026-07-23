package com.phonetts.core.convert

/** A minimal protobuf field, decoded by the test-only [decodeFields] reader below. */
sealed interface DecodedField {
    val field: Int
}

data class VarintField(override val field: Int, val value: Long) : DecodedField

data class LenField(override val field: Int, val bytes: ByteArray) : DecodedField {
    override fun equals(other: Any?): Boolean =
        other is LenField && other.field == field && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = field * HASH_MULTIPLIER + bytes.contentHashCode()

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

data class I32Field(override val field: Int, val bytes: ByteArray) : DecodedField {
    override fun equals(other: Any?): Boolean =
        other is I32Field && other.field == field && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = field * HASH_MULTIPLIER + bytes.contentHashCode()

    private companion object {
        const val HASH_MULTIPLIER = 31
    }
}

private const val VARINT_LOW_MASK = 0x7F
private const val VARINT_CONT_BIT = 0x80
private const val VARINT_SHIFT = 7
private const val TAG_SHIFT = 3
private const val WIRE_MASK = 0x7
private const val WIRE_VARINT = 0
private const val WIRE_LEN = 2
private const val WIRE_I32 = 5
private const val I32_BYTES = 4

private data class Varint(val value: Long, val next: Int)

private fun readVarint(
    data: ByteArray,
    start: Int,
): Varint {
    var result = 0L
    var shift = 0
    var pos = start
    while (true) {
        val b = data[pos].toInt() and 0xFF
        result = result or ((b and VARINT_LOW_MASK).toLong() shl shift)
        pos++
        if (b and VARINT_CONT_BIT == 0) return Varint(result, pos)
        shift += VARINT_SHIFT
    }
}

/** Walks [data] as a flat protobuf message and returns its top-level fields (varint/len/i32 only). */
fun decodeFields(data: ByteArray): List<DecodedField> {
    val fields = mutableListOf<DecodedField>()
    var pos = 0
    while (pos < data.size) {
        val tag = readVarint(data, pos)
        val field = (tag.value ushr TAG_SHIFT).toInt()
        val wire = (tag.value and WIRE_MASK.toLong()).toInt()
        pos = readField(data, tag.next, field, wire, fields)
    }
    return fields
}

private fun readField(
    data: ByteArray,
    start: Int,
    field: Int,
    wire: Int,
    sink: MutableList<DecodedField>,
): Int {
    if (wire == WIRE_VARINT) {
        val v = readVarint(data, start)
        sink.add(VarintField(field, v.value))
        return v.next
    }
    if (wire == WIRE_I32) {
        sink.add(I32Field(field, data.copyOfRange(start, start + I32_BYTES)))
        return start + I32_BYTES
    }
    if (wire == WIRE_LEN) {
        val len = readVarint(data, start)
        val end = len.next + len.value.toInt()
        sink.add(LenField(field, data.copyOfRange(len.next, end)))
        return end
    }
    error("unsupported wire type $wire")
}
