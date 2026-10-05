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
        // Python 3.12+ on Chaquopy is 64-bit only.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

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
    }
}
