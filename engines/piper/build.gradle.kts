import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A single engine module. It depends ONLY on :core's generic seams (VoiceEngine, the
// Runtime/InferenceSession/Tensor inference seam, Phonemizer, EngineProvider). It knows
// nothing about any other model, and nothing outside this module references it — deleting
// this directory + its settings include removes the model cleanly (spec §1.1.6).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engines:common"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
}
