package com.phonetts.engines.common.json

/**
 * A minimal, dependency-free JSON reader shared by the engine modules for their small companion
 * files (`config.json`, `<voice>.onnx.json`, voice tables). The engine modules deliberately take
 * no JSON-library dependency; this hand-rolled reader covers the JSON subset those files need:
 * nested objects, arrays, strings, numbers, booleans, null. Malformed input yields `null` from
 * [MiniJson.parse] rather than throwing, keeping fingerprinting callers fail-closed.
 */
sealed class JsonValue {
    data class JsonObject(val value: Map<String, JsonValue>) : JsonValue()

    data class JsonArray(val value: List<JsonValue>) : JsonValue()

    data class JsonString(val value: String) : JsonValue()

    data class JsonNumber(val value: Double) : JsonValue()

    data class JsonBool(val value: Boolean) : JsonValue()

    object JsonNull : JsonValue()
}

fun JsonValue.asObjectOrNull(): Map<String, JsonValue>? = (this as? JsonValue.JsonObject)?.value

fun JsonValue.asArrayOrNull(): List<JsonValue>? = (this as? JsonValue.JsonArray)?.value

fun JsonValue.asStringOrNull(): String? = (this as? JsonValue.JsonString)?.value

fun JsonValue.asIntOrNull(): Int? = (this as? JsonValue.JsonNumber)?.value?.toInt()

fun JsonValue.asFloatOrNull(): Float? = (this as? JsonValue.JsonNumber)?.value?.toFloat()

fun JsonValue.asLongListOrEmpty(): List<Long> =
    asArrayOrNull()?.mapNotNull { (it as? JsonValue.JsonNumber)?.value?.toLong() } ?: emptyList()

/**
 * Parses [text] as a flat JSON array of strings, e.g. `["Bella","Jasper"]` — the shape every
 * engine's own "list of names" side file uses. Malformed input, a non-array document, or a
 * non-string element all fall out as an empty list rather than throwing (fail-closed, matching
 * [MiniJson.parse] itself) — this is the one shared reader for that shape, in place of each
 * engine hand-rolling its own quoted-string regex.
 */
fun parseStringArray(text: String): List<String> =
    MiniJson.parse(text)?.asArrayOrNull()?.mapNotNull { it.asStringOrNull() } ?: emptyList()

object MiniJson {
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
