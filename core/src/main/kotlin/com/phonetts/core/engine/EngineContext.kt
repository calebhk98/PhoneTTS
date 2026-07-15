package com.phonetts.core.engine

import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.text.Phonemizer

/**
 * The dependencies an engine may need, handed to it at construction (see [EngineProvider]).
 * An engine pulls the runtime it wants from [runtimes] by id and uses [phonemizer] if its
 * frontend is phoneme-based. Supplying these here — rather than letting engines construct
 * them — is what keeps engines free of platform detail and fully testable with fakes.
 */
class EngineContext(
    val runtimes: RuntimeRegistry,
    val phonemizer: Phonemizer,
    /** App-private directory where this engine's downloaded weights live, if it needs a base path. */
    val storageDir: String? = null,
)
