import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared, model-AGNOSTIC engine plumbing (base class + helpers + a tiny JSON reader) that the
// engine modules link against. Depends only on :core's seams. It names no model and branches on
// no engine id - deleting any engine module still requires zero change here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    `java-test-fixtures`
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core")))

    // Shared engine-test scaffolding (EngineContext/FakeRuntime builders, inspect()/provider
    // assertion helpers) every engine module's test suite reuses via
    // testImplementation(testFixtures(project(":engines:common"))).
    testFixturesImplementation(project(":core"))
    testFixturesImplementation(testFixtures(project(":core")))
    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(kotlin("test"))
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
