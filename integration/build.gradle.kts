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

    // Every engine module on the test classpath — discovered by ServiceLoader, never named here.
    testImplementation(project(":engines:cosyvoice2"))
    testImplementation(project(":engines:melotts"))
    testImplementation(project(":engines:piper"))
    testImplementation(project(":engines:kittentts"))
    testImplementation(project(":engines:kokoro"))
}

tasks.test {
    useJUnitPlatform()
}
