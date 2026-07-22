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

// The CosyVoice native ggml TTS bridge (spec §5.3 second runtime) links CrispASR's cosyvoice3_tts
// (Qwen2 LLM + flow + HiFT + BPE, all ggml) and is gated the exact same way as espeak: opt-in via
// -PwithCosyVoice=true after running scripts/fetch-cosyvoice-ggml.sh. When off,
// libphonetts_cosyvoice.so isn't built, NativeCosyVoiceRuntime.isAvailable() is false, and CosyVoice
// simply isn't offered — the app still assembles everywhere. See docs/COSYVOICE2.md.
val buildCosyVoice = (project.findProperty("withCosyVoice") as String?)?.toBooleanStrictOrNull() ?: false

// Either native bridge pulls in the NDK + CMake externalNativeBuild; configure it if either is on.
val buildNative = buildEspeak || buildCosyVoice

// Auto-versioning: the patch number is the count of MERGES to main, so every merged PR bumps the
// version by exactly one (0.1.2 -> 0.1.3 -> 0.1.4 …) with no manual edit. We count FIRST-PARENT
// commits, not every commit: a PR merge (or squash) adds exactly one first-parent commit to main,
// while the branch's own commits sit off the first-parent line and never touch the version. This is
// why heavy branch work no longer inflates the patch the way the old raw-commit count did.
// (Caveat: "rebase and merge" replays each branch commit onto main's first-parent line, so it counts
//  per-commit — prefer "Create a merge commit" or "Squash and merge".)
//
// [VERSION_BASE_MERGES] is the first-parent count at which patch 0 was anchored; patch = merges
// since then. Bump MAJOR/MINOR by editing VERSION_MAJOR_MINOR and re-anchoring VERSION_BASE_MERGES.
// Falls back to a safe default when git history isn't available (e.g. a source tarball); CI checks
// out full history (fetch-depth: 0) so the count is correct there.
val VERSION_MAJOR_MINOR = "0.1"
// Anchored to 2 so the first automated release (main's first-parent count reaches 4 once this lands)
// is 0.1.2 — clearing the already published 0.1.0/0.1.1. Keep in sync with `base=` in
// .github/workflows/android.yml.
val VERSION_BASE_MERGES = 2

fun mainMergeCount(): Int =
    runCatching {
        val process =
            ProcessBuilder("git", "rev-list", "--count", "--first-parent", "HEAD")
                .directory(rootDir)
                .redirectErrorStream(true)
                .start()
        process.inputStream.bufferedReader().readText().trim().toIntOrNull() ?: 0
    }.getOrDefault(0)

val mainMerges = mainMergeCount()
val patchNumber = (mainMerges - VERSION_BASE_MERGES).coerceAtLeast(0)
val autoVersionName = "$VERSION_MAJOR_MINOR.$patchNumber"
// versionCode encodes the version itself (major*1_000_000 + minor*1_000 + patch) rather than a raw
// count. That keeps it strictly increasing as the version rises AND well above the old raw-count
// codes (which were ≤ ~66), so upgrading over a previously sideloaded build never trips Android's
// "downgrade" block.
val versionParts = VERSION_MAJOR_MINOR.split(".")
val autoVersionCode =
    (versionParts[0].toInt() * 1_000_000 + versionParts[1].toInt() * 1_000 + patchNumber).coerceAtLeast(1)

android {
    namespace = "com.phonetts.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonetts"
        minSdk = 24
        targetSdk = 35
        versionCode = autoVersionCode
        versionName = autoVersionName

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

                    // BUG FIX (found building against a real espeak-ng 1.52.0 checkout): without
                    // this, AGP asks CMake/ninja to build every target its "all" graph can reach,
                    // not just the JNI libs we link into the APK. espeak-ng's own subdirectory
                    // brings in the `espeak-ng` CLI binary, its unit tests, AND a `data ALL`
                    // custom target (cmake/data.cmake) that *executes* the just-cross-compiled
                    // arm64 `espeak-ng-bin` on the host to generate phoneme/dict data -- which
                    // fails outright on an x86_64 build host (can't run an ARM binary). None of
                    // that is needed: the phoneme DATA this app uses is produced separately, on a
                    // host-native build (docs/espeak-ng-integration.md step 2), and shipped as an
                    // asset, not linked into the APK. Restricting the target list to only the
                    // JNI .so's we actually package sidesteps the whole broken cross-build data
                    // step.
                    val nativeTargets = mutableListOf<String>()
                    if (buildEspeak) nativeTargets += "phonetts_espeak"
                    if (buildCosyVoice) nativeTargets += "phonetts_cosyvoice"
                    targets(*nativeTargets.toTypedArray())
                }
            }
        }
    }

    // Pinned once a real build (issue #13) confirmed a known-good version: this is the NDK AGP
    // auto-selected and successfully compiled/linked the espeak-ng JNI bridge against (arm64-v8a +
    // armeabi-v7a) end to end. Pinning keeps CI and local builds deterministic instead of AGP
    // silently downloading whatever its own default happens to be. Any NDK r23+ works for CMake
    // 3.22.1 compatibility; bump deliberately if a future native bridge needs a newer one.
    ndkVersion = "27.0.12077973"

    buildFeatures {
        compose = true
        buildConfig = true // exposes BuildConfig.VERSION_NAME to the in-app update check
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

    signingConfigs {
        // A STABLE debug key committed at keystore/phonetts-debug.keystore, so every build — local or
        // CI, on any machine — signs with the SAME certificate. Without this, each CI runner
        // auto-generates its own throwaway ~/.android/debug.keystore, so consecutive released APKs had
        // DIFFERENT signatures and Android refused to update one over another ("App not installed" /
        // signatures-do-not-match), forcing a full uninstall+reinstall for every update. This is a
        // debug certificate with the well-known "android" password and grants no security, so
        // committing it is safe for a personal, sideloaded app. (A real Play release key would live in
        // a CI secret instead — see issue #51.)
        getByName("debug") {
            storeFile = rootProject.file("keystore/phonetts-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // The published APK is the DEBUG build (see .github/workflows/android.yml), so the stable
        // signingConfig above is what actually ships; it's applied to the debug type automatically.
        release {
            // R8/shrinking is OFF until validated on-device: proguard-rules.pro already carries the
            // critical keeps (ServiceLoader EngineProviders, kotlinx.serialization) for when it's enabled.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign release with the same stable key so a release APK is also installable over a debug
            // one during personal sideloading (CI ships debug today; this keeps them interchangeable).
            signingConfig = signingConfigs.getByName("debug")
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
    runtimeOnly(project(":engines:mms"))
    runtimeOnly(project(":engines:f5tts"))

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
