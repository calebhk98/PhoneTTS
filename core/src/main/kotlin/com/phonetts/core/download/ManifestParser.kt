package com.phonetts.core.download

import kotlinx.serialization.json.Json

/**
 * Parses (and re-serializes) a [ModelManifest] from JSON. Pure and Android-free so it is
 * unit-testable; the Android layer only supplies the raw JSON string it fetched/read.
 */
object ManifestParser {
    private val json =
        Json {
            ignoreUnknownKeys = true // tolerate forward-compatible manifest additions
            prettyPrint = true
        }

    fun parse(text: String): ModelManifest = json.decodeFromString(ModelManifest.serializer(), text)

    fun encode(manifest: ModelManifest): String = json.encodeToString(ModelManifest.serializer(), manifest)
}
