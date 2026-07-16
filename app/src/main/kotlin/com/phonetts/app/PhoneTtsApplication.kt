package com.phonetts.app

import android.app.Application

/** Builds the [AppGraph] once at process start and re-hydrates the model catalog. */
class PhoneTtsApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.hydrate()
    }
}
