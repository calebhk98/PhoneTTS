package com.phonetts.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.phonetts.core.download.builtin.BuiltInCatalog

// One walkthrough page. Kept as data so the carousel is a list to page through, not a pyramid of
// conditionals — the last page is the call-to-action that dismisses onboarding.
private data class OnboardingPage(
    val title: String,
    val body: String,
    val detail: String? = null,
)

// Static, offline-safe copy. The "download once" page derives its concrete example from
// BuiltInCatalog so it never names a model fact the recommended list doesn't actually offer
// (SSOT: the catalog is the single authority for what a one-tap download lands).
private fun onboardingPages(): List<OnboardingPage> {
    val smallest = BuiltInCatalog.ALL.minByOrNull { it.approxSizeMb }
    val exampleLine =
        smallest?.let { "e.g. ${it.displayName} — about ${it.approxSizeMb} MB, downloaded one time." }
    return listOf(
        OnboardingPage(
            title = "Welcome to PhoneTTS",
            body = "An offline text-to-speech reader. Paste or share text, pick a voice, and listen — " +
                "no account, no cloud.",
        ),
        OnboardingPage(
            title = "1. Pick a model",
            body = "PhoneTTS speaks with neural voice models. It ships with none pre-installed, so you " +
                "choose which one(s) you want from the Browse screen.",
        ),
        OnboardingPage(
            title = "2. Download once",
            body = "The first time you pick a model it downloads to your phone. That is the only time the " +
                "app uses the network.",
            detail = exampleLine,
        ),
        OnboardingPage(
            title = "3. Fully offline after that",
            body = "Once a model is on the phone, every word is synthesized on-device — on a plane, " +
                "underground, or in airplane mode. Nothing you read leaves the phone.",
        ),
    )
}

/**
 * First-run walkthrough: a small static carousel explaining "pick a model → download once → fully
 * offline after that", shown once for a fresh install instead of dropping the user into an empty
 * model list. Purely informational and offline-safe. [onFinish] is called when the user taps
 * through the last page or skips — the caller records that so it never shows again.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pages = remember { onboardingPages() }
    var index by remember { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFinish) { Text("Skip") }
        }

        PageBody(page)

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            PageDots(count = pages.size, active = index)
            Button(
                onClick = { if (isLast) onFinish() else index++ },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isLast) "Get started" else "Next")
            }
        }
    }
}

@Composable
private fun PageBody(page: OnboardingPage) {
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(page.body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        page.detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun PageDots(
    count: Int,
    active: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            val color =
                if (i == active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(modifier = Modifier.padding(4.dp)) {
                Surface(modifier = Modifier.size(10.dp), shape = CircleShape, color = color) {}
            }
        }
    }
}
