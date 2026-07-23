package com.phonetts.core.text

/**
 * Derives a stable, content-based id for a block of text. Several features key per-document state
 * off the SAME id - [com.phonetts.core.prefs.DocumentMemory]'s resume position and
 * [com.phonetts.core.prefs.DocumentLibrary]'s saved documents - so opening identical text from
 * either the main reader or the library always lands on the same identity without the two features
 * coordinating directly. Deliberately just the text's own hash (no random/opaque id minted at save
 * time): two callers that see the same text always agree.
 */
object DocumentId {
    fun of(text: String): String = text.hashCode().toString()
}
