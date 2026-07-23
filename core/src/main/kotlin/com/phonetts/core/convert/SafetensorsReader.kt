package com.phonetts.core.convert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One tensor's metadata from a safetensors header: its [name], [dtype] string (e.g. "F32",
 * "F16", "I64"), integer [shape], and the half-open byte range [byteStart, byteEnd) of its
 * data, expressed relative to the start of the raw tensor buffer (i.e. after the JSON header).
 */
data class SafetensorEntry(
    val name: String,
    val dtype: String,
    val shape: List<Long>,
    val byteStart: Long,
    val byteEnd: Long,
) {
    /** Number of raw bytes this tensor occupies. */
    val byteLength: Long get() = byteEnd - byteStart
}

/**
 * Reads the safetensors container in pure JVM (ByteBuffer + kotlinx JSON), so it is unit-testable
 * from a hand-built byte array with no filesystem. The container is:
 *
 *  1. 8 bytes: little-endian u64 = length N of the JSON header.
 *  2. N bytes: UTF-8 JSON header. A map of tensorName -> { "dtype", "shape", "data_offsets":[a,b] }.
 *     The reserved `__metadata__` key (if present) is a string-to-string map, NOT a tensor.
 *  3. The rest: the raw tensor byte buffer, indexed by each tensor's data_offsets.
 *
 * Fails closed: a truncated buffer, an out-of-range offset, or a malformed header throws rather
 * than returning a half-parsed guess - a transcoder must not build a graph from garbage weights.
 */
class SafetensorsReader(private val bytes: ByteArray) {
    /** The reserved `__metadata__` map, or empty if the header had none. */
    val metadata: Map<String, String>

    private val entries: Map<String, SafetensorEntry>
    private val dataStart: Int

    init {
        require(bytes.size >= HEADER_LEN_BYTES) { "safetensors buffer too small for its 8-byte length prefix" }
        val headerLen = readHeaderLength(bytes)
        val headerEnd = HEADER_LEN_BYTES + headerLen
        require(headerEnd <= bytes.size) { "safetensors header length $headerLen runs past the buffer" }
        dataStart = headerEnd
        val json = String(bytes, HEADER_LEN_BYTES, headerLen, Charsets.UTF_8)
        val root = Json.parseToJsonElement(json).jsonObject
        metadata = parseMetadata(root)
        entries = parseEntries(root, bytes.size - dataStart)
    }

    /** All tensor entries in header order (the `__metadata__` key excluded). */
    val tensors: List<SafetensorEntry> get() = entries.values.toList()

    /** The set of tensor names present. */
    fun names(): Set<String> = entries.keys

    /** The entry for [name], or null if no such tensor exists (fail-closed lookup). */
    fun entry(name: String): SafetensorEntry? = entries[name]

    /** A copy of [name]'s raw bytes. Throws if the tensor is absent. */
    fun rawBytes(name: String): ByteArray {
        val e = entries[name] ?: error("no tensor named '$name'")
        return bytes.copyOfRange(dataStart + e.byteStart.toInt(), dataStart + e.byteEnd.toInt())
    }

    /**
     * A little-endian, read-only [ByteBuffer] view of [name]'s bytes (no copy). Throws if absent.
     * Callers decode the tensor's numeric type (F32/F16/I64/...) from [SafetensorEntry.dtype].
     */
    fun slice(name: String): ByteBuffer {
        val e = entries[name] ?: error("no tensor named '$name'")
        return ByteBuffer.wrap(bytes, dataStart + e.byteStart.toInt(), e.byteLength.toInt())
            .slice()
            .asReadOnlyBuffer()
            .order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun readHeaderLength(source: ByteArray): Int {
        val len = ByteBuffer.wrap(source, 0, HEADER_LEN_BYTES).order(ByteOrder.LITTLE_ENDIAN).long
        require(len in 0..Int.MAX_VALUE.toLong()) { "unreasonable safetensors header length: $len" }
        return len.toInt()
    }

    private fun parseMetadata(root: JsonObject): Map<String, String> {
        val meta = root[METADATA_KEY] ?: return emptyMap()
        return meta.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
    }

    private fun parseEntries(
        root: JsonObject,
        dataLen: Int,
    ): Map<String, SafetensorEntry> {
        val result = LinkedHashMap<String, SafetensorEntry>()
        for ((name, element) in root) {
            if (name == METADATA_KEY) continue
            result[name] = toEntry(name, element.jsonObject, dataLen)
        }
        return result
    }

    private fun toEntry(
        name: String,
        obj: JsonObject,
        dataLen: Int,
    ): SafetensorEntry {
        val dtype = requireField(obj, name, "dtype").jsonPrimitive.content
        val shape = requireField(obj, name, "shape").jsonArray.map { it.jsonPrimitive.long }
        val offsets = requireField(obj, name, "data_offsets").jsonArray
        require(offsets.size == OFFSET_PAIR) { "tensor '$name' data_offsets must be [start, end]" }
        val start = offsets[0].jsonPrimitive.long
        val end = offsets[1].jsonPrimitive.long
        require(start in 0..end && end <= dataLen) { "tensor '$name' offsets [$start, $end] out of range" }
        return SafetensorEntry(name, dtype, shape, start, end)
    }

    private fun requireField(
        obj: JsonObject,
        tensor: String,
        field: String,
    ) = obj[field] ?: error("tensor '$tensor' header is missing '$field'")

    private companion object {
        const val HEADER_LEN_BYTES = 8
        const val OFFSET_PAIR = 2
        const val METADATA_KEY = "__metadata__"
    }
}
