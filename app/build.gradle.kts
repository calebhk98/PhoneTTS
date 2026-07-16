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
    }

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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.documentfile) // Storage Access Framework tree access for sideloading
    implementation(libs.pdfbox.android) // PDF text extraction (the one file type that needs a library)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // ONNX Runtime for the Tier-A/B engines (a second, LLM-style runtime is added for CosyVoice2).
    implementation(libs.onnxruntime.android)
}
