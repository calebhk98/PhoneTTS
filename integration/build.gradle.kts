// Cross-module integration tests: the ONLY place that puts all five engine modules on one
// classpath, to prove they drop in via ServiceLoader with zero shared code naming any of them.
// Pure JVM, so it runs here with no Android SDK.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

dependencies {
    testImplementation(project(":core"))
    testImplementation(testFixtures(project(":core")))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)

    // Desktop ONNX Runtime (the JVM twin of the app's onnxruntime-android) so the end-to-end
    // auto-load test can run the REAL engines' synthesize() over a real model on plain JVM,
    // exactly as the app would on-device — only the platform backend differs, not our code.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.20.0")

    // Every engine module on the test classpath — discovered by ServiceLoader, never named here.
    testImplementation(project(":engines:cosyvoice2"))
    testImplementation(project(":engines:melotts"))
    testImplementation(project(":engines:piper"))
    testImplementation(project(":engines:kittentts"))
    testImplementation(project(":engines:kokoro"))
}

tasks.test {
    useJUnitPlatform()
    // Forward the opt-in flag for the network+espeak RealModelAutoLoadTest into the test JVM
    // (gradle does not propagate -D system properties to forked test workers by default).
    System.getProperty("runRealModel")?.let { systemProperty("runRealModel", it) }
}
