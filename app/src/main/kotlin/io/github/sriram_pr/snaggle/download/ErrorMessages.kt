package io.github.sriram_pr.snaggle.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.format.Formatter
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.engine.EngineError

fun EngineError.userMessage(context: Context): String = if (this is EngineError.NoSpace) {
    context.getString(R.string.error_no_space, Formatter.formatShortFileSize(context, needed), Formatter.formatShortFileSize(context, free))
} else context.getString(
    when (this) {
        EngineError.BotCheck -> if (onVpn(context)) R.string.error_bot_check_vpn else R.string.error_bot_check
        EngineError.LoginRequired -> R.string.error_login_required
        EngineError.Unavailable -> R.string.error_unavailable
        EngineError.FormatUnavailable -> R.string.error_format_unavailable
        EngineError.Forbidden -> R.string.error_forbidden
        EngineError.RateLimited -> R.string.error_rate_limited
        EngineError.Network -> R.string.error_network
        EngineError.JsRuntimeMissing -> R.string.error_js_runtime
        EngineError.Unsupported -> R.string.error_unsupported
        is EngineError.NoSpace, is EngineError.Unknown -> R.string.error_unknown
    },
)

/** Checked when the error happens: YouTube's bot check often targets VPN addresses. */
private fun onVpn(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
}
