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

// :core is a pure Kotlin/JVM module - all the deterministic "seam" logic lives here
// and its tests run on any JVM with no Android SDK required.
include(":core")

// One module per model engine. Each depends only on :core's generic seams and is discovered
// at runtime via ServiceLoader - so this list is the ONLY place the set of built-in engines is
// enumerated, and it is build config, not logic (no shared code branches on which model it is).
// Adding a model = a new module + a line here; removing one = deleting both. Nothing else changes.
include(":engines:common") // shared, model-agnostic engine plumbing the engine modules link against
include(":engines:cosyvoice2")
include(":engines:melotts")
include(":engines:piper")
include(":engines:kittentts")
include(":engines:kokoro")
include(":engines:mms")
include(":engines:f5tts")
include(":engines:ggmltts")
include(":engines:executorch")
include(":engines:pytorch")

// Cross-module integration tests (pure JVM) - the one place all engines share a classpath.
include(":integration")

// :app is the Android application module (Compose UI, AudioTrack, ONNX engines). It requires the
// Android SDK, so it is included ONLY when an SDK is present - the pure-JVM modules above still
// build and test with no SDK (e.g. core-only CI), while a machine with the SDK gets the app too.
// See docs/BUILDING.md. Force-disable with -PskipApp=true.
val androidSdkAvailable =
    System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }
if (androidSdkAvailable && !settings.providers.gradleProperty("skipApp").isPresent) {
    include(":app")
}
