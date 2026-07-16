// Android application module. This is NOT in the default build (settings.gradle.kts keeps
// include(":app") commented out) until an Android SDK is available. It hosts the platform
// side of the app: Compose UI, the AudioTrack sink, ONNX/LLM runtimes, concrete engines, the
// SharedPreferences-backed OverrideStore, and the downloader. All model facts still come from
// :core descriptors — never hardcoded here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose) // Kotlin 2.0+ Compose compiler plugin
}

// The espeak-ng JNI bridge needs the NDK + CMake, which aren't present in every build
// environment (core-only CI, or an SDK install without the NDK). Gate the native build on an
// explicit opt-in so `:app` still assembles everywhere; when it's off, EspeakNative simply fails
// to loadLibrary and EspeakPhonemizer falls back to PassthroughPhonemizer (logged, never crashes).
// Real device builds pass -PwithEspeak=true after running scripts/fetch-espeak-ng.sh.
val buildEspeak = (project.findProperty("withEspeak") as String?)?.toBooleanStrictOrNull() ?: false

// The CosyVoice2 speech-token LLM bridge (spec §5.3 second runtime) links llama.cpp/ggml and is
// gated the exact same way as espeak: opt-in via -PwithCosyVoice=true after running
// scripts/fetch-cosyvoice-llama.sh. When off, libphonetts_cosyvoice.so isn't built,
// LlamaCppSpeechTokenRuntime.isAvailable() is false, and CosyVoice2 simply isn't offered — the app
// still assembles everywhere. See docs/COSYVOICE2.md.
val buildCosyVoice = (project.findProperty("withCosyVoice") as String?)?.toBooleanStrictOrNull() ?: false

// Either native bridge pulls in the NDK + CMake externalNativeBuild; configure it if either is on.
val buildNative = buildEspeak || buildCosyVoice

android {
    namespace = "com.phonetts.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonetts"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Ship only the ABIs real target devices use (the Galaxy A16 is arm64-v8a) — keeps the
        // APK from carrying ONNX Runtime's x86 native libs that no phone needs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Native JNI bridges (docs/espeak-ng-integration.md, docs/COSYVOICE2.md). Only configured
        // when at least one is opted in (NDK + CMake present); each CMake half then links whether or
        // not its source tree has been fetched (see the stub guards in the CMakeLists files). A
        // per-bridge define tells the top CMakeLists which half(s) to build.
        if (buildNative) {
            externalNativeBuild {
                cmake {
                    // c++_shared keeps one copy of the STL shared across native libs in the APK
                    // instead of statically duplicating it into each libphonetts_*.so.
                    arguments += "-DANDROID_STL=c++_shared"
                    if (buildEspeak) arguments += "-DPHONETTS_BUILD_ESPEAK=ON"
                    if (buildCosyVoice) arguments += "-DPHONETTS_BUILD_COSYVOICE=ON"
                }
            }
        }
    }

    // NDK version is intentionally left to the installed default rather than pinned here: this
    // module was authored without a local NDK to test against (no Android SDK in this dev
    // environment). Any NDK new enough for CMake 3.22.1 (r23+) should work; pin this once a real
    // build has been run and the resolved version is known-good.

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            // Pre-empt duplicate-file collisions between ONNX Runtime, PDFBox and Compose AARs.
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.kotlin_module", "/META-INF/DEPENDENCIES")
        }
    }

    buildTypes {
        release {
            // R8/shrinking is OFF until validated on-device: proguard-rules.pro already carries the
            // critical keeps (ServiceLoader EngineProviders, kotlinx.serialization) for when it's enabled.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Entry point for the native JNI bridge builds (see the defaultConfig block above and
    // src/main/cpp/CMakeLists.txt). version pins the CMake release Gradle downloads via the SDK
    // manager. Wired when either native bridge is opted in, so builds without the NDK still assemble.
    if (buildNative) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation(project(":core"))

    // The built-in engines. Present on the classpath = discovered by ServiceLoader at startup.
    // This is the aggregation point; no code here names or branches on any specific model.
    runtimeOnly(project(":engines:cosyvoice2"))
    runtimeOnly(project(":engines:melotts"))
    runtimeOnly(project(":engines:piper"))
    runtimeOnly(project(":engines:kittentts"))
    runtimeOnly(project(":engines:kokoro"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json) // HfCatalog exposes a kotlinx.serialization Json default
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile) // Storage Access Framework tree access for sideloading
    // MediaSessionCompat + AudioFocusRequestCompat/MediaStyle notifications for background
    // playback + lock-screen controls (PlaybackService); androidx.media3 isn't on the classpath
    // yet, so this lighter media-compat artifact is the pick per the ticket's guidance.
    implementation(libs.androidx.media)
    implementation(libs.pdfbox.android) // PDF text extraction (the one file type that needs a library)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ONNX Runtime for the Tier-A/B engines (a second, LLM-style runtime is added for CosyVoice2).
    implementation(libs.onnxruntime.android)
}
