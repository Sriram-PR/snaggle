package io.github.sriram_pr.snaggle.download

import android.content.Context
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.engine.EngineError

fun EngineError.userMessage(context: Context): String = context.getString(
    when (this) {
        EngineError.BotCheck -> R.string.error_bot_check
        EngineError.LoginRequired -> R.string.error_login_required
        EngineError.Unavailable -> R.string.error_unavailable
        EngineError.FormatUnavailable -> R.string.error_format_unavailable
        EngineError.Forbidden -> R.string.error_forbidden
        EngineError.RateLimited -> R.string.error_rate_limited
        EngineError.Network -> R.string.error_network
        EngineError.JsRuntimeMissing -> R.string.error_js_runtime
        EngineError.Unsupported -> R.string.error_unsupported
        is EngineError.Unknown -> R.string.error_unknown
    },
)
