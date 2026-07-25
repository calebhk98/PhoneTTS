package com.phonetts.app.speech

import com.phonetts.app.AppGraph
import com.phonetts.core.audio.transform.TempoStretch
import com.phonetts.core.session.ReaderSession

/**
 * The reader's persisted, non-transport preferences - the non-destructive post-processing toggles
 * (issue #40/#43), the reading font scale (issue #29), and long-document mode (issue #34) - split out
 * of `TtsViewModel` so a settings-toggle change never touches the same file as playback/generation
 * (mission: break up the god object). Every value here writes straight through to durable storage
 * ([AppGraph.sessionState] / [AppGraph.readingTextPreferences] / [AppGraph.longDocumentPreferences]);
 * this class holds no state itself, it is only a thinner surface than five separate stores.
 */
class ReaderPreferencesController(private val graph: AppGraph) {
    /** The non-destructive export/playback toggles, mirrored 1:1 from the durable [ReaderSession]. */
    data class Toggles(
        val trimSilence: Boolean,
        val normalizeVolume: Boolean,
        val crossfadeJoins: Boolean,
        val bassCut: Boolean,
        val presenceBoost: Boolean,
        val deEss: Boolean,
        val tempoBoost: Boolean,
        val tempoFactor: Float,
    )

    /** The current toggle values - read on startup/whenever the UI needs to resync (e.g. after a
     * write) so the two never disagree about what is currently set. */
    fun currentToggles(): Toggles = graph.sessionState.reader().toToggles()

    fun setTrimSilence(on: Boolean) = persist { it.copy(trimSilence = on) }

    fun setNormalizeVolume(on: Boolean) = persist { it.copy(normalizeVolume = on) }

    fun setCrossfadeJoins(on: Boolean) = persist { it.copy(crossfadeJoins = on) }

    fun setBassCut(on: Boolean) = persist { it.copy(bassCut = on) }

    fun setPresenceBoost(on: Boolean) = persist { it.copy(presenceBoost = on) }

    fun setDeEss(on: Boolean) = persist { it.copy(deEss = on) }

    /** Toggle the opt-in, playback-only beyond-native tempo boost (issue #43). Off by default. */
    fun setTempoBoost(on: Boolean) = persist { it.copy(tempoBoost = on) }

    /** Set the beyond-native tempo factor (clamped to [TempoStretch]'s advertised 0.1x-10x range). */
    fun setTempoFactor(factor: Float): Toggles {
        val clamped = factor.coerceIn(TempoStretch.MIN_FACTOR, TempoStretch.MAX_FACTOR)
        return persist { it.copy(tempoFactor = clamped) }
    }

    // Every toggle writes through to the durable session document, then re-reads it back - one
    // helper so no toggle added later can quietly forget to persist (issue: all eight used to be
    // UI-state-only and silently reverted to defaults on every launch).
    private fun persist(apply: (ReaderSession) -> ReaderSession): Toggles {
        graph.sessionState.updateReader(apply)
        return currentToggles()
    }

    private fun ReaderSession.toToggles() =
        Toggles(trimSilence, normalizeVolume, crossfadeJoins, bassCut, presenceBoost, deEss, tempoBoost, tempoFactor)

    /** The reading/editing text field's font scale (issue #29, A−/A+). */
    fun readingScale(): Float = graph.readingTextPreferences.scale()

    /** A+ : one step larger, persisted immediately. */
    fun increaseTextScale(): Float = graph.readingTextPreferences.increased()

    /** A− : one step smaller, persisted immediately. */
    fun decreaseTextScale(): Float = graph.readingTextPreferences.decreased()

    /** Long-document (spill-to-disk) mode (issue #34) - off by default. */
    fun longDocumentModeEnabled(): Boolean = graph.longDocumentPreferences.enabled()

    fun setLongDocumentMode(on: Boolean) = graph.longDocumentPreferences.setEnabled(on)
}
