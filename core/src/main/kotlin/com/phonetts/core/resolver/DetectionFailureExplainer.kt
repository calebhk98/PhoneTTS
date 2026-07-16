package com.phonetts.core.resolver

import com.phonetts.core.engine.VoiceEngine
import com.phonetts.core.model.ModelBundle

/** One companion-file category spec §6.2 names as what a self-describing bundle typically ships. */
private data class CompanionFileCategory(
    val label: String,
    val present: (ModelBundle) -> Boolean,
)

/** Which of the standard companion-file categories a bundle has, per [DetectionFailureExplainer]. */
data class CompanionFileFindings(
    val presentCategories: List<String>,
    val missingCategories: List<String>,
) {
    /** True when none of the standard companion-file categories were found on the bundle. */
    val isBareWeightsFile: Boolean get() = presentCategories.isEmpty()
}

/**
 * Human-readable explanation of why no engine's [VoiceEngine.inspect] claimed a [ModelBundle]
 * (spec §6.2: `inspect()` fails closed rather than guessing, so an unclaimed bundle drops to the
 * user-pick fallback). Read-only and inert on its own: [explain] calls the same [VoiceEngine.inspect]
 * probe [com.phonetts.core.resolver.Resolver] already uses — a query method by the interface's own
 * contract — and never calls `load`, `forcedMatch`, or anything else that could change state. It
 * makes no [Resolver] calls and persists nothing.
 *
 * What it can and cannot know: no individual engine exposes *why* its `inspect()` declined a
 * bundle — that fingerprinting logic is private to each engine family (spec §5.1). So this class
 * reports only what is externally observable: which registered engines were asked and declined,
 * and which of the standard companion-file categories spec §6.2 lists (config.json, tokenizer,
 * phoneme map, voice/speaker table) are present or missing on the bundle itself.
 */
class DetectionFailureExplainer {
    fun explain(
        bundle: ModelBundle,
        engines: List<VoiceEngine>,
    ): DetectionFailureReport {
        val claimedByEngineIds = engines.filter { it.inspect(bundle) != null }.map { it.id }
        val findings = companionFileFindings(bundle)
        val summary = buildSummary(bundle, engines.map { it.id }, claimedByEngineIds, findings)
        return DetectionFailureReport(
            bundleId = bundle.id,
            checkedEngineIds = engines.map { it.id },
            claimedByEngineIds = claimedByEngineIds,
            presentCompanionFiles = findings.presentCategories,
            missingCompanionFiles = findings.missingCategories,
            isBareWeightsFile = findings.isBareWeightsFile,
            summary = summary,
        )
    }

    private fun companionFileFindings(bundle: ModelBundle): CompanionFileFindings {
        val present = COMPANION_FILE_CATEGORIES.filter { it.present(bundle) }.map { it.label }
        val missing = COMPANION_FILE_CATEGORIES.filterNot { it.present(bundle) }.map { it.label }
        return CompanionFileFindings(present, missing)
    }

    private fun buildSummary(
        bundle: ModelBundle,
        checkedEngineIds: List<String>,
        claimedByEngineIds: List<String>,
        findings: CompanionFileFindings,
    ): String {
        if (claimedByEngineIds.isNotEmpty()) {
            return "'${bundle.id}' was actually claimed by: ${claimedByEngineIds.joinToString()}. " +
                "Detection did not fail for this bundle."
        }
        if (checkedEngineIds.isEmpty()) {
            return "No engines are registered to check '${bundle.id}' against."
        }
        if (findings.isBareWeightsFile) {
            return "None of the ${checkedEngineIds.size} registered engine(s) claimed '${bundle.id}': " +
                "it has none of the standard companion files ($COMPANION_FILE_LABEL_LIST). Per spec " +
                "§6.2, a bare weights file with no side files is often not self-describing enough " +
                "for confident auto-detection, so inspect() fails closed rather than guessing."
        }
        return "None of the ${checkedEngineIds.size} registered engine(s) claimed '${bundle.id}'. " +
            "It is missing these standard companion file categories: " +
            "${findings.missingCategories.joinToString()}. Per spec §6.2, inspect() fails closed " +
            "when a bundle's family cannot be established with confidence, rather than guessing."
    }

    companion object {
        private val COMPANION_FILE_CATEGORIES =
            listOf(
                CompanionFileCategory("config.json") { it.hasFile("config.json") },
                CompanionFileCategory("tokenizer") { bundle ->
                    bundle.fileNames.any {
                        it.contains("tokenizer", ignoreCase = true) || it.contains("vocab", ignoreCase = true)
                    }
                },
                CompanionFileCategory("phoneme map") { bundle ->
                    bundle.fileNames.any { it.contains("phoneme", ignoreCase = true) }
                },
                CompanionFileCategory("voice/speaker table") { bundle ->
                    bundle.fileNames.any {
                        it.contains("voice", ignoreCase = true) || it.contains("speaker", ignoreCase = true)
                    }
                },
            )
        private val COMPANION_FILE_LABEL_LIST = COMPANION_FILE_CATEGORIES.joinToString { it.label }
    }
}

/** The result of [DetectionFailureExplainer.explain] — a narration, not a new detection decision. */
data class DetectionFailureReport(
    val bundleId: String,
    val checkedEngineIds: List<String>,
    val claimedByEngineIds: List<String>,
    val presentCompanionFiles: List<String>,
    val missingCompanionFiles: List<String>,
    val isBareWeightsFile: Boolean,
    val summary: String,
)
