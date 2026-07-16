package com.phonetts.core.audio.export

// A user-selectable export container. The export-format dropdown is derived from the registered
// encoders' formats — no extension or MIME string is hardcoded in the UI (SSOT, same discipline
// as model facts). Adding Opus/AAC/MP3 is registering another encoder, nothing else.
data class ExportFormat(
    val id: String,
    val displayName: String,
    val fileExtension: String,
    val mimeType: String,
)
