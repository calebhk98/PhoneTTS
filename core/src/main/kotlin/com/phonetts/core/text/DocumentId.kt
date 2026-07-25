package com.phonetts.core.text

import com.phonetts.core.download.Sha256Verifier

/**
 * Derives a stable, content-based id for a block of text. Several features key per-document state
 * off the SAME id - [com.phonetts.core.prefs.DocumentMemory]'s resume position and
 * [com.phonetts.core.prefs.DocumentLibrary]'s saved documents - so opening identical text from
 * either the main reader or the library always lands on the same identity without the two features
 * coordinating directly. Deliberately just a hash of the text's own content (no random/opaque id
 * minted at save time): two callers that see the same text always agree.
 *
 * **Issue #140:** this used to be `text.hashCode().toString()` - Java's 32-bit `String.hashCode`,
 * which collides trivially (e.g. `"Aa"` and `"BB"` hash identically). Two different saved documents
 * that collided would silently overwrite each other in [com.phonetts.core.prefs.DocumentLibrary] -
 * title, text, and resume point all gone, with no error. This now reuses [Sha256Verifier] (already
 * used to verify downloaded model weights) over the text's UTF-8 bytes, truncated to
 * [ID_HEX_LENGTH] hex characters (128 bits) - astronomically collision-resistant for a personal
 * document library, while staying short enough to embed in preference keys and file names.
 *
 * [com.phonetts.core.prefs.DocumentLibraryMigration] handles moving existing data saved under the
 * old scheme onto ids computed here; this object only knows the CURRENT scheme.
 */
object DocumentId {
    /** Hex characters kept from the full SHA-256 digest (128 of the 256 bits) - see class kdoc. */
    const val ID_HEX_LENGTH = 32

    fun of(text: String): String = Sha256Verifier.hashHex(text.toByteArray(Charsets.UTF_8)).take(ID_HEX_LENGTH)
}
