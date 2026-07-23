package com.phonetts.app.hf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.hf.DiagnosticsEntry
import com.phonetts.core.download.hf.DiagnosticsKind
import com.phonetts.core.download.hf.HfDownloadProgress
import com.phonetts.core.download.hf.HfEndpoints
import com.phonetts.core.download.hf.HfEngineClassifier
import com.phonetts.core.download.hf.HfFileFormat
import com.phonetts.core.download.hf.HfInstalledFilter
import com.phonetts.core.download.hf.HfLanguages
import com.phonetts.core.download.hf.HfModelSummary
import com.phonetts.core.download.hf.HfSizeEstimate
import com.phonetts.core.download.hf.HfSizeEstimator
import com.phonetts.core.download.hf.HfSizeParamFilter
import com.phonetts.core.download.hf.HfSortOption
import com.phonetts.core.download.hf.HfSupportedFilter
import com.phonetts.core.download.hf.ModelSpeedEstimate
import com.phonetts.core.download.hf.QuantizationFilter
import com.phonetts.core.download.hf.RunCompatibility
import com.phonetts.core.download.hf.needsRtf
import com.phonetts.core.download.hf.needsSize
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToLong

/**
 * Browse Hugging Face text-to-speech models and download one. The whole screen is derived from the
 * catalog + the resolver — it hardcodes no model. A downloaded repo the resolver can't identify
 * still lands in the catalog via the user-pick fallback (spec §6.2), so "browse everything" degrades
 * gracefully instead of failing.
 *
 * Layout: search box at the very top, then sort/filter controls (issue #6, choices derived from the
 * current results), then the live results, then the curated one-tap "Recommended" models — all in
 * one scrolling list. More than one row can download at once (issue #2): each row tracks its own
 * progress instead of the whole screen being locked to a single in-flight download. Errors — search
 * or download — are retained for the session and readable/copyable from the "Errors" button rather
 * than only flashing by (issue #3), and the inline banner itself is dismissible (bug #2) without
 * losing that retained log.
 *
 * Sort/filter recompute (bug #3): [displayedResults]/[availableTags] are `remember`-cached keyed on
 * exactly the state fields they read (`results`/`sort`/`tagFilter`), not recomputed on every
 * recomposition. Without that, any unrelated state change — most commonly a download's
 * bytes/files-progress tick, which can fire many times a second per in-flight download (issue #2
 * allows several at once) — replaces the whole [HfBrowseUiState] and forces `collectAsState` to
 * recompose this screen, re-sorting/re-filtering and re-flattening tags from scratch every time.
 * That alone is wasted work; it got worse because the filter row's callbacks were passed as bare
 * `viewModel::method` references, which Kotlin allocates fresh on every call — an unstable lambda
 * that defeats Compose's skip check, so the dropdown's own composable (and, if it happened to be
 * open, its full item list) re-composed on every one of those ticks too. On the target budget
 * hardware (4 GB RAM, no NPU) that steady drip of small-but-frequent allocations and recompositions
 * is enough GC/main-thread pressure that a tap's input event sits behind a backlog of pending frames
 * instead of being handled promptly — which reads as the dropdown "doing nothing" until a click
 * happens to land in a gap. The fix here is the `remember` scoping below plus `remember`-wrapped
 * stable callbacks — the dropdown's own open/close state was always local and cheap; it only *looked*
 * unresponsive because the main thread was busy with unrelated work.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfBrowseScreen(viewModel: HfBrowseViewModel) {
    val state by viewModel.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showErrorLog by remember { mutableStateOf(false) }
    var showDiagnosticsLog by remember { mutableStateOf(false) }

    // Rate-limit cooldown ticker (issue #103): drives the live countdown and re-enables Search /
    // "Load more" the moment the cooldown lifts, without polling on every frame — the loop only runs
    // while a cooldown is actually active.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.rateLimitedUntilMs) {
        while (System.currentTimeMillis() < state.rateLimitedUntilMs) {
            nowMs = System.currentTimeMillis()
            delay(RATE_LIMIT_TICK_MS)
        }
        nowMs = System.currentTimeMillis()
    }
    val rateLimited = state.isRateLimited(nowMs)
    val cooldownSeconds = ((state.rateLimitedUntilMs - nowMs) / MILLIS_PER_SECOND).coerceAtLeast(0)

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // "Browse models" title with a compact downloads subheader to its right / beneath (issue
        // #106) — progress lives here instead of a tall bar buried below the filters.
        BrowseHeader(downloads = state.downloads, queuedIds = state.queuedDownloadIds)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search Hugging Face TTS models") },
                // So the keyboard's Search/Enter key runs the query — not only the Search button.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
            )
            // Disabled during a 429 cooldown (issue #103) — the same /api/models bucket the list uses.
            Button(onClick = viewModel::search, enabled = !state.loading && !rateLimited) { Text("Search") }
        }
        if (rateLimited) RateLimitNotice(cooldownSeconds)

        // Wrapped in `remember(viewModel)` so the SAME lambda instance is passed on every
        // recomposition (a bare `viewModel::onSortChange` reference allocates a new one each time,
        // which is exactly the unstable-lambda half of the bug #3 root cause described above).
        val callbacks =
            remember(viewModel) {
                FilterCallbacks(
                    onSort = viewModel::onSortChange,
                    onTag = viewModel::onTagFilterChange,
                    onLanguage = viewModel::onLanguageFilterChange,
                    onInstalled = viewModel::onInstalledFilterChange,
                    onSize = viewModel::onSizeFilterChange,
                    onSupported = viewModel::onSupportedFilterChange,
                    onFormat = viewModel::onFormatFilterChange,
                    onEngine = viewModel::onEngineFilterChange,
                    onMinRealtime = viewModel::onMinRealtimeMultipleChange,
                )
            }
        // All keyed on only the fields they actually depend on — NOT recomputed on every
        // recomposition (e.g. a download-progress tick), which was the other half of bug #3.
        val menus =
            FilterMenus(
                tags = remember(state.results) { viewModel.availableTags(state) },
                languages = remember(state.results) { viewModel.availableLanguages(state) },
                formats = remember(state.fileFormats) { viewModel.availableFormats(state) },
                engines = remember(state.results) { viewModel.availableEngines(state) },
            )
        // Compact, collapsible sort + filter controls (issue #106) — only Sort + Language show until
        // the user opens "More filters", so the controls no longer dominate the screen.
        FiltersPanel(state = state, callbacks = callbacks, menus = menus)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            state.error?.let { message ->
                // Issue: a long error message used to grow unbounded and cover the whole screen.
                // Cap it to a few ellipsized lines here; the full text stays readable/copyable in the
                // "Errors"/"Download log" dialogs (which are already height-capped + scrollable).
                Text(
                    "Error: $message",
                    modifier = Modifier.weight(1f),
                    maxLines = ERROR_BANNER_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                // Bug #2: the inline banner is now dismissible — this only hides the banner, the
                // full session log below is untouched (HfBrowseViewModel.dismissError).
                IconButton(onClick = viewModel::dismissError) { Text("✕") }
            }
            if (state.errorLog.isNotEmpty()) {
                TextButton(onClick = { showErrorLog = true }) { Text("Errors (${state.errorLog.size})") }
            }
            // Persistent across app restarts (unlike the two logs above, which are session-only) —
            // see DownloadDiagnosticsLog. Only shown once something has actually been recorded.
            if (state.diagnostics.isNotEmpty()) {
                TextButton(onClick = { showDiagnosticsLog = true }) { Text("Download log (${state.diagnostics.size})") }
            }
        }
        // Resume every download that failed (issue #105) — each continues from its partial file on
        // disk via HTTP Range, so it's "Resume", not "Retry". Also auto-runs on reconnect once the
        // connectivity listener is wired (viewModel.onConnectivityAvailable).
        if (state.failedDownloadIds.isNotEmpty()) {
            Button(onClick = viewModel::resumeFailedDownloads) {
                val plural = if (state.failedDownloadIds.size > 1) "s" else ""
                Text("Resume download$plural (${state.failedDownloadIds.size})")
            }
        }
        if (state.loading) CircularProgressIndicator()

        // Also keyed on sizeEstimates/sizeFilter (unlike the sort/tag-only keys before size/param
        // sort+filter existed) so a size arriving after this composition — or the user setting a
        // size/param bound — actually reorders/refilters the list. Safe perf-wise despite bug #3's
        // "don't recompute on every tick" lesson: sizeEstimates changes at most once per row (each
        // repo's size resolves once and is cached), never the many-times-a-second cadence a
        // download's byte progress produces. Also keyed on installedFilter (issue: installed filter)
        // and downloads.keys (NOT the whole downloads map — its values tick many times a second
        // mid-download, which is exactly the bug #3 perf trap this function's kdoc warns about).
        // Installed-ness is read live off the catalog, not stored in state; a download STARTING or
        // FINISHING is exactly when it can have changed, and that's precisely when the key set
        // changes, so it's a cheap, correct invalidation signal without polling the catalog every
        // recomposition.
        val displayedResults =
            remember(
                state.results,
                state.sort,
                state.tagFilter,
                state.sizeEstimates,
                state.sizeFilter,
                state.installedFilter,
                state.downloads.keys,
                // Advanced filters (issue #107): compatibility/fileFormats arrive per row (once each,
                // like sizeEstimates — not the per-tick cadence bug #3 warns about), and the four
                // filter *choices* change only on user action, so keying on them is cheap and correct.
                advancedFilterKey(state),
            ) {
                viewModel.displayedResults(state)
            }
        // Same rationale as displayedResults above: only recomputed when the query or the fetched
        // voice list itself actually changes, not on every unrelated state tick (e.g. a download's
        // progress). state.piperVoices starts empty and fills in once (see loadPiperVoices()).
        val filteredPiperVoices =
            remember(state.piperVoices, state.piperVoiceQuery) {
                viewModel.filterPiperVoices(state.piperVoices, state.piperVoiceQuery)
            }
        // Collapsible "Recommended" box (issue: collapsible recommended box) — local Compose state,
        // not HfBrowseUiState, since (unlike the Piper voices section) nothing here needs to survive
        // navigating away and back, or trigger a fetch on first expand; the list is already computed
        // up front (viewModel.recommended). Expanded by default so existing behavior is unchanged
        // until the user chooses to collapse it.
        var recommendedExpanded by remember { mutableStateOf(true) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Recommended one-tap models come first — they're the curated, known-good downloads most
            // users want; the broader Hugging Face results follow under their own header.
            if (viewModel.recommended.isNotEmpty()) {
                item(key = "recommended-header") {
                    RecommendedHeader(
                        expanded = recommendedExpanded,
                        count = viewModel.recommended.size,
                        onExpandedChange = { recommendedExpanded = it },
                    )
                }
            }
            if (viewModel.recommended.isNotEmpty() && recommendedExpanded) {
                items(viewModel.recommended, key = { "rec:${it.id}" }) { model ->
                    RecommendedRow(
                        model = model,
                        progress = state.downloads[model.id],
                        queued = model.id in state.queuedDownloadIds,
                        isInstalled = viewModel.isInstalled(model.id),
                        actions =
                            RowActions(
                                onDownload = { viewModel.downloadBuiltIn(model) },
                                onCancel = { viewModel.cancelDownload(model.id) },
                                onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.repoId)) },
                            ),
                    )
                }
            }

            // "Piper voices" (issue #71): every voice rhasspy/piper-voices publishes, fetched at
            // runtime from upstream's own `voices.json` the first time this section is expanded
            // (never a checked-in snapshot — see PiperVoicesIndex/HfBrowseViewModel.loadPiperVoices)
            // and cached in state for the rest of the session. Reuses the exact same
            // downloadBuiltIn() path (and RecommendedRow styling) the "Recommended" grid above uses,
            // no second download path. Collapsed by default; nothing below the header enters the
            // LazyColumn until expanded, so nothing here is eagerly laid out or fetched.
            item(key = "piper-voices-header") {
                PiperVoicesHeader(
                    expanded = state.piperVoicesExpanded,
                    totalCount = state.piperVoices.size,
                    onExpandedChange = viewModel::onPiperVoicesExpandedChange,
                )
            }
            if (state.piperVoicesExpanded) {
                if (state.piperVoicesLoading) {
                    item(key = "piper-voices-loading") {
                        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                            CircularProgressIndicator()
                        }
                    }
                }
                state.piperVoicesError?.let { message ->
                    item(key = "piper-voices-error") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::loadPiperVoices) { Text("Retry") }
                        }
                    }
                }
                if (state.piperVoices.isNotEmpty()) {
                    item(key = "piper-voices-search") {
                        PiperVoiceSearchField(state.piperVoiceQuery, viewModel::onPiperVoiceQueryChange)
                    }
                    items(filteredPiperVoices, key = { "piper:${it.id}" }) { voice ->
                        RecommendedRow(
                            model = voice,
                            progress = state.downloads[voice.id],
                            queued = voice.id in state.queuedDownloadIds,
                            isInstalled = viewModel.isInstalled(voice.id),
                            actions =
                                RowActions(
                                    onDownload = { viewModel.downloadBuiltIn(voice) },
                                    onCancel = { viewModel.cancelDownload(voice.id) },
                                    onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(voice.repoId)) },
                                ),
                        )
                    }
                    if (filteredPiperVoices.isEmpty()) {
                        item(key = "piper-voices-empty") {
                            Text(
                                "No Piper voices match \"${state.piperVoiceQuery}\".",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            if (displayedResults.isNotEmpty()) {
                item(key = "results-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text("More from Hugging Face", fontWeight = FontWeight.Bold)
                        // Honest labeling (issue #104): a size/param/RTF sort only reorders the
                        // most-downloaded prefix loaded so far — HF is always queried by downloads, so
                        // a globally-smallest/fastest model that hasn't been paged in can't appear yet.
                        PrefixSortNotice(
                            sort = state.sort,
                            canLoadMore = state.canLoadMore,
                            loaded = displayedResults.size,
                        )
                    }
                }
            }
            items(displayedResults, key = { "hf:${it.id}" }) { model ->
                ModelRow(
                    model = model,
                    progress = state.downloads[model.id],
                    queued = model.id in state.queuedDownloadIds,
                    isInstalled = viewModel.isInstalled(model.id),
                    compatibility = state.compatibility[model.id],
                    sizeState =
                        SizeState(
                            estimate = state.sizeEstimates[model.id],
                            loading = model.id in state.sizeLoading,
                            onLoad = { viewModel.loadSize(model.id) },
                        ),
                    actions =
                        RowActions(
                            onDownload = { viewModel.download(model) },
                            onCancel = { viewModel.cancelDownload(model.id) },
                            onOpenPage = { uriHandler.openUri(HfEndpoints.modelPageUrl(model.id)) },
                        ),
                )
            }
            // Pagination (issue: Browse "Load more"): appears once the last-fetched page came back
            // full (state.canLoadMore — see HfBrowseViewModel.search/loadMore) and disappears once a
            // short page proves the Hub has nothing further for this query. Its own row so it always
            // sits after every fetched result, never interleaved mid-list.
            if (state.canLoadMore || state.loadingMore) {
                item(key = "load-more") {
                    LoadMoreRow(
                        loading = state.loadingMore,
                        rateLimited = rateLimited,
                        cooldownSeconds = cooldownSeconds,
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }
        }
    }

    state.variantChoice?.let { choice ->
        VariantPicker(
            choice = choice,
            onPick = viewModel::chooseVariant,
            onDismiss = viewModel::cancelVariantChoice,
        )
    }

    if (showErrorLog) {
        ErrorLogDialog(errors = state.errorLog, onDismiss = { showErrorLog = false })
    }

    if (showDiagnosticsLog) {
        DiagnosticsLogDialog(
            entries = state.diagnostics,
            onClear = viewModel::clearDiagnostics,
            onDismiss = { showDiagnosticsLog = false },
        )
    }
}

/** The remembered, stable filter callbacks (bug #3: bare `viewModel::method` refs allocate a fresh
 * lambda each recomposition, defeating Compose's skip check). Bundled so [FiltersPanel] stays under
 * detekt's parameter limit. */
private data class FilterCallbacks(
    val onSort: (HfSortOption) -> Unit,
    val onTag: (String?) -> Unit,
    val onLanguage: (String?) -> Unit,
    val onInstalled: (HfInstalledFilter) -> Unit,
    val onSize: (HfSizeParamFilter) -> Unit,
    val onSupported: (HfSupportedFilter) -> Unit,
    val onFormat: (HfFileFormat?) -> Unit,
    val onEngine: (String?) -> Unit,
    val onMinRealtime: (Double?) -> Unit,
)

/** The filter menus' derived choices (tags/languages/formats/engines) — every one computed from the
 * current results, never a hardcoded list (issue #6/#107). Bundled for the same reason as
 * [FilterCallbacks]. */
private data class FilterMenus(
    val tags: List<String>,
    val languages: List<String>,
    val formats: List<HfFileFormat>,
    val engines: List<String>,
)

/**
 * Compact, collapsible sort + filter controls (issue #106): Sort and Language stay visible; the rest
 * (installed, supported, format, engine, tag, RTF slider, size/params) hide behind "More filters" so
 * the controls no longer dominate the screen. Every choice is derived from the current result set
 * (issue #6/#107) — no hardcoded model list, language, tag, format, or engine vocabulary.
 */
@Composable
private fun FiltersPanel(
    state: HfBrowseUiState,
    callbacks: FilterCallbacks,
    menus: FilterMenus,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownBox(label = "Sort by", value = sortLabel(state.sort), modifier = Modifier.weight(1f)) { dismiss ->
                HfSortOption.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(option)) },
                        onClick = { callbacks.onSort(option); dismiss() },
                    )
                }
            }
            if (menus.languages.isNotEmpty()) {
                LanguageFilterDropdown(state.languageFilter, menus.languages, callbacks.onLanguage, Modifier.weight(1f))
            }
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Fewer filters ▴" else "More filters ▾")
        }
        if (expanded) MoreFilters(state, callbacks, menus)
    }
}

/** The collapsed-away filter controls (issue #106/#107) — kept in its own composable so [FiltersPanel]
 * stays small and the whole set only enters composition when the user opens it. */
@Composable
private fun MoreFilters(
    state: HfBrowseUiState,
    callbacks: FilterCallbacks,
    menus: FilterMenus,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InstalledFilterDropdown(state.installedFilter, callbacks.onInstalled, Modifier.fillMaxWidth())
        SupportedFilterDropdown(state.supportedFilter, callbacks.onSupported, Modifier.fillMaxWidth())
        if (menus.formats.isNotEmpty()) {
            FormatFilterDropdown(state.formatFilter, menus.formats, callbacks.onFormat, Modifier.fillMaxWidth())
        }
        if (menus.engines.isNotEmpty()) {
            EngineFilterDropdown(state.engineFilter, menus.engines, callbacks.onEngine, Modifier.fillMaxWidth())
        }
        if (menus.tags.isNotEmpty()) {
            TagFilterDropdown(state.tagFilter, menus.tags, callbacks.onTag)
        }
        RtfSliderRow(state.minRealtimeMultiple, callbacks.onMinRealtime)
        SizeParamFilterRow(filter = state.sizeFilter, onFilterChange = callbacks.onSize)
    }
}

/** "Supported" filter (issue #107) — keeps only results whose fetched file tree classifies to the
 * chosen [RunCompatibility] (see [com.phonetts.core.download.hf.HfSupportedFilters]). */
@Composable
private fun SupportedFilterDropdown(
    supportedFilter: HfSupportedFilter,
    onSupportedFilterChange: (HfSupportedFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownBox(label = "Runnable", value = supportedFilterLabel(supportedFilter), modifier = modifier) { dismiss ->
        HfSupportedFilter.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(supportedFilterLabel(option)) },
                onClick = { onSupportedFilterChange(option); dismiss() },
            )
        }
    }
}

/** Format/Type filter (issue #107) — choices are the formats actually present across fetched file
 * trees (GGUF/safetensors/ONNX/MLX/CoreML/tflite/NeMo/PyTorch), derived from data. */
@Composable
private fun FormatFilterDropdown(
    formatFilter: HfFileFormat?,
    availableFormats: List<HfFileFormat>,
    onFormatFilterChange: (HfFileFormat?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = formatFilter?.let(::formatLabel) ?: "All formats"
    DropdownBox(label = "Format", value = value, modifier = modifier) { dismiss ->
        DropdownMenuItem(text = { Text("All formats") }, onClick = { onFormatFilterChange(null); dismiss() })
        availableFormats.forEach { format ->
            DropdownMenuItem(
                text = { Text(formatLabel(format)) },
                onClick = { onFormatFilterChange(format); dismiss() },
            )
        }
    }
}

/** Engine-type filter (issue #107) — each registered engine a current result maps to, plus "Other"
 * for unrecognized bundles (see [HfEngineClassifier]); choices come from the live registry, not a
 * hardcoded model list. */
@Composable
private fun EngineFilterDropdown(
    engineFilter: String?,
    availableEngines: List<String>,
    onEngineFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownBox(label = "Engine", value = engineFilter ?: "All engines", modifier = modifier) { dismiss ->
        DropdownMenuItem(text = { Text("All engines") }, onClick = { onEngineFilterChange(null); dismiss() })
        availableEngines.forEach { engine ->
            DropdownMenuItem(text = { Text(engine) }, onClick = { onEngineFilterChange(engine); dismiss() })
        }
    }
}

/** RTF slider (issue #107) — an estimated minimum "faster than real-time" multiple (from the size
 * estimate, labeled as such). 0 clears the filter (null). */
@Composable
private fun RtfSliderRow(
    minMultiple: Double?,
    onMinRealtimeChange: (Double?) -> Unit,
) {
    val label =
        if (minMultiple == null) {
            "Min speed: any"
        } else {
            "Min speed: ~${formatRealtimeMultiple(minMultiple)}x real-time (est.)"
        }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Slider(
            value = (minMultiple ?: 0.0).toFloat().coerceIn(0f, RTF_SLIDER_MAX),
            onValueChange = { onMinRealtimeChange(if (it <= 0f) null else it.toDouble()) },
            valueRange = 0f..RTF_SLIDER_MAX,
        )
    }
}

/** "Installed"/"Not installed"/"All" filter (issue: installed/not-installed filter) — the three
 * choices are fixed since installed-ness is a device fact rather than data derived per-search, but
 * the underlying membership itself is always read live from the local catalog (never cached here). */
@Composable
private fun InstalledFilterDropdown(
    installedFilter: HfInstalledFilter,
    onInstalledFilterChange: (HfInstalledFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownBox(label = "Show", value = installedFilterLabel(installedFilter), modifier = modifier) { dismiss ->
        HfInstalledFilter.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(installedFilterLabel(option)) },
                onClick = { onInstalledFilterChange(option); dismiss() },
            )
        }
    }
}

/** Language filter (issue: many models are multilingual, user mostly wants English) — choices from
 * [HfLanguages.availableLanguages] over the current results, shown by their friendly name. */
@Composable
private fun LanguageFilterDropdown(
    languageFilter: String?,
    availableLanguages: List<String>,
    onLanguageFilterChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = languageFilter?.let { HfLanguages.displayName(it) } ?: "All languages"
    DropdownBox(label = "Language", value = value, modifier = modifier) { dismiss ->
        DropdownMenuItem(text = { Text("All languages") }, onClick = { onLanguageFilterChange(null); dismiss() })
        availableLanguages.forEach { code ->
            DropdownMenuItem(
                text = { Text(HfLanguages.displayName(code)) },
                onClick = { onLanguageFilterChange(code); dismiss() },
            )
        }
    }
}

/** Tag filter — the trimmed, frequency-ranked tag set (issue: tag filter slow); see
 * [HfResultsView.frequentTags]. Full width since it sits on its own row. */
@Composable
private fun TagFilterDropdown(
    tagFilter: String?,
    availableTags: List<String>,
    onTagFilterChange: (String?) -> Unit,
) {
    DropdownBox(label = "Filter by tag", value = tagFilter ?: "All tags", modifier = Modifier.fillMaxWidth()) { dismiss ->
        DropdownMenuItem(text = { Text("All tags") }, onClick = { onTagFilterChange(null); dismiss() })
        availableTags.forEach { tag ->
            DropdownMenuItem(text = { Text(tag) }, onClick = { onTagFilterChange(tag); dismiss() })
        }
    }
}

/** Shared ExposedDropdownMenuBox scaffold: a read-only anchor field plus a menu whose items are
 * supplied by [menuContent], which is handed a `dismiss` callback to close the menu after a pick.
 * Factored out so the sort/language/tag dropdowns don't each repeat the same boilerplate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    menuContent: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        TextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuContent { expanded = false }
        }
    }
}

private fun sortLabel(option: HfSortOption): String =
    when (option) {
        HfSortOption.RELEVANCE -> "Relevance"
        HfSortOption.MOST_DOWNLOADS -> "Most downloads"
        HfSortOption.MOST_LIKES -> "Most likes"
        HfSortOption.NAME_ASC -> "Name (A-Z)"
        HfSortOption.LARGEST_SIZE -> "Largest size"
        HfSortOption.SMALLEST_SIZE -> "Smallest size"
        HfSortOption.MOST_PARAMS -> "Most params (est.)"
        HfSortOption.FEWEST_PARAMS -> "Fewest params (est.)"
        HfSortOption.FASTEST_RTF -> "Fastest (measured)"
        HfSortOption.SLOWEST_RTF -> "Slowest (measured)"
    }

/** "Installed" filter menu choices (issue: installed/not-installed filter) — every value is a
 * genuine [HfInstalledFilter], not a hardcoded model list. */
private fun installedFilterLabel(filter: HfInstalledFilter): String =
    when (filter) {
        HfInstalledFilter.ALL -> "All models"
        HfInstalledFilter.INSTALLED_ONLY -> "Installed only"
        HfInstalledFilter.NOT_INSTALLED_ONLY -> "Not installed"
    }

/** "Runnable" filter menu labels (issue #107) — every value is a genuine [HfSupportedFilter], never
 * a hardcoded model list. Wording matches the honest badge (issue #108): "Runs now" vs "needs
 * conversion" vs "can't run here". */
private fun supportedFilterLabel(filter: HfSupportedFilter): String =
    when (filter) {
        HfSupportedFilter.ALL -> "Any"
        HfSupportedFilter.RUNNABLE -> "Runs on this app"
        HfSupportedFilter.NEEDS_CONVERSION -> "Needs conversion"
        HfSupportedFilter.IMPOSSIBLE -> "Can't run here"
    }

/** Format menu labels (issue #107) — each is a genuine [HfFileFormat] the file tree revealed. */
private fun formatLabel(format: HfFileFormat): String =
    when (format) {
        HfFileFormat.ONNX -> "ONNX"
        HfFileFormat.GGUF -> "GGUF"
        HfFileFormat.SAFETENSORS -> "safetensors"
        HfFileFormat.PYTORCH -> "PyTorch"
        HfFileFormat.TFLITE -> "tflite"
        HfFileFormat.NEMO -> "NeMo"
        HfFileFormat.MLX -> "MLX"
        HfFileFormat.COREML -> "CoreML"
    }

/**
 * Numeric min/max bounds for the size and estimated-parameter-count filter (issue: sort+filter by
 * size/params — [HfSizeParamFilter]). Collapsed by default so the common case (no filter) doesn't
 * add four text fields to the screen; expands once tapped, or if a filter from a previous session
 * is already active. Values are entered in the same human units the rest of this screen already
 * shows (MB, M params) and converted to raw bytes/count only on Apply — see [mbTextToBytes]/
 * [mTextToParams] — so a blank field means "no bound", never a fabricated zero.
 */
@Composable
private fun SizeParamFilterRow(
    filter: HfSizeParamFilter,
    onFilterChange: (HfSizeParamFilter) -> Unit,
) {
    var expanded by remember { mutableStateOf(filter.isActive) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (filter.isActive) "Size / params filter (active) ▾" else "Size / params filter ▾")
        }
        if (!expanded) return@Column
        var minSizeMb by remember { mutableStateOf(bytesToMbText(filter.minBytes)) }
        var maxSizeMb by remember { mutableStateOf(bytesToMbText(filter.maxBytes)) }
        var minParamsM by remember { mutableStateOf(paramsToMText(filter.minParams)) }
        var maxParamsM by remember { mutableStateOf(paramsToMText(filter.maxParams)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min size (MB)", minSizeMb, { minSizeMb = it }, Modifier.weight(1f))
            NumberField("Max size (MB)", maxSizeMb, { maxSizeMb = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Min params (M)", minParamsM, { minParamsM = it }, Modifier.weight(1f))
            NumberField("Max params (M)", maxParamsM, { maxParamsM = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                onFilterChange(
                    HfSizeParamFilter(
                        minBytes = mbTextToBytes(minSizeMb),
                        maxBytes = mbTextToBytes(maxSizeMb),
                        minParams = mTextToParams(minParamsM),
                        maxParams = mTextToParams(maxParamsM),
                    ),
                )
            }) { Text("Apply") }
            OutlinedButton(onClick = {
                minSizeMb = ""
                maxSizeMb = ""
                minParamsM = ""
                maxParamsM = ""
                onFilterChange(HfSizeParamFilter())
            }) { Text("Clear") }
        }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/**
 * Collapsible header for the "Recommended (one-tap)" box (issue: collapsible recommended box) — lets
 * the curated models be hidden entirely rather than always taking the top of the screen. Mirrors
 * [PiperVoicesHeader]'s ▾/▸ affordance for a consistent expand/collapse pattern across the screen.
 */
@Composable
private fun RecommendedHeader(
    expanded: Boolean,
    count: Int,
    onExpandedChange: (Boolean) -> Unit,
) {
    TextButton(onClick = { onExpandedChange(!expanded) }) {
        Text(
            if (expanded) "Recommended (one-tap) — $count ▾" else "Recommended (one-tap) — $count ▸",
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Collapsible header for the "Piper voices" section (issue #71): 166+ voices, fetched at runtime
 * (see [HfBrowseViewModel.loadPiperVoices]), is too many to dump into the list unfiltered, so this
 * starts collapsed (`expanded = false`) and nothing else in the section — not the search field,
 * not a single row — enters the LazyColumn until the user taps it open. [totalCount] is 0 before
 * the first fetch completes (or on a failed one), so the count is only shown once it's real.
 */
@Composable
private fun PiperVoicesHeader(
    expanded: Boolean,
    totalCount: Int,
    onExpandedChange: (Boolean) -> Unit,
) {
    val label = if (totalCount > 0) "Piper voices ($totalCount)" else "Piper voices"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        TextButton(onClick = { onExpandedChange(!expanded) }) {
            Text(if (expanded) "$label ▾" else "$label ▸")
        }
    }
}

/** Filters the expanded Piper section by voice name or language (issue #71) — see
 * [HfBrowseViewModel.filterPiperVoices]; the display name already carries the language, so one
 * field covers both. */
@Composable
private fun PiperVoiceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        label = { Text("Filter by voice name or language") },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Pagination's "fetch the next page" control (issue: Browse "Load more") — a button while idle, a
 * spinner while [loading] a page is in flight, centered so it reads as a list footer rather than
 * another result row. */
@Composable
private fun LoadMoreRow(
    loading: Boolean,
    rateLimited: Boolean,
    cooldownSeconds: Long,
    onLoadMore: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator()
            return@Row
        }
        // During a 429 cooldown (issue #103) the button is disabled and shows the countdown — the
        // view model auto-retries when it lifts, so this never needs a tap to resume.
        if (rateLimited) {
            OutlinedButton(onClick = onLoadMore, enabled = false) {
                Text("Rate-limited by Hugging Face — retrying in ${cooldownSeconds}s…")
            }
            return@Row
        }
        OutlinedButton(onClick = onLoadMore) { Text("Load more") }
    }
}

/** The advanced-filter fields [HfBrowseViewModel.displayedResults] reads (issue #107), bundled into
 * one stable key so the results view recomputes when any of them changes but NOT on a download tick.
 * A List compares by content, so compatibility/format maps re-trigger only when their contents move. */
private fun advancedFilterKey(state: HfBrowseUiState): List<Any?> =
    listOf(
        state.compatibility,
        state.fileFormats,
        state.supportedFilter,
        state.formatFilter,
        state.minRealtimeMultiple,
        state.engineFilter,
    )

private fun bytesToMbText(bytes: Long?): String = bytes?.let { "%.0f".format(it / BYTES_PER_MB.toDouble()) } ?: ""

private fun paramsToMText(params: Long?): String = params?.let { "%.0f".format(it / PARAMS_PER_M) } ?: ""

private fun mbTextToBytes(text: String): Long? =
    text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * BYTES_PER_MB).roundToLong() }

private fun mTextToParams(text: String): Long? =
    text.trim().toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * PARAMS_PER_M).roundToLong() }

/** Every retained browse/download error this session (issue #3): selectable text plus a "Copy all"
 * button so the user can paste a full report rather than only screenshotting a toast. */
@Composable
private fun ErrorLogDialog(
    errors: List<HfBrowseError>,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val fullText = remember(errors) { errors.joinToString("\n\n") { formatErrorLine(it, formatter) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
        },
        title = { Text("Recent errors") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    errors.forEach { error ->
                        Text(formatErrorLine(error, formatter), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

private fun formatErrorLine(
    error: HfBrowseError,
    formatter: DateFormat,
): String {
    val time = formatter.format(Date(error.atMs))
    val prefix = error.modelId?.let { "$it — " } ?: ""
    // Repeat-collapsed entries (issue #103) show "(xN)" so the reader sees how often it recurred.
    val repeat = if (error.count > 1) " (x${error.count})" else ""
    return "[$time] $prefix${error.message}$repeat"
}

/** Persistent (survives an app restart — see [DownloadDiagnosticsLog]) record of Browse download
 * failures and "downloaded, but no engine claims it yet" imports, so the user can track which
 * engines are worth adding next — not just a session-only toast. */
@Composable
private fun DiagnosticsLogDialog(
    entries: List<DiagnosticsEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val formatter = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val fullText = remember(entries) { entries.joinToString("\n\n") { formatDiagnosticsLine(it, formatter) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }
        },
        title = { Text("Download diagnostics") },
        text = { DiagnosticsLogBody(entries, formatter) },
    )
}

@Composable
private fun DiagnosticsLogBody(
    entries: List<DiagnosticsEntry>,
    formatter: DateFormat,
) {
    if (entries.isEmpty()) {
        Text("No download issues recorded yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    SelectionContainer {
        Column(
            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            entries.forEach { entry ->
                Text(formatDiagnosticsLine(entry, formatter), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun formatDiagnosticsLine(
    entry: DiagnosticsEntry,
    formatter: DateFormat,
): String {
    val time = formatter.format(Date(entry.atMs))
    val kind = if (entry.kind == DiagnosticsKind.FAILURE) "FAILED" else "downloaded, no engine yet"
    return "[$time] ${entry.modelId} — $kind: ${entry.detail}"
}

/** Lets the user pick which weight precision to download when a repo ships more than one KNOWN
 * precision (issue #9 — an ambiguous, unlabeled auxiliary weight file never reaches this picker;
 * see [com.phonetts.core.download.hf.QuantizationFilter.requiresChoice]). Each option shows its own
 * download size where the repo's file tree reports one (issue #7). */
@Composable
private fun VariantPicker(
    choice: VariantChoice,
    onPick: (com.phonetts.core.download.hf.QuantizationVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Choose precision") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${choice.modelId} offers several precisions. Smaller = faster/less RAM.")
                choice.variants.forEach { variant ->
                    val estimate = HfSizeEstimator.estimate(QuantizationFilter.filesForVariant(choice.files, variant))
                    Button(onClick = { onPick(variant) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${variant.name} — ${formatSize(estimate)}")
                    }
                }
            }
        },
    )
}

/** A row's Download/Cancel/"View on Hugging Face" callbacks, bundled into one parameter so
 * [RecommendedRow]/[ModelRow] stay under detekt's LongParameterList limit. */
private data class RowActions(
    val onDownload: () -> Unit,
    val onCancel: () -> Unit,
    val onOpenPage: () -> Unit,
)

/** A result row's on-demand size lookup (issue #7): the estimate once fetched, whether a fetch is
 * in flight, and how to trigger one — bundled into one parameter for the same reason as [RowActions].
 * The fetch is now triggered automatically (bug #4 — no more "Show download size" tap gate); [onLoad]
 * is idempotent (see [HfBrowseViewModel.loadSize]) so calling it once per row composition is safe. */
private data class SizeState(
    val estimate: HfSizeEstimate?,
    val loading: Boolean,
    val onLoad: () -> Unit,
)

@Composable
private fun RecommendedRow(
    model: com.phonetts.core.download.builtin.BuiltInModel,
    progress: HfDownloadProgress?,
    queued: Boolean,
    isInstalled: Boolean,
    actions: RowActions,
) {
    ModelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.displayName, fontWeight = FontWeight.Bold)
                Text("~${model.approxSizeMb} MB", style = MaterialTheme.typography.bodyMedium)
                model.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                // Bug #5: a curated model's approximate size is already known up front (no network
                // round trip needed), so its param/speed hint can be derived immediately too.
                val fileNames = remember(model.id) { model.files.map { it.localName } }
                SpeedHintLine(totalBytes = model.approxSizeMb.toLong() * BYTES_PER_MB, precisionHints = fileNames)
            }
            DownloadControl(progress != null, isInstalled, actions.onDownload, actions.onCancel)
        }
        DownloadProgress(progress, queued)
        OpenPageLink(actions.onOpenPage)
    }
}

@Composable
private fun ModelRow(
    model: HfModelSummary,
    progress: HfDownloadProgress?,
    queued: Boolean,
    isInstalled: Boolean,
    compatibility: RunCompatibility?,
    sizeState: SizeState,
    actions: RowActions,
) {
    // Bug #4: the download size is no longer gated behind a "Show download size" tap — fetch it as
    // soon as this row is shown. Runs once per model id (LaunchedEffect restarts only when the key
    // changes) and loadSize() is a no-op if a fetch already ran/is running for this id. The same
    // file-tree fetch also determines the compatibility badge (see HfBrowseViewModel.loadSize).
    LaunchedEffect(model.id) { sizeState.onLoad() }
    ModelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.id, fontWeight = FontWeight.Bold)
                Text("▼ ${model.downloads}   ♥ ${model.likes}", style = MaterialTheme.typography.bodyMedium)
                // pipelineTag + tags come straight from the HF API response — no extra network call,
                // just fields the parser already kept that the UI wasn't showing.
                val tags = listOfNotNull(model.pipelineTag) + model.tags.take(MAX_TAGS_SHOWN)
                val subtitle = tags.joinToString(" · ")
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall)
                CompatibilityBadge(compatibility)
                SizeLine(sizeState)
                SpeedHintLine(totalBytes = sizeState.estimate?.knownBytes, precisionHints = model.tags)
            }
            DownloadControl(progress != null, isInstalled, actions.onDownload, actions.onCancel)
        }
        DownloadProgress(progress, queued)
        OpenPageLink(actions.onOpenPage)
    }
}

/**
 * An honest, file-tree-derived runnability label (issue #108) — greyed out, and never disables the
 * Download button (the user may still want the weights on disk). Distinguishes the three
 * [RunCompatibility] outcomes so the wording NEVER implies "coming soon" for a format that can't run
 * here at all:
 *  - [RunCompatibility.RUNNABLE] (or not-yet-checked null): no badge — it runs.
 *  - [RunCompatibility.NEEDS_CONVERSION]: convertible offline, not "coming soon" inside the app.
 *  - [RunCompatibility.IMPOSSIBLE]: an Apple-only format (MLX/CoreML) — states plainly it can't run
 *    on Android, no "yet".
 */
@Composable
private fun CompatibilityBadge(compatibility: RunCompatibility?) {
    val text =
        when (compatibility) {
            RunCompatibility.NEEDS_CONVERSION -> "Needs conversion to ONNX/GGUF before it can run here"
            RunCompatibility.IMPOSSIBLE -> "Can't run on Android — Apple-only format (MLX/CoreML)"
            else -> return
        }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = NOT_SUPPORTED_ALPHA),
    )
}

/** Issue #7 + bug #4: the repo's download size isn't known until its file tree is fetched, so this
 * shows "Checking size…" briefly (the fetch now starts automatically — see [ModelRow]'s
 * `LaunchedEffect` — never behind a tap) and then the computed (possibly partial) total inline,
 * with no gating button. */
@Composable
private fun SizeLine(sizeState: SizeState) {
    val estimate = sizeState.estimate
    if (estimate != null) {
        val suffix = if (estimate.isExact) "" else " (partial — some file sizes unknown)"
        Text("Download size: ${formatSize(estimate)}$suffix", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (sizeState.loading) {
        Text("Checking size…", style = MaterialTheme.typography.bodySmall)
        return
    }
    // Neither loading nor known: the automatic fetch hasn't started yet this frame, or it failed
    // (see HfBrowseViewModel.loadSize's onFailure — a network hiccup just clears the loading flag,
    // never fabricates a number). Either way, no size to show and no button to gate it behind.
    Text("Download size unavailable", style = MaterialTheme.typography.bodySmall)
}

/**
 * Bug #5: a parameter-count + predicted-speed hint, derived purely from a total byte size (already
 * on hand once the download size is known — no extra network call) via the pure [ModelSpeedEstimate]
 * formula in `:core`. Both numbers are estimates and are labeled as such — the formula runs
 * identically for every model, curated or browsed, with no per-model fact hardcoded here (spec
 * rule 1). Renders nothing until [totalBytes] is known, since there's nothing honest to estimate
 * from yet.
 */
@Composable
private fun SpeedHintLine(
    totalBytes: Long?,
    precisionHints: List<String>,
) {
    if (totalBytes == null || totalBytes <= 0L) return
    val speed = remember(totalBytes, precisionHints) { ModelSpeedEstimate.from(totalBytes, precisionHints) }
    // The leading "~" already signals these are estimates (issue #106 removed the "(both estimated)"
    // suffix that only wasted a line).
    Text(
        "~${formatParamCount(speed.paramCount)} params · " +
            "~${formatRealtimeMultiple(speed.realtimeMultiple)}x real-time",
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Opens the model's Hugging Face page (its README / model card / files) in the browser. */
@Composable
private fun OpenPageLink(onOpenPage: () -> Unit) {
    TextButton(onClick = onOpenPage) { Text("View on Hugging Face ↗") }
}

/** A model card's Delete/Download row is followed by a progress bar when a download is in flight.
 * Shows bytes/ETA once a total size and a measured throughput are both known (issue #7) — never a
 * fabricated rate; falls back to the file-count text otherwise. */
@Composable
private fun DownloadProgress(
    progress: HfDownloadProgress?,
    queued: Boolean,
) {
    if (progress == null) return
    // Queued behind the concurrency cap (issue #101): tracked and cancellable, but not transferring
    // yet — show "Queued" instead of a 0% bar so the user sees it's waiting, not stuck.
    if (queued) {
        Text("Queued…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val now = System.currentTimeMillis()
    val bytesTotal = progress.bytesTotal
    // Progress is file-count based when the plan's byte total isn't known (a repo file the tree
    // omitted a size for) — most of these repos are 1-2 large weight files, so a single-file repo
    // with no size would sit at an uninformative 0% the whole time; show it as indeterminate instead.
    if (bytesTotal == null) {
        if (progress.filesTotal <= 1) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            LinearProgressIndicator(
                progress = { progress.filesDone.toFloat() / progress.filesTotal },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        return
    }
    Column(modifier = Modifier.padding(top = 8.dp)) {
        LinearProgressIndicator(
            progress = { (progress.bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        val etaText = progress.etaSeconds(now)?.let { " · ≈${formatDuration(it)} left" } ?: ""
        Text(
            "${formatBytes(progress.bytesDone)} / ${formatBytes(bytesTotal)}$etaText",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * The compact downloads subheader (issue #106) placed directly under the app bar's "Browse models"
 * title (that title is rendered by the surrounding BackScaffold, so it is not repeated here): a
 * "Downloading X models" summary line with the combined bar + "X/X GB, XX% done, X:XX left" stats
 * beneath — so download progress is glanceable up top instead of a tall bar buried below the
 * filters. Renders nothing when nothing is downloading.
 */
@Composable
private fun BrowseHeader(
    downloads: Map<String, HfDownloadProgress>,
    queuedIds: Set<String>,
) {
    if (downloads.isEmpty()) return
    val queued = downloads.keys.count { it in queuedIds }
    val suffix = if (queued > 0) " ($queued queued)" else ""
    val plural = if (downloads.size > 1) "s" else ""
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Downloading ${downloads.size} model$plural$suffix", style = MaterialTheme.typography.bodyMedium)
        DownloadsSubheader(downloads)
    }
}

/**
 * The combined download bar + stats line (issue #106): "X/X GB, XX% done, X:XX left" above a single
 * bar summing bytes across every in-flight download — including a total-download ETA from the
 * measured aggregate throughput (never a fabricated rate). Indeterminate the moment any one
 * download's total isn't known yet, rather than a fabricated percent. Renders nothing when idle.
 */
@Composable
private fun DownloadsSubheader(downloads: Map<String, HfDownloadProgress>) {
    if (downloads.isEmpty()) return
    val now = System.currentTimeMillis()
    val progresses = downloads.values.toList()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (progresses.any { it.bytesTotal == null }) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            return@Column
        }
        val done = progresses.sumOf { it.bytesDone }
        val total = progresses.sumOf { it.bytesTotal ?: 0L }
        val percent = if (total > 0L) ((done.toDouble() / total) * PERCENT_MAX).toInt().coerceIn(0, PERCENT_MAX) else 0
        val eta = aggregateEtaText(progresses, done, total, now)
        Text(
            "${formatBytes(done)} / ${formatBytes(total)} · $percent% done$eta",
            style = MaterialTheme.typography.bodySmall,
        )
        val fraction = if (total > 0L) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
}

// Total-download ETA (issue #106): remaining bytes across every download divided by the summed
// MEASURED throughput. Empty when no download has a trustworthy rate yet (queued/just-started ones
// contribute no rate) — never a fabricated number.
private fun aggregateEtaText(
    progresses: List<HfDownloadProgress>,
    done: Long,
    total: Long,
    now: Long,
): String {
    val rate = progresses.mapNotNull { it.bytesPerSecond(now) }.sum()
    val remaining = (total - done).coerceAtLeast(0L)
    if (rate <= 0.0 || remaining <= 0L) return ""
    return " · ${formatClock(remaining / rate)} left"
}

/** The 429 cooldown notice under the search box (issue #103) — a plain caption, NOT the red error
 * banner: an expected, self-resolving wait the app auto-retries, not a user-actionable failure. */
@Composable
private fun RateLimitNotice(cooldownSeconds: Long) {
    Text(
        "Rate-limited by Hugging Face — retrying in ${cooldownSeconds}s…",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = NOT_SUPPORTED_ALPHA),
    )
}

/** Honest labeling for a prefix-only sort (issue #104): a size/param/RTF sort only reorders the
 * most-downloaded prefix already loaded, since HF is always queried by downloads and has no
 * size/RTF sort to delegate to. Shown only while that sort is active AND more pages remain. */
@Composable
private fun PrefixSortNotice(
    sort: HfSortOption,
    canLoadMore: Boolean,
    loaded: Int,
) {
    if (!canLoadMore || !(sort.needsSize() || sort.needsRtf())) return
    Text(
        "Sorting the $loaded most-downloaded loaded so far — load more to include others.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = NOT_SUPPORTED_ALPHA),
    )
}

/** Groups a model's info + controls into a visually distinct card instead of a bare list row. */
@Composable
private fun ModelCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

@Composable
private fun DownloadControl(
    isDownloading: Boolean,
    isInstalled: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    if (isInstalled) {
        Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        return
    }
    if (isDownloading) {
        // Cancel stops the fetch and leaves any partial file on disk, so re-tapping Download resumes.
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
        return
    }
    Button(onClick = onDownload) { Text("Download") }
}

private fun formatSize(estimate: HfSizeEstimate): String {
    val prefix = if (estimate.isExact) "" else "≥"
    return "$prefix${formatBytes(estimate.knownBytes)}"
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= MB_TO_GB_THRESHOLD) return "%.2f GB".format(mb / 1024.0)
    return "%.1f MB".format(mb)
}

private fun formatDuration(seconds: Double): String {
    val totalSeconds = seconds.roundToLong().coerceAtLeast(0L)
    if (totalSeconds < SECONDS_PER_MINUTE) return "${totalSeconds}s"
    val minutes = totalSeconds / SECONDS_PER_MINUTE
    if (minutes < MINUTES_PER_HOUR) return "${minutes}m"
    val hours = minutes / MINUTES_PER_HOUR
    return "${hours}h ${minutes % MINUTES_PER_HOUR}m"
}

/** The aggregate download ETA (issue #106) as a clock — "M:SS" under an hour, "H:MM:SS" above — so
 * "X:XX left" reads the way a media/download timer does. */
private fun formatClock(seconds: Double): String {
    val total = seconds.roundToLong().coerceAtLeast(0L)
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val secs = total % SECONDS_PER_MINUTE
    if (hours > 0L) return "%d:%02d:%02d".format(hours, minutes, secs)
    return "%d:%02d".format(minutes, secs)
}

/** Bug #5 display helper: an estimated parameter count as a compact "82M"/"1.2B" label. */
private fun formatParamCount(count: Long): String {
    if (count <= 0L) return "?"
    val millions = count / 1_000_000.0
    if (millions >= THOUSAND) return "%.1fB".format(millions / THOUSAND)
    if (millions >= 1.0) return "%.0fM".format(millions)
    val thousands = count / 1_000.0
    if (thousands >= 1.0) return "%.0fK".format(thousands)
    return count.toString()
}

/** Bug #5 display helper: one decimal place is plenty of precision for a heuristic estimate. */
private fun formatRealtimeMultiple(multiple: Double): String = "%.1f".format(multiple)

private const val MAX_TAGS_SHOWN = 4
private const val ERROR_BANNER_MAX_LINES = 3
private const val BYTES_PER_MB = 1024L * 1024L
private const val PARAMS_PER_M = 1_000_000.0
private const val THOUSAND = 1000.0
private const val MB_TO_GB_THRESHOLD = 1024.0
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val NOT_SUPPORTED_ALPHA = 0.5f
private const val PERCENT_MAX = 100
private const val RTF_SLIDER_MAX = 12f
private const val RATE_LIMIT_TICK_MS = 500L
private const val MILLIS_PER_SECOND = 1000L
