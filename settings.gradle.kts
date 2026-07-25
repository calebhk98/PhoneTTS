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

// One module per model engine, DISCOVERED FROM DISK - never hand-listed. Every directory under
// engines/ that carries a build.gradle.kts is included automatically, so adding a model is
// literally "drop in engines/<name>/" and removing one is "delete the directory". No line here to
// forget, and (with the matching blocks in app/build.gradle.kts and integration/build.gradle.kts)
// no other file to edit either - which is the point of rule 5.
//
// This replaced a hand-maintained include() list. That list had already drifted: four engine
// modules (neutts, outetts, pockettts, supertonic) were included here but never added to the app's
// runtime classpath, so ServiceLoader never saw them and they silently did not exist in the
// shipped APK. Auto-discovery makes that class of mistake impossible rather than merely unlikely.
//
// :engines:common (shared, model-agnostic plumbing the engine modules link against) is picked up
// by the same rule; it is not itself an engine, and the consumers below exclude it by name.
file("engines")
    .listFiles { candidate -> candidate.isDirectory && File(candidate, "build.gradle.kts").isFile }
    ?.map { it.name }
    ?.sorted()
    ?.forEach { name -> include(":engines:$name") }

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
