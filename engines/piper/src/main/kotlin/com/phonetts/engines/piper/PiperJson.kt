package com.phonetts.engines.piper

/**
 * A minimal, dependency-free JSON reader for Piper's `<voice>.onnx.json` sidecar files.
 *
 * The `engines/piper` module deliberately has no JSON library dependency (its `build.gradle.kts`
 * is out of scope for this change), so this hand-rolled reader covers exactly the JSON subset a
 * Piper voice config needs: nested objects, arrays of numbers, strings, numbers, booleans, null.
 * Malformed input yields `null` from [MiniJson.parse] rather than throwing, which keeps callers
 * (notably [PiperVoiceConfig.parse], used from `inspect()`) fail-closed.
 */
internal sealed class JsonValue {
    data class JsonObject(val value: Map<String, JsonValue>) : JsonValue()

    data class JsonArray(val value: List<JsonValue>) : JsonValue()

    data class JsonString(val value: String) : JsonValue()

    data class JsonNumber(val value: Double) : JsonValue()

    data class JsonBool(val value: Boolean) : JsonValue()

    object JsonNull : JsonValue()
}

internal fun JsonValue.asObjectOrNull(): Map<String, JsonValue>? = (this as? JsonValue.JsonObject)?.value

internal fun JsonValue.asArrayOrNull(): List<JsonValue>? = (this as? JsonValue.JsonArray)?.value

internal fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.JsonString)?.value

internal fun JsonValue.asIntOrNull(): Int? = (this as? JsonValue.JsonNumber)?.value?.toInt()

internal fun JsonValue.asFloatOrNull(): Float? = (this as? JsonValue.JsonNumber)?.value?.toFloat()

internal fun JsonValue.asLongListOrEmpty(): List<Long> =
    asArrayOrNull()?.mapNotNull { (it as? JsonValue.JsonNumber)?.value?.toLong() } ?: emptyList()

internal object MiniJson {
    /** Parses [text] as JSON, or returns null on any malformed input (fail-closed, never throws). */
    fun parse(text: String): JsonValue? = runCatching { JsonParser(text).parseDocument() }.getOrNull()

    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JsonString(parseStringLiteral())
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue.JsonObject {
            expect('{')
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.JsonObject(map)
            }
            readObjectEntries(map)
            skipWhitespace()
            expect('}')
            return JsonValue.JsonObject(map)
        }

        private fun readObjectEntries(map: MutableMap<String, JsonValue>) {
            while (true) {
                skipWhitespace()
                val key = parseStringLiteral()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                if (peek() != ',') break
                pos++
            }
        }

        private fun parseArray(): JsonValue.JsonArray {
            expect('[')
            val list = mutableListOf<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.JsonArray(list)
            }
            readArrayElements(list)
            skipWhitespace()
            expect(']')
            return JsonValue.JsonArray(list)
        }

        private fun readArrayElements(list: MutableList<JsonValue>) {
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                if (peek() != ',') break
                pos++
            }
        }

        private fun parseStringLiteral(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                if (c == '"') break
                sb.append(if (c == '\\') parseEscape() else c)
            }
            return sb.toString()
        }

        private fun parseEscape(): Char {
            val e = text[pos++]
            return when (e) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'n' -> '\n'
                't' -> '\t'
                'r' -> '\r'
                'b' -> '\b'
                'u' -> parseUnicodeEscape()
                else -> e
            }
        }

        private fun parseUnicodeEscape(): Char {
            val hex = text.substring(pos, pos + UNICODE_ESCAPE_LEN)
            pos += UNICODE_ESCAPE_LEN
            return hex.toInt(HEX_RADIX).toChar()
        }

        private fun parseBoolean(): JsonValue.JsonBool =
            if (text.startsWith("true", pos)) {
                pos += TRUE_LEN
                JsonValue.JsonBool(true)
            } else {
                pos += FALSE_LEN
                JsonValue.JsonBool(false)
            }

        private fun parseNull(): JsonValue {
            pos += NULL_LEN
            return JsonValue.JsonNull
        }

        private fun parseNumber(): JsonValue.JsonNumber {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] in "-+.eE")) pos++
            return JsonValue.JsonNumber(text.substring(start, pos).toDouble())
        }

        private fun peek(): Char = text[pos]

        private fun expect(c: Char) {
            require(pos < text.length && text[pos] == c) { "expected '$c' at index $pos" }
            pos++
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private companion object {
            const val UNICODE_ESCAPE_LEN = 4
            const val HEX_RADIX = 16
            const val TRUE_LEN = 4
            const val FALSE_LEN = 5
            const val NULL_LEN = 4
        }
    }
}
