package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.builtin.BuiltInCatalog

/**
 * In-app help: how to add a voice model, which recommended models exist (read from
 * [BuiltInCatalog] so this can never drift from the actual one-tap list), what a bring-your-own
 * model needs per engine, and troubleshooting - plus the durable error log and a way back into the
 * first-run walkthrough. Pure guidance UI: no stateful preference lives here anymore - the theme
 * picker and "About & updates" moved to Settings, since a stateful control doesn't belong on a help
 * page. It names no model fact that drives another control; the recommended list is the single
 * source of truth it derives from.
 */
@Composable
fun HelpScreen(
    // The durable error log's rows, newest-first, already formatted to one line each (see
    // MainActivity). Surfaced here (issue #109) so the user has a copyable "why" when a model - e.g. a
    // LiteRT .tflite - fails to load or run, or the app itself crashed (each such row is tagged
    // "[crash] ..." by MainActivity's `source` prefix, since CrashReporter records crashes under the
    // "crash" source - see com.phonetts.core.diagnostics.CrashLogFormatter).
    errorLogEntries: List<String> = emptyList(),
    // Forgets every recorded error/crash (durableErrorLog.clear()). Defaults to a no-op so this screen
    // still compiles/previews standalone; MainActivity wires the real store.
    onClearErrorLog: () -> Unit = {},
    // Replays the first-run walkthrough on demand (onboarding itself stays a first-run-only flow,
    // not a nav destination - this is its one other entry point). Defaults to a no-op so this screen
    // still compiles/previews standalone.
    onReplayTutorial: () -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Section("Getting a voice") {
            Bullet("Tap Voices → Browse → the Recommended (one-tap) section for a working voice in one tap.")
            Bullet("Or search Hugging Face in Voices → Browse and tap Download on a result.")
            Bullet("Or use \"Add model\" on Voices → Downloaded to import a model you already copied onto the phone.")
            Bullet("Everything after the download runs fully offline - no network is used to speak.")
        }

        Section("Recommended models (one-tap)") {
            BuiltInCatalog.ALL.forEach { model ->
                Text("• ${model.displayName} - ~${model.approxSizeMb} MB", fontWeight = FontWeight.Medium)
                model.note?.let { Body("   $it") }
            }
        }

        Section("Bringing your own model") {
            Body("The app auto-detects a model from its files. A lone .onnx usually isn't enough - grab these:")
            Engine("Piper", "rhasspy/piper-voices", "<voice>.onnx + <voice>.onnx.json. Any voice/language works.")
            Engine("KittenTTS", "KittenML/kitten-tts-nano-*", "<model>.onnx + config.json + voices.npz. Tiny, English.")
            Engine(
                "Kokoro-82M",
                "onnx-community/Kokoro-82M-v1.0-ONNX",
                "onnx/model.onnx (fp32) + config.json + tokenizer.json + voices/<name>.bin. " +
                    "Use fp32 - q8f16 crashes.",
            )
            Engine(
                "MeloTTS",
                "MiaoMint/MeloTTS-ONNX",
                "a language folder's model.onnx + tokens.txt + lexicon.txt + metadata.json. Not the myshell repo.",
            )
        }

        Section("Which model formats work, and which cannot") {
            Body(
                "This app runs models on ONNX and a native GGUF/LiteRT backend. A model has to already " +
                    "be in a format the app's own runtimes read - it cannot run a format by downloading " +
                    "and executing new code, which Android blocks.",
            )
            Bullet("Works now: ONNX (.onnx), and native GGUF stacks (like the recommended CosyVoice3).")
            Bullet(
                "Needs converting first: raw PyTorch or safetensors, NVIDIA NeMo (.nemo), and " +
                    "TensorFlow Lite (.tflite). These can often be converted to ONNX on a computer, then " +
                    "sideloaded here.",
            )
            Bullet(
                "Cannot run here at all: Apple MLX (Metal) and Apple CoreML (Neural Engine). They only " +
                    "run on Apple hardware and there is no Android runtime for them - this is not a " +
                    "'coming soon', they will never run in this app. Look for an ONNX or LiteRT sibling " +
                    "of the model instead.",
            )
        }

        ErrorLogSection(errorLogEntries, onClearErrorLog)

        Section("Troubleshooting") {
            Q("No sound, or it's garbled?", "Piper/Kitten/Kokoro need the espeak add-on in the build; MeloTTS doesn't.")
            Q("Which Kokoro file?", "The fp32 onnx/model.onnx - the q8f16 one crashes the runtime.")
            Q(
                "MeloTTS won't work?",
                "Use the MiaoMint sherpa export (with tokens.txt/lexicon.txt), not myshell-ai/MeloTTS.",
            )
            Q(
                "It asked me to pick an engine?",
                "It couldn't identify the model - pick the matching engine; it's remembered.",
            )
            Q("Freeing space?", "Voices → Downloaded shows each model's size and lets you delete it.")
        }

        Section("Tutorial") {
            Body("Replay the first-run walkthrough - the same one shown the first time the app was opened.")
            OutlinedButton(onClick = onReplayTutorial) { Text("Replay tutorial") }
        }
    }
}

// The durable error log (issue #109 + crash log): survives process death, so a model that failed to
// load/run - or a crash - on a previous run is still explainable. Only offers the viewer once
// something is recorded; the dialog is selectable + copyable ("Copy all") so the user can paste a
// full report rather than screenshot it - mirroring the Browse screen's ErrorLogDialog. [entries] is
// already newest-first (DurableErrorLog's contract), so a crash - always the freshest possible entry -
// is the first line the user sees.
//
// [localEntries] mirrors [entries] but is re-seeded only when the CALLER's list actually changes
// (`remember(entries)`); this lets "Clear log" hide the list immediately even though MainActivity
// currently reads the durable store directly at composition time rather than from a recomposing
// state, so it would otherwise take an unrelated recomposition to reflect the clear.
@Composable
private fun ErrorLogSection(
    entries: List<String>,
    onClear: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    var localEntries by remember(entries) { mutableStateOf(entries) }
    Section("Error log") {
        if (localEntries.isEmpty()) {
            Body(
                "No errors recorded yet, including app crashes. If a model fails to load/run, or the " +
                    "app crashes, the reason shows up here.",
            )
            return@Section
        }
        Body("Recorded reasons the app crashed or a model failed to load/run - copy these when reporting a problem.")
        OutlinedButton(onClick = { showLog = true }) { Text("View error log (${localEntries.size})") }
    }
    if (showLog) {
        ErrorLogDialog(
            entries = localEntries,
            onDismiss = { showLog = false },
            onClear = {
                onClear()
                localEntries = emptyList()
                showLog = false
            },
        )
    }
}

@Composable
private fun ErrorLogDialog(
    entries: List<String>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val fullText = remember(entries) { entries.joinToString("\n\n") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(fullText)) }) { Text("Copy all") }
                OutlinedButton(onClick = onClear) { Text("Clear log") }
            }
        },
        title = { Text("Error log") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Bullet(text: String) = Text("• $text")

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun Engine(
    name: String,
    repo: String,
    needs: String,
) {
    Text("• $name ($repo)", fontWeight = FontWeight.Medium)
    Body("   $needs")
}

@Composable
private fun Q(
    question: String,
    answer: String,
) {
    Text(question, fontWeight = FontWeight.Medium)
    Body("   $answer")
}
