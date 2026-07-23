package com.phonetts.core.ssot

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Automated guard for CLAUDE.md rule 1 (SSOT): "a model constant outside the resolver/descriptor
 * layer is a bug." Today that rule is only loosely protected by
 * [com.phonetts.core.registry.ModelCatalogTest] (registry dynamism) and
 * [com.phonetts.core.model.ModelParametersTest] (parameters are discovered, not assumed) - neither
 * actually scans for a *duplicated* literal creeping into the UI/platform layer. This test does.
 *
 * What it enforces: none of a small, deliberately narrow set of well-known TTS output sample
 * rates (8000/11025/16000/22050/24000/32000/44100/48000 Hz - the values CLAUDE.md calls out by
 * name as a model fact) may appear as a hardcoded Kotlin integer literal in source outside the
 * "resolver/descriptor layer" CLAUDE.md names: [ALLOWED_ROOTS] below -
 * `core/.../model` (the [com.phonetts.core.model.ModelDescriptor] SSOT itself),
 * `core/.../resolver`, and every engine's own `src/main` tree (each engine is the SSOT for *its own*
 * model's sample rate - see e.g. `KokoroEngine.SAMPLE_RATE`, `PiperVoiceConfig.DEFAULT_SAMPLE_RATE`
 * - CLAUDE.md explicitly scopes the descriptor layer as ":core descriptors / :engines"). Anywhere
 * else under `core/src/main` or `app/src/main` (the UI/platform layer, which must read the value
 * from the resolved [com.phonetts.core.model.ModelDescriptor] instead) is forbidden ground: if one
 * of these numbers shows up there as a real code literal, that's a second source of truth and the
 * guarantee CLAUDE.md describes is already broken.
 *
 * Deliberately conservative to keep false positives near zero:
 *  - Only checks actual TTS-model sample rates, not every integer, so unrelated constants (buffer
 *    sizes, timeouts, version codes...) never trip it.
 *  - Strips `//` line comments and skips KDoc/block-comment decoration lines before
 *    matching, so *documenting* a rate (e.g. "24000 for CosyVoice3" in a KDoc comment, as
 *    `CosyVoiceNative.kt` does today) does not fail the build - only a literal actually compiled
 *    into code does.
 *  - Matches on integer-literal boundaries (`(?<!\w)N(?!\w)`, both plain and Kotlin's
 *    underscore-grouped form, e.g. `24000` and `24_000`) so it can't accidentally match inside an
 *    unrelated larger number or identifier.
 *
 * Known false-positive/false-negative edges (acceptable per the above trade-off):
 *  - False positive: a *non-model* integer that happens to equal one of these values (e.g. a
 *    coincidental buffer size of exactly 44100 bytes) would still trip this test if it landed
 *    outside the allowed roots. None do today; if one legitimately needs to, allowlist it by path
 *    rather than loosening the pattern.
 *  - False negative: a literal spelled with a numeric-type suffix directly attached (e.g.
 *    `24000f`, `24000L`) is not matched, since the suffix letter is a word character and the
 *    lookahead treats it as "not a boundary." Real sample-rate constants in this codebase are
 *    plain `Int`s (see the engines cited above), so this has not mattered in practice.
 *  - This only catches the *sample-rate* flavor of the rule (the easiest model fact to pattern-
 *    match reliably); voice names and speed bounds are free-form enough that a precise, low-noise
 *    regex isn't practical, so those remain covered by review + the existing descriptor tests.
 */
class SsotLiteralGuardTest {
    @Test
    fun `no hardcoded TTS sample-rate literal appears outside the resolver-descriptor layer`() {
        val root = findRepoRoot()
        val scanRoots =
            listOf(File(root, "core/src/main/kotlin"), File(root, "app/src/main/kotlin"))
                .filter { it.exists() }
        assertTrue(scanRoots.isNotEmpty(), "expected at least one scannable source root under $root")

        val violations =
            scanRoots
                .flatMap { it.walkTopDown() }
                .filter { it.isFile && it.extension == "kt" && !isAllowed(it) }
                .flatMap { file -> findLiteralHits(file) }

        assertTrue(
            violations.isEmpty(),
            "hardcoded TTS sample-rate literal(s) found outside the resolver/descriptor layer " +
                "(CLAUDE.md rule 1 - read this from ModelDescriptor.sampleRate instead):\n" +
                violations.joinToString("\n"),
        )
    }

    private fun isAllowed(file: File): Boolean {
        val path = file.path.replace(File.separatorChar, '/')
        return ALLOWED_ROOTS.any { marker -> path.contains(marker) }
    }

    private fun findLiteralHits(file: File): List<String> =
        file.readLines().mapIndexedNotNull { index, rawLine ->
            val code = stripCommentary(rawLine)
            val hit = FORBIDDEN_SAMPLE_RATE_PATTERNS.firstOrNull { it.containsMatchIn(code) }
            hit?.let { "${file.path}:${index + 1}: ${rawLine.trim()}" }
        }

    /** Drops `//` line comments and whole lines that are only doc/block-comment decoration. */
    private fun stripCommentary(line: String): String {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) return ""
        val commentStart = line.indexOf("//")
        return if (commentStart >= 0) line.substring(0, commentStart) else line
    }

    /** Walks up from the working directory to the checkout root (marked by `settings.gradle.kts`). */
    private fun findRepoRoot(): File {
        val start = File(".").absoluteFile
        var dir = start
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: error("could not locate repo root (no settings.gradle.kts found above $start)")
        }
    }

    private companion object {
        /**
         * CLAUDE.md's "resolver/descriptor layer" within the scanned roots: core's descriptor and
         * resolver code. Each engine module (the SSOT for its own model's facts) is exempt
         * too, but doesn't need listing here - the scan never walks into the engine modules at all, only
         * `core/src/main` and `app/src/main`.
         */
        val ALLOWED_ROOTS =
            listOf(
                "/core/src/main/kotlin/com/phonetts/core/model/",
                "/core/src/main/kotlin/com/phonetts/core/resolver/",
            )

        /** Well-known TTS output sample rates - the specific model fact CLAUDE.md names by example. */
        val FORBIDDEN_SAMPLE_RATES = listOf(8_000, 11_025, 16_000, 22_050, 24_000, 32_000, 44_100, 48_000)

        val FORBIDDEN_SAMPLE_RATE_PATTERNS =
            FORBIDDEN_SAMPLE_RATES.flatMap { rate ->
                val plain = rate.toString()
                val grouped = "%,d".format(rate).replace(',', '_')
                listOf(plain, grouped).distinct()
            }.map { literal -> Regex("(?<!\\w)${Regex.escape(literal)}(?!\\w)") }
    }
}
