import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "dev.placeholder.engine.chaquopy"
    compileSdk = 36
    buildToolsVersion = "36.1.0" // installed version; SDK auto-download is off
    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }
    // One flavor per ABI (Python 3.12+ on Chaquopy is 64-bit only): per-ABI APKs, and
    // per-ABI pip requirements, since curl_cffi only has an arm64 Android wheel.
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
        create("x64") { dimension = "abi"; ndk { abiFilters += "x86_64" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val checkNativeBinaries = tasks.register("checkNativeBinaries") {
    val required = listOf("arm64-v8a", "x86_64").flatMap { abi ->
        listOf("libffmpeg.so", "libffprobe.so", "libqjs.so").map { rootProject.file("native/out/$abi/$it") }
    }
    doLast {
        val missing = required.filterNot { it.exists() }
        check(missing.isEmpty()) { "Missing native binaries ${missing.map { it.name }.distinct()}; run native/ffmpeg/build.sh and native/quickjs/build.sh" }
    }
}
tasks.named("preBuild") { dependsOn(checkNativeBinaries) }

chaquopy {
    defaultConfig {
        // Build Python must match; ./gw puts the `untitled` conda env's python3.13 on PATH.
        version = "3.13"
        pip {
            // Chaquopy runs pip isolated (PIP_* env ignored), so keep its cache in the project explicitly.
            options("--cache-dir", rootProject.file(".tools/pip-cache").path)
            install("certifi")
            install("mutagen")
        }
    }
    productFlavors {
        getByName("arm64") {
            pip {
                // Browser impersonation for yt-dlp (TikTok etc.). cffi built by native/cffi/build.sh.
                install(rootProject.file("native/out/wheels/cffi-2.1.1-cp313-cp313-android_24_arm64_v8a.whl").path)
                install("curl_cffi==0.16.3")
            }
        }
    }
}

dependencies {
    api(project(":engine"))
    implementation(libs.coroutines.android)
}

// Baseline yt-dlp shipped in the APK so first launch works offline; runtime updates replace it.
abstract class FetchYtDlp : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val url = "https://github.com/yt-dlp/yt-dlp/releases/download/${version.get()}/yt-dlp"
        val bytes = URI(url).toURL().readBytes()
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "yt-dlp checksum mismatch for $url: $actual" }
        val dir = outputDir.get().asFile.resolve("ytdlp").apply { mkdirs() }
        dir.resolve("yt-dlp.zip").writeBytes(bytes)
        dir.resolve("version.txt").writeText(version.get())
    }
}

val fetchYtDlp = tasks.register<FetchYtDlp>("fetchYtDlp") {
    version = "2026.08.19"
    sha256 = "1fa6733c37ea6fb51c99ad8fe785e7b7e5f3246c9b980230329d4fb72ed8d4d6"
    outputDir = layout.buildDirectory.dir("generated/ytdlp-assets")
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchYtDlp, FetchYtDlp::outputDir)
        // ffmpeg/ffprobe/qjs built by native/*/build.sh, shipped as lib*.so executables.
        variant.sources.jniLibs?.addStaticSourceDirectory(rootProject.file("native/out").path)
    }
}
