package com.phonetts.core.engine

import com.phonetts.core.registry.RuntimeRegistry
import com.phonetts.core.text.Phonemizer

/**
 * The dependencies an engine may need, handed to it at construction (see [EngineProvider]).
 * An engine pulls the runtime it wants from [runtimes] by id and uses [phonemizer] if its
 * frontend is phoneme-based. Supplying these here — rather than letting engines construct
 * them — is what keeps engines free of platform detail and fully testable with fakes.
 *
 * [platform] is an optional escape hatch (default null) for a provider whose engine needs a
 * platform service beyond the two above — e.g. an Android `Context` for an engine that wraps an
 * OS service rather than loading weights of its own. It is typed as the Android-free
 * [PlatformServices] marker so :core still names no platform type; the concrete implementation
 * lives in whichever module builds this [EngineContext] (see `com.phonetts.app.AppGraph`), and a
 * provider that needs it casts to that module's concrete type. Existing providers/tests that never
 * pass it are unaffected.
 */
class EngineContext(
    val runtimes: RuntimeRegistry,
    val phonemizer: Phonemizer,
    val platform: PlatformServices? = null,
)
