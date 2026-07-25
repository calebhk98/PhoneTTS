package com.phonetts.core.session

import com.phonetts.core.store.DurableStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the reader screen looked like when the user last left it. Everything here is a user choice
 * that survived nothing before: the text they typed or imported, and the audio-shaping toggles the
 * Settings screen offers.
 *
 * Eight of these toggles silently reset on every launch, which is what "settings didn't save" and
 * "data is lost on close and reopen" describe. They were plain `mutableState.update` setters with no
 * store behind them at all.
 */
@Serializable
data class ReaderSession(
    val text: String = "",
    val exportFormatId: String? = null,
    val trimSilence: Boolean = false,
    val normalizeVolume: Boolean = false,
    val crossfadeJoins: Boolean = false,
    val bassCut: Boolean = false,
    val presenceBoost: Boolean = false,
    val deEss: Boolean = false,
    val tempoBoost: Boolean = false,
    val tempoFactor: Float = 1f,
)

/** A list screen's sort + filter choices (Browse, Downloaded models, Benchmarks). */
@Serializable
data class ListViewSession(
    val sortKey: String? = null,
    val ascending: Boolean = true,
    val query: String = "",
    /** Screen-specific filters, kept as plain strings so a screen can add one without a schema change. */
    val filters: Map<String, String> = emptyMap(),
)

/** The whole persisted session document. One small JSON blob; see [SessionStateStore]. */
@Serializable
data class SessionState(
    val reader: ReaderSession = ReaderSession(),
    val browse: ListViewSession = ListViewSession(),
    val manage: ListViewSession = ListViewSession(),
    val benchmark: ListViewSession = ListViewSession(),
)

/**
 * The one place "what the user was doing" is persisted, so relaunching the app puts them back where
 * they were instead of wiping the screen.
 *
 * Why this exists: NO view model in the app used `SavedStateHandle` or `rememberSaveable`, so any
 * state not explicitly written to a store died on process death - which on a 4 GB phone happens
 * routinely just from backgrounding the app. The reader's text, the audio toggles, and every list's
 * sort/filter were all in that category.
 *
 * Deliberately ONE document rather than a dozen new preference keys: the repo already has thirteen
 * separate classes hand-rolling their own key namespaces over one SharedPreferences file, and adding
 * more of those is how the single-source-of-truth guarantee erodes. This follows the newer
 * [com.phonetts.core.store.FavoritesStore] idiom instead - pure `:core` logic over an injected
 * [DurableStore], loaded once, whole-document rewrite on mutation, fails closed.
 *
 * Fail-closed on read: a corrupt or absent document loads as defaults rather than throwing, and a
 * stale value a screen no longer understands (a sort key that was renamed, a filter that was
 * removed) is the caller's to validate - this store promises storage, never that a stored string is
 * still meaningful.
 */
class SessionStateStore(
    private val store: DurableStore,
    private val json: Json = DEFAULT_JSON,
) {
    private var state: SessionState = load()

    /** The current snapshot. Cheap - held in memory, re-read from disk only at construction. */
    fun current(): SessionState = state

    fun reader(): ReaderSession = state.reader

    fun updateReader(transform: (ReaderSession) -> ReaderSession) {
        persist(state.copy(reader = transform(state.reader)))
    }

    fun browse(): ListViewSession = state.browse

    fun updateBrowse(transform: (ListViewSession) -> ListViewSession) {
        persist(state.copy(browse = transform(state.browse)))
    }

    fun manage(): ListViewSession = state.manage

    fun updateManage(transform: (ListViewSession) -> ListViewSession) {
        persist(state.copy(manage = transform(state.manage)))
    }

    fun benchmark(): ListViewSession = state.benchmark

    fun updateBenchmark(transform: (ListViewSession) -> ListViewSession) {
        persist(state.copy(benchmark = transform(state.benchmark)))
    }

    /** Forget everything (a "reset" action). */
    fun clear() {
        state = SessionState()
        store.delete(DOCUMENT_NAME)
    }

    private fun persist(next: SessionState) {
        state = next
        runCatching { store.write(DOCUMENT_NAME, json.encodeToString(SessionState.serializer(), next)) }
    }

    private fun load(): SessionState {
        val raw = store.read(DOCUMENT_NAME) ?: return SessionState()
        return runCatching { json.decodeFromString(SessionState.serializer(), raw) }.getOrDefault(SessionState())
    }

    companion object {
        const val DOCUMENT_NAME = "session-state"
        val DEFAULT_JSON = Json { ignoreUnknownKeys = true }
    }
}
