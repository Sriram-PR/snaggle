package io.github.sriram_pr.snaggle.download

import android.os.Build
import io.github.sriram_pr.snaggle.BuildConfig
import io.github.sriram_pr.snaggle.SnaggleApp
import io.github.sriram_pr.snaggle.data.JobEntity

/** Plain-text report a user can paste into a bug report. */
object DebugReport {
    const val LOG_LINES = 50
    const val LINE_CHARS = 2000

    suspend fun build(app: SnaggleApp, job: JobEntity, message: String, log: Collection<String>): String {
        val v = runCatching { app.readyEngine().versions() }.getOrNull()
        return buildString {
            appendLine("Snaggle debug report")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR} ${BuildConfig.BUILD_TYPE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Engine: yt-dlp ${v?.ytDlp}, ejs ${v?.ejs}, Python ${v?.python}, QuickJS ${v?.quickJs}, ffmpeg ${v?.ffmpeg}")
            appendLine("Mode: ${job.mode}")
            appendLine("Link: ${job.url}")
            appendLine("Error: $message")
            appendLine()
            appendLine("Last ${log.size} log lines:")
            log.forEach(::appendLine)
        }
    }
}
