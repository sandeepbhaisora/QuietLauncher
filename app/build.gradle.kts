plugins { id("com.android.application") }

// CI provides these only for trusted main-branch builds. PR builds keep the
// disposable debug key and must never receive the permanent signing key.
val quietKeystorePath = System.getenv("QUIET_KEYSTORE_FILE")

android {
    namespace = "dev.quiet.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.quiet.launcher"
        minSdk = 28
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
