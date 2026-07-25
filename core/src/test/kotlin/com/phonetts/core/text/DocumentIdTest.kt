package com.phonetts.core.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DocumentIdTest {
    @Test
    fun `two texts that collide under the old 32-bit String hashCode scheme do NOT collide under DocumentId`() {
        // "Aa" and "BB" are a textbook java String#hashCode collision: both hash to 2112. Issue #140 -
        // this is exactly the class of bug the old `text.hashCode().toString()` id scheme was exposed
        // to (two DIFFERENT saved documents silently overwriting each other).
        val textA = "Aa"
        val textB = "BB"
        assertEquals(textA.hashCode(), textB.hashCode(), "fixture assumption: these must collide under String.hashCode")

        assertNotEquals(DocumentId.of(textA), DocumentId.of(textB))
    }

    @Test
    fun `id is deterministic - the same text always yields the same id`() {
        val text = "Some document text.\nWith multiple lines."
        assertEquals(DocumentId.of(text), DocumentId.of(text))
    }

    @Test
    fun `different texts yield different ids`() {
        assertNotEquals(DocumentId.of("First document."), DocumentId.of("Second, unrelated document."))
    }

    @Test
    fun `id is a fixed-length lowercase hex string`() {
        val id = DocumentId.of("Any text at all.")
        assertEquals(DocumentId.ID_HEX_LENGTH, id.length)
        assertTrue(id.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `empty text still yields a stable id`() {
        assertEquals(DocumentId.of(""), DocumentId.of(""))
    }
}
