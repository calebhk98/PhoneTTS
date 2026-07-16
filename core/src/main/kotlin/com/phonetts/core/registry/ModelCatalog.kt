package com.phonetts.core.registry

import com.phonetts.core.model.ModelDescriptor

/**
 * The set of models the user can pick — the single source of truth the model dropdown reads
 * (spec §7). Built-in models (downloaded via the manifest) and sideloaded models are added
 * through the SAME path (resolve → add); nothing here distinguishes them beyond
 * [ModelDescriptor.origin], which is for display only. Register a model → it appears; remove
 * it → it vanishes. No UI code is edited either way.
 */
class ModelCatalog {
    private val models = LinkedHashMap<String, ModelDescriptor>()

    /** Add or replace the descriptor for its [ModelDescriptor.modelId]. */
    @Synchronized
    fun add(descriptor: ModelDescriptor) {
        models[descriptor.modelId] = descriptor
    }

    @Synchronized
    fun remove(modelId: String) {
        models.remove(modelId)
    }

    @Synchronized
    fun get(modelId: String): ModelDescriptor? = models[modelId]

    /** Every model currently available, in insertion order. */
    @Synchronized
    fun list(): List<ModelDescriptor> = models.values.toList()
}
