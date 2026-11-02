plugins { id("com.android.application") }

android {
    namespace = "dev.quiet.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.quiet.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 14
        versionName = "0.8.1"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
