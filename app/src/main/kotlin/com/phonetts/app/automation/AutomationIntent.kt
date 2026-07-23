package com.phonetts.app.automation

import android.content.Intent
import com.phonetts.core.automation.AutomationRequest

/**
 * The public intent contract of the Tasker/`adb`/MacroDroid automation surface (issue #41):
 * the action + extra keys a caller sets, and the extras the result intent carries back. Kept in
 * one object so the manifest action and the parser can't drift, and so the whole contract is
 * documented in a single place a power user can read.
 *
 * These are the automation entry point's own API, not model facts - so naming them here does not
 * touch the SSOT rule (which governs sample rates / voices / speed bounds, all still read from the
 * descriptor at [AutomationRequest] planning time).
 */
object AutomationIntent {
    /** `am start -a com.phonetts.action.SYNTHESIZE …` (also declared in the manifest intent-filter). */
    const val ACTION = "com.phonetts.action.SYNTHESIZE"

    const val EXTRA_TEXT = "text"
    const val EXTRA_ENGINE_ID = "engineId"
    const val EXTRA_VOICE_ID = "voiceId"
    const val EXTRA_SPEED = "speed"
    const val EXTRA_OUTPUT_URI = "outputUri"

    /** Result-intent extras the activity sets before finishing (RESULT_OK on success). */
    const val RESULT_SUCCESS = "success"
    const val RESULT_OUTPUT_URI = "outputUri"
    const val RESULT_ERROR = "error"

    /**
     * Read the raw extras ONCE into a single typed [AutomationRequest] (owner's request on the
     * issue: pass one object around, not five loose parameters). [outputUri] falls back to the
     * intent's own data URI so `am start -d <uri>` works too. Blank/absent optionals normalize to
     * null inside [AutomationRequest.of].
     */
    fun parse(intent: Intent): AutomationRequest =
        AutomationRequest.of(
            text = intent.getStringExtra(EXTRA_TEXT),
            engineId = intent.getStringExtra(EXTRA_ENGINE_ID),
            voiceId = intent.getStringExtra(EXTRA_VOICE_ID),
            speed = intent.floatExtraOrNull(EXTRA_SPEED),
            outputUri = intent.getStringExtra(EXTRA_OUTPUT_URI) ?: intent.dataString,
        )

    // Speed may arrive as a string (am --es, Tasker) or a float (am --ef) - accept either, else
    // "unset". String form is tried first: a float extra returns null from getStringExtra so we
    // then read it numerically, avoiding the deprecated Bundle.get.
    private fun Intent.floatExtraOrNull(key: String): Float? {
        if (!hasExtra(key)) return null
        val asString = getStringExtra(key)
        if (asString != null) return asString.toFloatOrNull()
        return getFloatExtra(key, Float.NaN).takeUnless { it.isNaN() }
    }
}
