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

// :app is the Android application module (Compose UI, AudioTrack, ONNX engines).
// It requires the Android SDK to configure/build, so it is intentionally left out of
// the default build until the SDK is available. Uncomment locally / in CI to enable it:
// include(":app")
