package com.phonetts.app

import android.app.Application
import com.phonetts.app.diagnostics.CrashReporter

/** Builds the [AppGraph] once at process start and re-hydrates the model catalog. */
class PhoneTtsApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Installed FIRST, before AppGraph is built, so a crash anywhere after this point - including
        // during AppGraph's own construction - is recorded to the durable error log the Help screen
        // shows (see CrashReporter's kdoc for why "no error log after a crash" happened at all).
        CrashReporter.install(this)
        graph = AppGraph(this)
        graph.hydrate()
    }
}
