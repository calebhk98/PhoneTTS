rootProject.name = "PhoneTTS"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// :core is a pure Kotlin/JVM module — all the deterministic "seam" logic lives here
// and its tests run on any JVM with no Android SDK required.
include(":core")

// One module per model engine. Each depends only on :core's generic seams and is discovered
// at runtime via ServiceLoader — so this list is the ONLY place the set of built-in engines is
// enumerated, and it is build config, not logic (no shared code branches on which model it is).
// Adding a model = a new module + a line here; removing one = deleting both. Nothing else changes.
include(":engines:common") // shared, model-agnostic engine plumbing the engine modules link against
include(":engines:cosyvoice2")
include(":engines:melotts")
include(":engines:piper")
include(":engines:kittentts")
include(":engines:kokoro")

// Cross-module integration tests (pure JVM) — the one place all engines share a classpath.
include(":integration")

// :app is the Android application module (Compose UI, AudioTrack, ONNX engines).
// It requires the Android SDK to configure/build, so it is intentionally left out of
// the default build until the SDK is available. Uncomment locally / in CI to enable it:
// include(":app")
