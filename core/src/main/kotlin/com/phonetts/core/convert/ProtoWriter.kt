package com.phonetts.core.convert

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The low-level protobuf wire-format primitives (protobuf encoding spec), used to hand-emit the
 * handful of ONNX messages [OnnxProto] needs without pulling in a protobuf runtime.
 *
 * A field is written as a tag varint `(fieldNumber shl 3) or wireType`, then the payload:
 *  - wire type 0 (varint): a base-128 little-endian varint.
 *  - wire type 2 (length-delimited): a varint length, then that many bytes (strings, bytes,
 *    packed repeated fields, and nested messages all use this).
 *  - wire type 5 (32-bit): four little-endian bytes (used for `float`).
 *
 * Signed int64 values are encoded as their unsigned two's-complement representation (up to 10
 * bytes), matching how a standard protobuf encoder serializes a non-`sint` int64.
 */
internal object ProtoWire {
    const val WIRE_VARINT = 0
    const val WIRE_LEN = 2
    const val WIRE_I32 = 5

    private const val CONTINUATION_BIT = 0x80
    private const val SEGMENT_MASK = 0x7F
    private const val SEGMENT_BITS = 7
    private const val TAG_SHIFT = 3

    /** The tag varint value for [field] carrying [wireType]. */
    fun tag(
        field: Int,
        wireType: Int,
    ): Long = ((field shl TAG_SHIFT) or wireType).toLong()

    /** Encodes [value] as a standalone varint byte array (exposed for direct unit testing). */
    fun encodeVarint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        writeVarint(out, value)
        return out.toByteArray()
    }

    /** Appends [value] as a varint to [out]. */
    fun writeVarint(
        out: ByteArrayOutputStream,
        value: Long,
    ) {
        var remaining = value
        while (true) {
            val low = (remaining and SEGMENT_MASK.toLong()).toInt()
            remaining = remaining ushr SEGMENT_BITS
            if (remaining == 0L) {
                out.write(low)
                return
            }
            out.write(low or CONTINUATION_BIT)
        }
    }
}

/**
 * A tiny append-only builder for one protobuf message. Each `writeXxx` appends a single field and
 * returns `this` for chaining; [toByteArray] yields the encoded message. Nested messages are built
 * with their own [ProtoBuffer] and embedded via [message]; a repeated field is just the same field
 * number written more than once.
 */
internal class ProtoBuffer {
    private val out = ByteArrayOutputStream()

    fun varint(
        field: Int,
        value: Long,
    ): ProtoBuffer {
        ProtoWire.writeVarint(out, ProtoWire.tag(field, ProtoWire.WIRE_VARINT))
        ProtoWire.writeVarint(out, value)
        return this
    }

    fun int32(
        field: Int,
        value: Int,
    ): ProtoBuffer = varint(field, value.toLong())

    fun float(
        field: Int,
        value: Float,
    ): ProtoBuffer {
        ProtoWire.writeVarint(out, ProtoWire.tag(field, ProtoWire.WIRE_I32))
        out.write(ByteBuffer.allocate(Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        return this
    }

    fun bytes(
        field: Int,
        value: ByteArray,
    ): ProtoBuffer {
        ProtoWire.writeVarint(out, ProtoWire.tag(field, ProtoWire.WIRE_LEN))
        ProtoWire.writeVarint(out, value.size.toLong())
        out.write(value)
        return this
    }

    fun string(
        field: Int,
        value: String,
    ): ProtoBuffer = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun message(
        field: Int,
        value: ByteArray,
    ): ProtoBuffer = bytes(field, value)

    /** A `repeated int64` field written unpacked (one tag per value), the proto2-canonical form. */
    fun repeatedInt64(
        field: Int,
        values: List<Long>,
    ): ProtoBuffer {
        for (v in values) varint(field, v)
        return this
    }

    /** A `[packed=true] repeated int64` field: one length-delimited run of concatenated varints. */
    fun packedInt64(
        field: Int,
        values: List<Long>,
    ): ProtoBuffer {
        if (values.isEmpty()) return this
        val inner = ByteArrayOutputStream()
        for (v in values) ProtoWire.writeVarint(inner, v)
        return bytes(field, inner.toByteArray())
    }

    /** A `[packed=true] repeated float` field: one length-delimited run of 32-bit little-endian floats. */
    fun packedFloat(
        field: Int,
        values: FloatArray,
    ): ProtoBuffer {
        if (values.isEmpty()) return this
        val buf = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buf.putFloat(v)
        return bytes(field, buf.array())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}
