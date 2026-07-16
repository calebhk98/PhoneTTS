package com.phonetts.core.text.extract

/** Reads plain-text files (.txt/.text/.log and `text/plain`) as UTF-8. */
class PlainTextExtractor : TextExtractor {
    override val id: String = "plain-text"

    override fun supports(
        fileName: String,
        mimeType: String?,
    ): Boolean {
        if (fileExtension(fileName) in EXTENSIONS) return true
        return mimeType == "text/plain"
    }

    override fun extract(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8)

    companion object {
        val EXTENSIONS = setOf("txt", "text", "log")
    }
}
