plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release CI passes -Psnaggle.version=MAJOR.MINOR.PATCH from the tag; local builds are 0.0.0-dev.
val appVersion: String? = providers.gradleProperty("snaggle.version").orNull
val appVersionCode: Int = appVersion?.let { v ->
    val (major, minor, patch) = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)""").matchEntire(v)) {
        "snaggle.version must be MAJOR.MINOR.PATCH, got '$v'"
    }.destructured.toList().map(String::toInt)
    require(minor < 1000 && patch < 1000) { "snaggle.version minor/patch must be < 1000, got '$v'" }
    major * 1_000_000 + minor * 1_000 + patch
} ?: 1

android {
    namespace = "io.github.sriram_pr.snaggle"
    compileSdk = 37
    buildToolsVersion = "36.1.0" // installed version; SDK auto-download is off

    defaultConfig {
        applicationId = "io.github.sriram_pr.snaggle"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion ?: "0.0.0-dev"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("x64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
    }

    // CI's release job sets these; without them the release build stays unsigned.
    val keystore = providers.environmentVariable("SNAGGLE_KEYSTORE").orNull
    if (keystore != null) {
        signingConfigs.create("release") {
            storeFile = file(keystore)
            storePassword = providers.environmentVariable("SNAGGLE_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("SNAGGLE_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("SNAGGLE_KEY_PASSWORD").get()
            enableV3Signing = true // allows key rotation later
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    buildFeatures { compose = true; buildConfig = true }

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
    implementation(libs.compose.icons)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore)
}

// THIRD_PARTY_NOTICES.md lives at the repo root; the APK carries a copy (MIT/Apache/LGPL require it).
abstract class CopyNotices : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val source: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(source)
            into(outputDir.get().asFile.resolve("licenses"))
        }
    }
}

val copyNotices = tasks.register<CopyNotices>("copyNotices") {
    source = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    outputDir = layout.buildDirectory.dir("generated/notices-assets")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyNotices, CopyNotices::outputDir)
    }
}
