package com.phonetts.engines.supertonic

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SupertonicStyleTest {
    @Test
    fun `parses both tensors with their real dims and flattened data in order`() {
        val ttl = FloatArray(TTL_SIZE) { it.toFloat() }
        val dp = FloatArray(DP_SIZE) { it.toFloat() + 1000f }
        val json = styleJson(ttl, dp)

        val style = assertNotNull(SupertonicStyle.parse(json))

        assertContentEquals(TTL_DIMS, style.ttlShape)
        assertContentEquals(DP_DIMS, style.dpShape)
        assertContentEquals(ttl, style.ttl)
        assertContentEquals(dp, style.dp)
    }

    @Test
    fun `missing style_dp is rejected`() {
        val json = """{"style_ttl":{"dims":[1,1,1],"data":[[[0.1]]]}}"""

        assertNull(SupertonicStyle.parse(json))
    }

    @Test
    fun `missing style_ttl is rejected`() {
        val json = """{"style_dp":{"dims":[1,1,1],"data":[[[0.1]]]}}"""

        assertNull(SupertonicStyle.parse(json))
    }

    @Test
    fun `malformed json yields null, never throws`() {
        assertNull(SupertonicStyle.parse("not json"))
    }
}
