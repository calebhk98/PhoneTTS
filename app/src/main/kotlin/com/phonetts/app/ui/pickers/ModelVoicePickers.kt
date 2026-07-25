package com.phonetts.app.ui.pickers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.phonetts.core.engine.Voice
import com.phonetts.core.model.ModelDescriptor
import kotlinx.coroutines.launch

/**
 * The model + voice dropdown pickers - previously written out three separate times (the Voice card
 * on the main screen, Mix-voices, and A/B Compare), each drifting from the others (CLAUDE.md rule 1:
 * a duplicated concept is a bug). This is the one shared body; every caller renders from the SAME
 * dropdown scaffolding and opts into only the extras it needs:
 *
 * - The main Voice card wants the richest form: two-line rows (name + a summary line), a supporting
 *   line under the field for the current selection, and a per-voice favorite star. Pass [summary] /
 *   [twoLine] + [favoriteVoiceIds]/[onToggleFavorite] for that.
 * - Mix-voices and Compare want a bare single-line dropdown - they simply don't pass those params
 *   and get exactly the plain list they always had. Compare additionally wants each voice row to show
 *   its language, via [voiceLabel].
 *
 * Nothing here changes behavior for any existing caller - it is the same three bodies unified, not a
 * redesign (no bottom sheets, no restyling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPicker(
    models: List<ModelDescriptor>,
    selected: ModelDescriptor?,
    onSelect: (ModelDescriptor) -> Unit,
    modifier: Modifier = Modifier,
    /** True (the main Voice card): an unlabeled OutlinedTextField. False (Mix/Compare): a labeled TextField. */
    outlined: Boolean = true,
    /** Field label, only rendered when [outlined] is false (Mix/Compare's "Model" label). */
    label: String? = null,
    /** Text shown when nothing is selected yet. */
    placeholder: String = "Select a model",
    /**
     * Non-null enables the richer two-line row (bold model name over this summary) plus a supporting
     * line under the field and a "selected" check icon in the list. Null (Mix/Compare) keeps the
     * original bare single-line row with no extra chrome.
     */
    summary: ((ModelDescriptor) -> String)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        val fieldModifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        if (outlined) {
            OutlinedTextField(
                value = selected?.displayName ?: placeholder,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                supportingText = summary?.let { s -> selected?.let { { Text(s(it)) } } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = fieldModifier,
            )
        } else {
            TextField(
                value = selected?.displayName ?: placeholder,
                onValueChange = {},
                readOnly = true,
                label = label?.let { { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = fieldModifier,
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            models.forEach { model ->
                val isSelected = model.modelId == selected?.modelId
                DropdownMenuItem(
                    text = {
                        if (summary != null) {
                            TwoLineItem(model.displayName, summary(model), isSelected)
                        } else {
                            Text(model.displayName)
                        }
                    },
                    trailingIcon = if (summary != null) { { SelectedCheck(isSelected) } } else null,
                    onClick = {
                        onSelect(model)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * Voice picker counterpart to [ModelPicker]. Voices always come from [voices] (a descriptor's own
 * list - SSOT); [voiceLabel]'s meaning depends on [twoLine]:
 *  - [twoLine] true (main Voice card): [voiceLabel] is the SECOND line under the bold voice name
 *    (that screen passes the voice's language).
 *  - [twoLine] false (Mix/Compare): [voiceLabel] is the WHOLE row text (Mix defaults to just the
 *    name; Compare passes "name (language)").
 *
 * Favorited voices ([favoriteVoiceIds], derived from the single FavoritesStore source of truth) sort
 * first and show a filled star when both [favoriteVoiceIds] and [onToggleFavorite] are supplied; the
 * star toggle never changes the voice list itself, only its order. Callers that don't pass them
 * (Mix/Compare) get no favorites UI at all, same as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePicker(
    voices: List<Voice>,
    selectedVoiceId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    label: String? = null,
    placeholder: String = "Select a voice",
    twoLine: Boolean = false,
    voiceLabel: (Voice) -> String = { it.name },
    favoriteVoiceIds: Set<String>? = null,
    onToggleFavorite: ((Voice) -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.id == selectedVoiceId }
    val selectedName = selected?.name ?: placeholder
    val ordered = if (favoriteVoiceIds != null) voices.sortedByDescending { it.id in favoriteVoiceIds } else voices
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        val fieldModifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        if (outlined) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                supportingText = if (twoLine) selected?.let { { Text(voiceLabel(it)) } } else null,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = fieldModifier,
            )
        } else {
            TextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                label = label?.let { { Text(it) } },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = fieldModifier,
            )
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ordered.forEach { voice ->
                val isSelected = voice.id == selectedVoiceId
                DropdownMenuItem(
                    text = {
                        if (twoLine) {
                            TwoLineItem(voice.name, voiceLabel(voice), isSelected)
                        } else {
                            Text(voiceLabel(voice))
                        }
                    },
                    trailingIcon = voiceTrailingIcon(favoriteVoiceIds, onToggleFavorite, twoLine, voice, isSelected),
                    onClick = {
                        onSelect(voice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

// The optional trailing icon(s) for one voice row: a favorite star (when the caller wired favorites
// in) alongside a "selected" check (in two-line/richer mode), or nothing at all for the bare Mix/
// Compare rows - same three behaviors the old separate bodies had, just expressed as one guard chain.
@Composable
private fun voiceTrailingIcon(
    favoriteVoiceIds: Set<String>?,
    onToggleFavorite: ((Voice) -> Unit)?,
    twoLine: Boolean,
    voice: Voice,
    isSelected: Boolean,
): (@Composable () -> Unit)? {
    if (favoriteVoiceIds != null && onToggleFavorite != null) {
        return {
            Row(
                horizontalArrangement = Arrangement.spacedBy(FAVORITE_ICON_SPACING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteStar(voice.id in favoriteVoiceIds) { onToggleFavorite(voice) }
                SelectedCheck(isSelected)
            }
        }
    }
    if (twoLine) return { SelectedCheck(isSelected) }
    return null
}

/** A dropdown row rendered as a bold primary line over a muted supporting line. */
@Composable
private fun TwoLineItem(
    primary: String,
    secondary: String,
    selected: Boolean,
) {
    Column {
        Text(
            primary,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            secondary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A check icon shown only on the currently-selected dropdown row, so the pick is obvious in a long list. */
@Composable
private fun SelectedCheck(selected: Boolean) {
    if (!selected) return
    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
}

@Composable
private fun FavoriteStar(
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(FAVORITE_STAR_SIZE)) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val FAVORITE_STAR_SIZE = 32.dp
private val FAVORITE_ICON_SPACING = 12.dp

/**
 * The single "what voice is speaking" control for the Home screen (issue: three stacked widgets -
 * a model dropdown, a voice dropdown, and a separate favorites chip row - for one decision). This
 * collapses all three into one tappable summary row ("Aria · Kokoro") that opens a
 * [ModalBottomSheet]: a search field, favorited voices pinned in their own section at the top, then
 * every voice grouped by model. Picking any row - favorite or not - both selects the voice and
 * switches the active model when it differs, via [onPick]; the row's star toggles that voice's
 * favorite status via [onToggleFavorite], independent of picking it.
 *
 * Deliberately takes only `:core` types ([ModelDescriptor], [Voice]) plus primitives, matching the
 * rest of this file's layering - it knows nothing about `TtsViewModel`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedVoicePicker(
    models: List<ModelDescriptor>,
    selectedModelId: String?,
    selectedVoiceId: String?,
    favoritePairs: Set<Pair<String, String>>,
    onPick: (modelId: String, voiceId: String) -> Unit,
    onToggleFavorite: (modelId: String, voice: Voice) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val selectedModel = models.firstOrNull { it.modelId == selectedModelId }
    val selectedVoice = selectedModel?.voices?.firstOrNull { it.id == selectedVoiceId }
    ListItem(
        headlineContent = { Text(selectedVoice?.name ?: "Select a voice") },
        supportingContent =
            selectedModel?.let { model ->
                { Text("${model.displayName}${selectedVoice?.let { " · ${it.language}" }.orEmpty()}") }
            },
        trailingContent = { Icon(Icons.Filled.ChevronRight, contentDescription = null) },
        modifier = modifier.fillMaxWidth().clickable { showSheet = true },
    )
    if (!showSheet) return
    VoicePickerSheet(
        models = models,
        selectedModelId = selectedModelId,
        selectedVoiceId = selectedVoiceId,
        favoritePairs = favoritePairs,
        onDismiss = { showSheet = false },
        onPick = onPick,
        onToggleFavorite = onToggleFavorite,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoicePickerSheet(
    models: List<ModelDescriptor>,
    selectedModelId: String?,
    selectedVoiceId: String?,
    favoritePairs: Set<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String, String) -> Unit,
    onToggleFavorite: (String, Voice) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // Animate the sheet closed FIRST, then tell the parent to drop it from composition - so both a
    // swipe-to-dismiss and picking a row get the same graceful exit instead of the sheet vanishing
    // mid-animation the instant the parent's "show" flag flips.
    val dismiss: () -> Unit = {
        scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() }
    }
    val pickAndDismiss: (String, String) -> Unit = { modelId, voiceId ->
        onPick(modelId, voiceId)
        dismiss()
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search voices") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            val q = query.trim()
            val favorites =
                models
                    .flatMap { model ->
                        model.voices.filter { (model.modelId to it.id) in favoritePairs }.map { model to it }
                    }
                    .filter { (model, voice) -> matchesVoiceQuery(model, voice, q) }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = SHEET_LIST_MAX_HEIGHT)) {
                if (favorites.isNotEmpty()) {
                    item(key = "favorites-header") { PickerSectionLabel("Favorites") }
                    items(favorites, key = { (model, voice) -> "fav:${model.modelId}:${voice.id}" }) { (model, voice) ->
                        VoicePickerRow(
                            model = model,
                            voice = voice,
                            isSelected = model.modelId == selectedModelId && voice.id == selectedVoiceId,
                            isFavorite = true,
                            onPick = pickAndDismiss,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
                models.forEach { model ->
                    val voices = model.voices.filter { matchesVoiceQuery(model, it, q) }
                    if (voices.isEmpty()) return@forEach
                    item(key = "header:${model.modelId}") { PickerSectionLabel(model.displayName) }
                    items(voices, key = { voice -> "${model.modelId}:${voice.id}" }) { voice ->
                        VoicePickerRow(
                            model = model,
                            voice = voice,
                            isSelected = model.modelId == selectedModelId && voice.id == selectedVoiceId,
                            isFavorite = (model.modelId to voice.id) in favoritePairs,
                            onPick = pickAndDismiss,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VoicePickerRow(
    model: ModelDescriptor,
    voice: Voice,
    isSelected: Boolean,
    isFavorite: Boolean,
    onPick: (String, String) -> Unit,
    onToggleFavorite: (String, Voice) -> Unit,
) {
    ListItem(
        headlineContent = {
            Text(voice.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
        },
        supportingContent = { Text("${model.displayName} · ${voice.language}") },
        trailingContent = {
            IconButton(onClick = { onToggleFavorite(model.modelId, voice) }) {
                val tint =
                    if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = tint,
                )
            }
        },
        colors =
            if (isSelected) {
                ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                ListItemDefaults.colors()
            },
        modifier = Modifier.fillMaxWidth().clickable { onPick(model.modelId, voice.id) },
    )
}

/** A small caps-style sub-label marking a group of rows in the voice picker sheet. */
@Composable
private fun PickerSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

// A voice matches a blank query trivially; otherwise it matches when the voice name, its language,
// or the owning model's display name contains the query text (case-insensitive) - so searching
// "kokoro" finds every Kokoro voice and searching "en" finds every English one, regardless of model.
private fun matchesVoiceQuery(
    model: ModelDescriptor,
    voice: Voice,
    query: String,
): Boolean {
    if (query.isBlank()) return true
    val needle = query.lowercase()
    return voice.name.lowercase().contains(needle) ||
        voice.language.lowercase().contains(needle) ||
        model.displayName.lowercase().contains(needle)
}

private val SHEET_LIST_MAX_HEIGHT: Dp = 420.dp
