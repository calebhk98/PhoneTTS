package com.phonetts.core.engine

/** A selectable voice within a model. The voice dropdown renders [name]; [id] is passed to synthesize(). */
data class Voice(
    val id: String,
    val name: String,
    val language: String,
)
