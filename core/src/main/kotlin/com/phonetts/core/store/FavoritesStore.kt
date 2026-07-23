package com.phonetts.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A favorited voice: a specific [voiceId] within a specific [modelId]. A voice is only meaningful
 * paired with the model it belongs to, so both halves are stored (issue #119 - "a favorite voice
 * may live in any of several models"). Callers must source both ids from a descriptor
 * (`descriptor.id` / `descriptor.voices[].id`, spec §5.7 SSOT); this store only records the choice.
 */
@Serializable
data class FavoriteVoiceRef(
    val modelId: String,
    val voiceId: String,
)

/**
 * A model the user flagged/downvoted during testing (issue #113 - e.g. an "English" slot that turns
 * out to be a Russian model, or a broken engine). [reason] is an optional free-text note; [atMs] is
 * a caller-supplied timestamp (this class never reads the wall clock, matching every other seam) so
 * a later screen can offer to delete or export the flagged set.
 */
@Serializable
data class FlaggedModel(
    val modelId: String,
    val reason: String? = null,
    val atMs: Long = 0L,
)

/** The whole persisted favorites/flags document. One small JSON blob; see [FavoritesStore]. */
@Serializable
private data class FavoritesState(
    val voices: List<FavoriteVoiceRef> = emptyList(),
    val models: List<String> = emptyList(),
    val flagged: List<FlaggedModel> = emptyList(),
)

/**
 * The single shared Favorites store the tournament (#113), home-favorites (#119), and persistence
 * (#114) work all consume. It covers three things a user marks while comparing models, and which
 * must survive a restart:
 *
 *  - **favorite voices** - a `(modelId, voiceId)` pair, so a favorite voice can be surfaced on Home
 *    independent of which model owns it (issue #119);
 *  - **favorite models** - a whole `modelId` (issue #113, "save a model as a favorite");
 *  - **flagged/downvoted models** - a `modelId` plus an optional reason, so bad models can be
 *    reviewed, deleted, or exported after a tournament (issue #113).
 *
 * Pure `:core` logic over an injected [DurableStore] (storage lives in `:app`), so it is
 * unit-testable on a plain JVM. State is **loaded once at construction** and every mutation writes
 * the whole (small, bounded) document straight back, so a relaunch that constructs a fresh instance
 * sees exactly what the previous session left. Each list is newest-first and de-duplicated by id;
 * marking something already marked is idempotent. Fails closed: an unreadable/corrupt document loads
 * as empty rather than throwing.
 */
class FavoritesStore(
    private val store: DurableStore,
    private val documentName: String = DOCUMENT_NAME,
    private val maxPerList: Int = DEFAULT_MAX_PER_LIST,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var state: FavoritesState = load()

    // --- Favorite voices (modelId + voiceId), issue #119 -----------------------------------------

    /** Whether the voice [voiceId] within [modelId] is currently favorited. */
    fun isFavoriteVoice(
        modelId: String,
        voiceId: String,
    ): Boolean = FavoriteVoiceRef(modelId, voiceId) in state.voices

    /** Every favorited voice, newest first. */
    fun favoriteVoices(): List<FavoriteVoiceRef> = state.voices

    /** Adds or removes the `(modelId, voiceId)` voice favorite. Idempotent. */
    fun setFavoriteVoice(
        modelId: String,
        voiceId: String,
        favorite: Boolean,
    ) {
        val ref = FavoriteVoiceRef(modelId, voiceId)
        val updated = if (favorite) prepend(state.voices, ref) { it == ref } else state.voices - ref
        persist(state.copy(voices = updated))
    }

    /** Flips the voice favorite and returns the new state (true = now favorited). */
    fun toggleFavoriteVoice(
        modelId: String,
        voiceId: String,
    ): Boolean {
        val now = !isFavoriteVoice(modelId, voiceId)
        setFavoriteVoice(modelId, voiceId, now)
        return now
    }

    // --- Favorite models (modelId), issue #113 ---------------------------------------------------

    /** Whether [modelId] is a favorited model. */
    fun isFavoriteModel(modelId: String): Boolean = modelId in state.models

    /** Every favorited model id, newest first. */
    fun favoriteModels(): List<String> = state.models

    /** Adds or removes [modelId] as a favorite model. Idempotent. */
    fun setFavoriteModel(
        modelId: String,
        favorite: Boolean,
    ) {
        val updated = if (favorite) prepend(state.models, modelId) { it == modelId } else state.models - modelId
        persist(state.copy(models = updated))
    }

    /** Flips the model favorite and returns the new state (true = now favorited). */
    fun toggleFavoriteModel(modelId: String): Boolean {
        val now = !isFavoriteModel(modelId)
        setFavoriteModel(modelId, now)
        return now
    }

    // --- Flagged / downvoted models (modelId + optional reason), issue #113 -----------------------

    /** Whether [modelId] has been flagged/downvoted. */
    fun isFlagged(modelId: String): Boolean = state.flagged.any { it.modelId == modelId }

    /** Every flagged model, newest first. */
    fun flaggedModels(): List<FlaggedModel> = state.flagged

    /**
     * Flags/downvotes [modelId] with an optional [reason] and caller-supplied [atMs] timestamp.
     * Re-flagging the same model replaces its previous entry (and moves it to newest).
     */
    fun flagModel(
        modelId: String,
        reason: String? = null,
        atMs: Long = 0L,
    ) {
        val entry = FlaggedModel(modelId, reason, atMs)
        persist(state.copy(flagged = prepend(state.flagged, entry) { it.modelId == modelId }))
    }

    /** Removes [modelId] from the flagged set. A no-op if it wasn't flagged. */
    fun unflagModel(modelId: String) {
        persist(state.copy(flagged = state.flagged.filterNot { it.modelId == modelId }))
    }

    // --- internals -------------------------------------------------------------------------------

    // Newest-first insert that first drops any existing match, then caps the list.
    private fun <E> prepend(
        current: List<E>,
        entry: E,
        matches: (E) -> Boolean,
    ): List<E> = (listOf(entry) + current.filterNot(matches)).take(maxPerList)

    private fun persist(next: FavoritesState) {
        state = next
        runCatching { store.write(documentName, json.encodeToString(FavoritesState.serializer(), next)) }
    }

    private fun load(): FavoritesState {
        val raw = store.read(documentName) ?: return FavoritesState()
        return runCatching { json.decodeFromString(FavoritesState.serializer(), raw) }.getOrDefault(FavoritesState())
    }

    companion object {
        /** The [DurableStore] document name (one file) favorites/flags are persisted under. */
        const val DOCUMENT_NAME = "favorites"

        /** Per-list cap so favorites/flags stay bounded even after long testing sessions. */
        const val DEFAULT_MAX_PER_LIST = 500
    }
}
