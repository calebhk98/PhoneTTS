package com.phonetts.core.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sha256VerifierTest {
    // Known NIST vector: SHA-256("abc").
    private val abcHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun hashesKnownVector() {
        assertEquals(abcHash, Sha256Verifier.hashHex("abc".toByteArray()))
    }

    @Test
    fun streamAndByteArrayAgree() {
        val bytes = "the quick brown fox".toByteArray()
        assertEquals(Sha256Verifier.hashHex(bytes), Sha256Verifier.hashHex(bytes.inputStream()))
    }

    @Test
    fun verifyAcceptsMatchingHashCaseInsensitively() {
        assertTrue(Sha256Verifier.verify("abc".toByteArray(), abcHash.uppercase()))
    }

    @Test
    fun verifyRejectsMismatch() {
        assertFalse(Sha256Verifier.verify("abc".toByteArray(), abcHash.replaceFirst("b", "c")))
    }
}
