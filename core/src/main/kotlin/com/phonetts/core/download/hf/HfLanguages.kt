package com.phonetts.core.download.hf

/**
 * Interprets the Hugging Face Hub's own language tags into a language filter for the browse screen
 * (issue: add a language filter — many TTS models are multilingual and the user mostly wants
 * English). The Hub publishes a model's languages as **bare ISO 639-1 codes** in its `tags` array
 * (e.g. `en`, `es`, `zh`) plus a `multilingual` marker — verified against the live `/api/models`
 * response — mixed in among unrelated tags (`onnx`, `tts`, `safetensors`) and namespaced boilerplate
 * (`region:us`, `license:…`, `arxiv:…`).
 *
 * The available languages are always derived from the current result set (never a hardcoded catalog
 * — spec rule 1's SSOT discipline), so a language the Hub starts tagging tomorrow shows up as a
 * filter choice with no code change. [NAMES] is not a model fact: it is a generic ISO code → English
 * display-name table (the same category as a locale label), used only to render a friendlier menu.
 * A code present in the results but absent from [NAMES] simply isn't offered as a filter — graceful
 * degradation, never a guess.
 */
object HfLanguages {
    /** The Hub's marker tag for models that span many languages. */
    const val MULTILINGUAL = "multilingual"

    // ISO 639-1 code → English name. Covers the languages a TTS user is realistically choosing
    // among; an unrecognized code just isn't surfaced as a language choice (see class kdoc).
    private val NAMES: Map<String, String> =
        mapOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "pl" to "Polish",
            "ru" to "Russian", "uk" to "Ukrainian", "cs" to "Czech", "sk" to "Slovak",
            "sl" to "Slovenian", "hr" to "Croatian", "sr" to "Serbian", "bg" to "Bulgarian",
            "ro" to "Romanian", "hu" to "Hungarian", "fi" to "Finnish", "sv" to "Swedish",
            "no" to "Norwegian", "da" to "Danish", "is" to "Icelandic", "el" to "Greek",
            "tr" to "Turkish", "ar" to "Arabic", "he" to "Hebrew", "fa" to "Persian",
            "ur" to "Urdu", "hi" to "Hindi", "bn" to "Bengali", "ta" to "Tamil",
            "te" to "Telugu", "ml" to "Malayalam", "kn" to "Kannada", "mr" to "Marathi",
            "gu" to "Gujarati", "pa" to "Punjabi", "ne" to "Nepali", "si" to "Sinhala",
            "th" to "Thai", "vi" to "Vietnamese", "id" to "Indonesian", "ms" to "Malay",
            "tl" to "Tagalog", "zh" to "Chinese", "ja" to "Japanese", "ko" to "Korean",
            "sw" to "Swahili", "am" to "Amharic", "yo" to "Yoruba", "ha" to "Hausa",
            "af" to "Afrikaans", "ca" to "Catalan", "eu" to "Basque", "gl" to "Galician",
            "et" to "Estonian", "lv" to "Latvian", "lt" to "Lithuanian", "ka" to "Georgian",
            "hy" to "Armenian", "az" to "Azerbaijani", "kk" to "Kazakh", "mn" to "Mongolian",
        )

    /** The language codes this app recognizes on [summary] — the [MULTILINGUAL] marker and any bare
     * ISO code in [NAMES]. Order preserves the model's own tag order. */
    fun codesOf(summary: HfModelSummary): List<String> = summary.tags.filter { it == MULTILINGUAL || it in NAMES }

    /**
     * The distinct languages present across [results], as the actual choices for a "filter by
     * language" menu — derived from the data, not a fixed list. Specific languages come first,
     * sorted by display name; [MULTILINGUAL] (a catch-all, not a single language) sorts last so it
     * doesn't crowd out the specific choice the user is scanning for.
     */
    fun availableLanguages(results: List<HfModelSummary>): List<String> {
        val present = results.flatMap { codesOf(it) }.toSet()
        val specific = present.filter { it != MULTILINGUAL }.sortedBy { displayName(it) }
        val multilingual = if (MULTILINGUAL in present) listOf(MULTILINGUAL) else emptyList()
        return specific + multilingual
    }

    /** A friendly label for a language [code] — its English name where known (e.g. `en` → English),
     * a capitalized "Multilingual" for the marker, else the raw code so nothing is ever dropped. */
    fun displayName(code: String): String =
        when (code) {
            MULTILINGUAL -> "Multilingual"
            else -> NAMES[code] ?: code
        }

    /**
     * Keeps only results in language [code]; a blank/null [code] means "no filter". Selecting a
     * specific language also keeps [MULTILINGUAL]-tagged models — a multilingual model covers that
     * language, and a user who "mostly wants English" still wants the multilingual models that speak
     * it. Selecting [MULTILINGUAL] itself keeps only the models explicitly marked that way.
     */
    fun filterByLanguage(
        results: List<HfModelSummary>,
        code: String?,
    ): List<HfModelSummary> {
        if (code.isNullOrBlank()) return results
        if (code == MULTILINGUAL) return results.filter { MULTILINGUAL in it.tags }
        return results.filter { code in it.tags || MULTILINGUAL in it.tags }
    }
}
