package com.phonetts.core.engine

/**
 * A neutral marker for platform-specific services a provider might need beyond [EngineContext]'s
 * Android-free [EngineContext.runtimes]/[EngineContext.phonemizer] — e.g. an Android `Context` for
 * an engine that wraps an OS service rather than loading its own weights (see
 * `com.phonetts.app.runtime.SystemTtsEngineProvider`).
 *
 * :core stays Android-free by design: this interface carries no members and no `android.*`
 * import. The concrete platform (`:app`) defines its own implementation exposing whatever it needs
 * (an application `Context`, etc.) and a provider that needs it casts [EngineContext.platform] to
 * that concrete type. Providers that need nothing beyond [EngineContext]'s existing fields ignore
 * this entirely — it is optional and defaults to null (see [EngineContext]).
 */
interface PlatformServices
