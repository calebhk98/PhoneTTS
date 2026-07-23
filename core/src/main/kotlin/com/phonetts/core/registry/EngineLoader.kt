package com.phonetts.core.registry

import com.phonetts.core.engine.EngineContext
import com.phonetts.core.engine.EngineProvider
import java.util.ServiceLoader

/**
 * Discovers [EngineProvider]s on the classpath and seeds an [EngineRegistry] from them. This
 * is the single, generic bootstrap path for the built-in engines (spec §5.4: pre-seeded at
 * startup) - and it names no model. Whichever engine modules are on the classpath appear;
 * removing a module removes its engine with no other change.
 */
object EngineLoader {
    fun discoverProviders(classLoader: ClassLoader = EngineLoader::class.java.classLoader): List<EngineProvider> =
        ServiceLoader.load(EngineProvider::class.java, classLoader).toList()

    /** Register every discovered provider's engine into [registry]. */
    fun seed(
        registry: EngineRegistry,
        context: EngineContext,
        providers: List<EngineProvider> = discoverProviders(),
    ) {
        providers.forEach { provider -> registry.register(provider.create(context)) }
    }
}
