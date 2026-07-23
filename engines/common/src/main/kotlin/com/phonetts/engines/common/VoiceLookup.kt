package com.phonetts.engines.common

import com.phonetts.core.engine.Voice

/**
 * Locates [voiceId] inside [voices] by index, or fails clearly naming [engineLabel] and the
 * unknown id. `synthesizeSentence()` receiving a voice id absent from its own `voices()` list is
 * a caller bug - the descriptor's own voices are the only ids a caller should ever pass - so this
 * fails loudly rather than silently defaulting to voice 0. Every engine that selects a voice by
 * list position (as opposed to a map keyed by voice id) repeated this same
 * `indexOfFirst` + `require(index >= 0)` pair; this is the one place it lives.
 */
fun requireVoiceIndex(
    voices: List<Voice>,
    voiceId: String,
    engineLabel: String,
): Int {
    val index = voices.indexOfFirst { it.id == voiceId }
    require(index >= 0) { "$engineLabel: voice '$voiceId' is not among its known voices" }
    return index
}
