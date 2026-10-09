package io.github.sriram_pr.snaggle.download

import android.content.Context
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.SnaggleApp
import io.github.sriram_pr.snaggle.engine.Channel
import io.github.sriram_pr.snaggle.engine.EngineError
import io.github.sriram_pr.snaggle.engine.UpdateResult

/** The yt-dlp update policy: scheduled stable checks, opt-in nightly retries. */
object EngineUpdates {
    /** Scheduled stable check: only when due per the user's frequency, and never waits for downloads. */
    suspend fun checkIfDue(app: SnaggleApp) {
        val settings = app.settings.current()
        val interval = settings.updateFrequency.intervalMs ?: return
        if (System.currentTimeMillis() - settings.lastEngineCheck < interval) return
        checkNow(app, waitForIdle = false)
    }

    /**
     * Installs the latest stable if newer than what's active (and not held). This is also what moves
     * Snaggle back from nightly once a stable release catches up.
     */
    suspend fun checkNow(app: SnaggleApp, waitForIdle: Boolean = true): UpdateResult {
        val result = app.readyEngine().updateYtDlp(Channel.STABLE, waitForIdle)
        if (result is UpdateResult.Updated || result is UpdateResult.UpToDate) {
            app.settings.setLastEngineCheck(System.currentTimeMillis())
        }
        announce(app, result)
        return result
    }

    /** Switches to the latest nightly, waiting for running downloads to finish first. */
    suspend fun tryNightly(app: SnaggleApp): UpdateResult =
        app.readyEngine().updateYtDlp(Channel.NIGHTLY, waitForIdle = true).also { announce(app, it) }

    private suspend fun announce(app: SnaggleApp, result: UpdateResult) {
        if (result is UpdateResult.Updated && app.settings.current().notifyEngineUpdates) {
            Notifications.showEngineUpdated(app, displayVersion(result.to))
        }
    }

    /** "nightly-2026.09.27.232945" → "2026.09.27.232945 (nightly)". */
    fun displayVersion(id: String): String = "${id.substringAfter('-')} (${id.substringBefore('-')})"

    fun resultMessage(context: Context, result: UpdateResult): String = when (result) {
        is UpdateResult.Updated -> context.getString(R.string.result_updated, displayVersion(result.to))
        is UpdateResult.UpToDate -> context.getString(R.string.result_up_to_date, result.version)
        is UpdateResult.Failed -> context.getString(R.string.result_failed, result.reason)
        UpdateResult.Busy -> context.getString(R.string.result_busy)
    }
}

/**
 * Failures that usually mean the site changed and a newer yt-dlp might already handle it.
 * Not BotCheck: that's YouTube's verdict on the IP/session, relayed verbatim by yt-dlp.
 */
fun EngineError.isSiteChange(): Boolean = when (this) {
    EngineError.Unavailable, EngineError.FormatUnavailable,
    EngineError.Forbidden, EngineError.JsRuntimeMissing, is EngineError.Unknown -> true
    EngineError.BotCheck, EngineError.LoginRequired, EngineError.RateLimited, EngineError.Network,
    EngineError.Unsupported, is EngineError.NoSpace -> false
}
