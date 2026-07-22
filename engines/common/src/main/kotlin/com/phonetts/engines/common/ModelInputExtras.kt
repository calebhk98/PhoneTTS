package com.phonetts.engines.common

import com.phonetts.core.engine.ExtraKey
import com.phonetts.core.engine.ModelInput
import kotlin.reflect.safeCast

// The one generic, type-safe accessor for ModelInput.extras (issue #18 item 2). Any engine that
// needs a typed side value out of its frontend's output -- a LongArray of tone ids, a scalar Int,
// a FloatArray embedding, or anything a future frontend adds -- reads it through these two
// functions, parameterized only by an ExtraKey. No per-model helper, no raw `as?` cast at the
// callsite; adding a new extra type needs a new ExtraKey, never a change here.

/**
 * The entry under [key]'s name, cast to [key]'s declared type, or null if the key is absent or
 * the stored value is a different type (fails closed, spec rule 4 -- never a
 * `ClassCastException`).
 */
fun <T : Any> ModelInput.extra(key: ExtraKey<T>): T? = extras[key.name]?.let { key.type.safeCast(it) }

/** [extra] that fails loudly instead of returning null, naming [engineLabel] and the missing key. */
fun <T : Any> ModelInput.requireExtra(
    key: ExtraKey<T>,
    engineLabel: String,
): T =
    extra(key) ?: error(
        "$engineLabel: ModelInput.extras is missing '${key.name}' (expected a ${key.type.simpleName})",
    )
