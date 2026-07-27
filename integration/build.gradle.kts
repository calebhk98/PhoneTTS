// Cross-module integration tests: the ONLY place that puts all five engine modules on one
// classpath, to prove they drop in via ServiceLoader with zero shared code naming any of them.
// Pure JVM, so it runs here with no Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

// The engine modules settings.gradle.kts discovered on disk (:engines:common is shared plumbing,
// not an engine). One derivation, no hand-maintained list - see settings.gradle.kts.
val engineProjectPaths =
    rootProject.subprojects
        .map { it.path }
        .filter { it.startsWith(":engines:") && it != ":engines:common" }
        .sorted()

dependencies {
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    // Desktop ONNX Runtime (the JVM twin of the app's onnxruntime-android) so the end-to-end
    // auto-load test can run the REAL engines' synthesize() over a real model on plain JVM,
    // exactly as the app would on-device - only the platform backend differs, not our code.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // Every engine module on the test classpath - discovered by ServiceLoader, never named here.
    // This comment used to sit directly above five hand-written module names, and the other ten
    // engines therefore got no cross-module coverage at all. Derived from the same on-disk
    // authority settings.gradle.kts uses, so it can no longer go stale.
    engineProjectPaths.forEach { path -> testImplementation(project(path)) }
}

tasks.test {
    useJUnitPlatform()
    // Let AllEnginesDiscoveryTest assert against however many engine modules actually exist rather
    // than a hand-edited constant. The constant it replaced said 5 while 15 modules were on disk,
    // so the one test meant to prove "every engine drops in via ServiceLoader" was passing while
    // ignoring two thirds of them.
    systemProperty("phonetts.expectedEngineCount", engineProjectPaths.size)
    // Forward the opt-in flags for the network+native RealModel*AutoLoadTests into the test JVM
    // (gradle does not propagate -D system properties to forked test workers by default).
    System.getProperty("runRealModel")?.let { systemProperty("runRealModel", it) }
    // CosyVoice's real-model test binds a desktop build of cosyvoice3_tts (scripts/model-verify/
    // build_jvm_cosyvoice.sh) and can reuse a pre-downloaded GGUF stack to skip the 745 MB fetch.
    System.getProperty("cosyvoice.nativeLib")?.let { systemProperty("cosyvoice.nativeLib", it) }
    System.getProperty("cosyvoice.modelDir")?.let { systemProperty("cosyvoice.modelDir", it) }
    // Inflect's real-model test can reuse an already-downloaded ONNX pair instead of re-fetching.
    System.getProperty("inflect.modelDir")?.let { systemProperty("inflect.modelDir", it) }
}
