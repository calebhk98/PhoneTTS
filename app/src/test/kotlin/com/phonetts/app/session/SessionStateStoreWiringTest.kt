package com.phonetts.app.session

import com.phonetts.core.session.ListViewSession
import com.phonetts.core.session.ReaderSession
import com.phonetts.core.session.SessionStateStore
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #131's real defect was never a wrong formula - it was that [SessionStateStore] shipped
 * `browse()`/`updateBrowse`, `manage()`/`updateManage`, and `benchmark()`/`updateBenchmark`, each
 * fully unit-tested in isolation at the `:core` layer ([com.phonetts.core.session.
 * SessionStateStoreTest]), while zero production code anywhere in `:app` ever called
 * `updateManage`/`updateBenchmark` (`grep -rln "sessionState" app/src/main/kotlin/` returned only
 * `TtsViewModel.kt`/`AppGraph.kt`). Per-field assertions against the store itself - exactly what
 * that existing test file already is - cannot catch this: they pass whether or not any real screen
 * ever calls the method under test. What was missing is a test that fails when a section is
 * declared but never WIRED, no matter which section it is or what it is later renamed to - hence
 * reflection over the store's own public API, not a hand-maintained list of method-name strings.
 *
 * Mirrors [com.phonetts.core.ssot.SsotLiteralGuardTest]'s established pattern in this repo: walk the
 * real `src/main` trees from the checkout root and grep for real usage, rather than mocking
 * anything.
 */
class SessionStateStoreWiringTest {
    @Test
    fun `every SessionStateStore read-and-update pair is actually called from production code`() {
        val repoRoot = findRepoRoot()
        val productionSources =
            listOf(File(repoRoot, "core/src/main/kotlin"), File(repoRoot, "app/src/main/kotlin"))
                .filter { it.exists() }
                .flatMap { it.walkTopDown() }
                // The declaring file itself trivially "calls" persist()/load() internally - that is
                // not evidence any SCREEN wires the feature. Excluded from being a valid call site.
                .filter { it.isFile && it.extension == "kt" && it.name != "SessionStateStore.kt" }
        assertTrue("expected to find scannable production Kotlin sources under $repoRoot", productionSources.isNotEmpty())

        val fileText = productionSources.associateWith { it.readText() }

        val uncalled =
            sessionSectionMethodNames().filterNot { methodName -> methodName in KNOWN_PENDING_WIRING }
                .filterNot { methodName -> fileText.values.any { text -> callsMethod(text, methodName) } }

        assertTrue(
            "SessionStateStore method(s) $uncalled are declared (and have their own SessionStateStoreTest " +
                "coverage) but are never invoked by any production file under core/src/main or " +
                "app/src/main outside SessionStateStore.kt itself. That combination - unit-tested in " +
                "isolation, zero real call sites - is exactly issue #131's bug: a persisted-state " +
                "section that LOOKS wired in review but silently does nothing on a real device. Either " +
                "wire it into the screen that owns it, or add it to KNOWN_PENDING_WIRING with a comment " +
                "explaining why it is deliberately still unwired.",
            uncalled.isEmpty(),
        )
    }

    /**
     * Every accessor (`reader()`, `browse()`, `manage()`, `benchmark()` - returns a [ReaderSession]
     * or [ListViewSession]) and every `update*` method, discovered via reflection so a future section
     * this test has never heard of (a new screen's own persisted state) is covered automatically -
     * no method name is hardcoded as a literal here (CLAUDE.md rule 1's spirit, applied to test
     * coverage rather than a model fact).
     */
    private fun sessionSectionMethodNames(): List<String> {
        val methods = SessionStateStore::class.java.methods
        val updaters = methods.filter { it.name.startsWith("update") }.map { it.name }
        val accessors =
            methods
                .filter { it.parameterCount == 0 }
                .filter { it.returnType == ReaderSession::class.java || it.returnType == ListViewSession::class.java }
                .map { it.name }
        return (updaters + accessors).distinct().sorted()
    }

    /** Matches both call styles a real call site uses - `.updateManage(...)` and Kotlin's
     * parenthesis-free trailing-lambda form `.updateManage { ... }` - so a real wiring written either
     * way is recognised. Word-bounded so e.g. `updateManage` never matches a longer identifier that
     * merely contains it. */
    private fun callsMethod(
        text: String,
        methodName: String,
    ): Boolean = Regex("\\b${Regex.escape(methodName)}\\s*[({]").containsMatchIn(text)

    /** Walks up from the working directory to the checkout root (marked by `settings.gradle.kts`) -
     * the same approach [com.phonetts.core.ssot.SsotLiteralGuardTest] already uses in `:core`. */
    private fun findRepoRoot(): File {
        var dir = File(".").absoluteFile
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile ?: error("could not locate repo root (no settings.gradle.kts found above .)")
        }
    }

    private companion object {
        /**
         * Deliberate, VISIBLE exceptions only - mirrors [com.phonetts.app.ScreenSmokeTest]'s own
         * `excluded` map convention (an omission must be a documented act, never silent).
         *
         * `browse`/`updateBrowse`: [com.phonetts.app.hf.HfBrowseViewModel] is owned by a separate,
         * concurrently in-flight session fixing an unrelated Browse-screen crash (this session was
         * explicitly told not to touch that file to avoid colliding with it). The exact wiring patch
         * (seed from `sessionState.browse()` at construction, write back via `updateBrowse` from
         * every sort/tag/language/size/installed/supported/format/engine/RTF filter setter, with the
         * same fail-closed-on-restore treatment as Manage's engine filter) is written up as a diff in
         * this session's report for that session to apply once its own fix lands. Remove this entry
         * the moment that patch is applied - at that point this test enforces it same as any other
         * section, with no code change needed here.
         */
        val KNOWN_PENDING_WIRING = setOf("browse", "updateBrowse")
    }
}
