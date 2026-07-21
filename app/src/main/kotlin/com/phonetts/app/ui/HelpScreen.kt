package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.builtin.BuiltInCatalog
import com.phonetts.core.prefs.AppTheme
import com.phonetts.core.update.UpdateStatus

/**
 * In-app help: how to add a voice model, which recommended models exist (read from
 * [BuiltInCatalog] so this can never drift from the actual one-tap list), what a bring-your-own
 * model needs per engine, and troubleshooting. Pure guidance UI — it names no model fact that
 * drives another control; the recommended list is the single source of truth it derives from.
 */
@Composable
fun HelpScreen(
    currentVersion: String,
    update: UpdateStatus?,
    checkStatus: String?,
    onCheckForUpdates: () -> Unit,
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        UpdatesSection(currentVersion, update, checkStatus, onCheckForUpdates)

        ThemeSection(currentTheme, onThemeSelected)

        Section("Getting a voice") {
            Bullet("Tap Browse models → the Recommended (one-tap) section for a working voice in one tap.")
            Bullet("Or search Hugging Face in Browse models and tap Download on a result.")
            Bullet("Or Sideload folder to add a model you already copied onto the phone.")
            Bullet("Everything after the download runs fully offline — no network is used to speak.")
        }

        Section("Recommended models (one-tap)") {
            BuiltInCatalog.ALL.forEach { model ->
                Text("• ${model.displayName} — ~${model.approxSizeMb} MB", fontWeight = FontWeight.Medium)
                model.note?.let { Body("   $it") }
            }
        }

        Section("Bringing your own model") {
            Body("The app auto-detects a model from its files. A lone .onnx usually isn't enough — grab these:")
            Engine("Piper", "rhasspy/piper-voices", "<voice>.onnx + <voice>.onnx.json. Any voice/language works.")
            Engine("KittenTTS", "KittenML/kitten-tts-nano-*", "<model>.onnx + config.json + voices.npz. Tiny, English.")
            Engine(
                "Kokoro-82M",
                "onnx-community/Kokoro-82M-v1.0-ONNX",
                "onnx/model.onnx (fp32) + config.json + tokenizer.json + voices/<name>.bin. Use fp32 — q8f16 crashes.",
            )
            Engine(
                "MeloTTS",
                "MiaoMint/MeloTTS-ONNX",
                "a language folder's model.onnx + tokens.txt + lexicon.txt + metadata.json. Not the myshell repo.",
            )
        }

        Section("Troubleshooting") {
            Q("No sound, or it's garbled?", "Piper/Kitten/Kokoro need the espeak add-on in the build; MeloTTS doesn't.")
            Q("Which Kokoro file?", "The fp32 onnx/model.onnx — the q8f16 one crashes the runtime.")
            Q("MeloTTS won't work?", "Use the MiaoMint sherpa export (with tokens.txt/lexicon.txt), not myshell-ai/MeloTTS.")
            Q("It asked me to pick an engine?", "It couldn't identify the model — pick the matching engine; it's remembered.")
            Q("Freeing space?", "Manage models shows each model's size and lets you delete it.")
        }
    }
}

// Version + a manual update check. The app also checks automatically at launch (silent unless a
// newer build exists); this button lets the user re-check on demand and always gives feedback —
// "Up to date (v…)", a download offer, or a note when the check couldn't reach GitHub. Installing is
// always the user's choice (offer, never force): Download only opens the APK URL in the browser.
@Composable
private fun UpdatesSection(
    currentVersion: String,
    update: UpdateStatus?,
    checkStatus: String?,
    onCheckForUpdates: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Section("About & updates") {
        Body("Version $currentVersion")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCheckForUpdates) { Text("Check for updates") }
            update?.let { available ->
                val target = available.apkDownloadUrl ?: available.releasePageUrl
                Button(onClick = { target?.let(uriHandler::openUri) }, enabled = target != null) {
                    Text("Download ${available.latestVersion}")
                }
            }
        }
        checkStatus?.let { Body(it) }
    }
}

// The reading-friendly theme picker. Options are derived from AppTheme.entries (SSOT: the enum is
// the single authority for which themes exist), so adding a scheme needs no edit here.
@Composable
private fun ThemeSection(
    currentTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
) {
    Section("Appearance") {
        Body("Pick a color theme — sepia and true-black are tuned for long reading / OLED battery.")
        AppTheme.entries.forEach { theme ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = theme == currentTheme, onClick = { onThemeSelected(theme) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(selected = theme == currentTheme, onClick = { onThemeSelected(theme) })
                Text(theme.displayName)
            }
        }
    }
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
