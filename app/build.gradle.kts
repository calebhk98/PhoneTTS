// Android application module. This is NOT in the default build (settings.gradle.kts keeps
// include(":app") commented out) until an Android SDK is available. It hosts the platform
// side of the app: Compose UI, the AudioTrack sink, ONNX/LLM runtimes, concrete engines, the
// SharedPreferences-backed OverrideStore, and the downloader. All model facts still come from
// :core descriptors — never hardcoded here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
