plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI provides these only for trusted main-branch builds. PR builds keep the
// disposable debug key and must never receive the permanent signing key.
val quietKeystorePath = System.getenv("QUIET_KEYSTORE_FILE")

android {
    namespace = "dev.quiet.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.quiet.launcher"
        minSdk = 28
        // Target Samsung/modern Android arm64 so the bundled neural runtime stays smaller.
        ndk { abiFilters += listOf("arm64-v8a") }
        targetSdk = 35
        versionCode = System.getenv("QUIET_VERSION_CODE")?.toIntOrNull() ?: 28
        versionName = "0.9.6"
    }

    if (!quietKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("quietPersistent") {
                storeFile = file(quietKeystorePath)
                storePassword = System.getenv("QUIET_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QUIET_KEY_ALIAS")
                keyPassword = System.getenv("QUIET_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (!quietKeystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("quietPersistent")
            }
        }
        getByName("release") { isMinifyEnabled = false }
    }

    androidResources { noCompress += listOf("onnx", "bin") }

    kotlinOptions { jvmTarget = "17" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// scripts/install_local_voice.sh downloads this verified native AAR before Gradle runs.
dependencies {
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    // The Android sherpa-onnx AAR is implemented in Kotlin, even when called from Java.
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    // Free, actual on-device llama.cpp inference with GGUF. No API/keys.
    implementation("dev.ffmpegkit-maintained:llama-android:0.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
