package com.phonetts.engines.common

import com.phonetts.engines.common.json.MiniJson
import com.phonetts.engines.common.json.asFloatOrNull
import com.phonetts.engines.common.json.asIntOrNull
import com.phonetts.engines.common.json.asLongListOrEmpty
import com.phonetts.engines.common.json.asObjectOrNull
import com.phonetts.engines.common.json.asStringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MiniJsonTest {
    @Test
    fun parsesNestedObjectsArraysAndScalars() {
        val root =
            MiniJson.parse(
                """{ "name": "amy", "rate": 22050, "scale": 1.5, "ids": [1, 2, 3], "nested": {"ok": true} }""",
            )?.asObjectOrNull()
        requireNotNull(root)
        assertEquals("amy", root.getValue("name").asStringOrNull())
        assertEquals(22050, root.getValue("rate").asIntOrNull())
        assertEquals(1.5f, root.getValue("scale").asFloatOrNull())
        assertEquals(listOf(1L, 2L, 3L), root.getValue("ids").asLongListOrEmpty())
        assertTrue(root.getValue("nested").asObjectOrNull()!!.containsKey("ok"))
    }

    @Test
    fun handlesEscapesInStrings() {
        val root = MiniJson.parse("""{ "s": "a\"b\nA" }""")?.asObjectOrNull()
        assertEquals("a\"b\nA", root?.getValue("s")?.asStringOrNull())
    }

    @Test
    fun malformedInputReturnsNullNotThrow() {
        assertNull(MiniJson.parse("{ not json"))
        assertNull(MiniJson.parse(""))
    }
}
