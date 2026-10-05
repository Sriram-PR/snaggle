plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.placeholder.app"
    compileSdk = 36
    buildToolsVersion = "36.1.0" // installed version; SDK auto-download is off

    defaultConfig {
        applicationId = "dev.placeholder.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-spike"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Extract .so files to nativeLibraryDir so ffmpeg/QuickJS (shipped as lib*.so) can be exec'd.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":engine-chaquopy"))
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
}
