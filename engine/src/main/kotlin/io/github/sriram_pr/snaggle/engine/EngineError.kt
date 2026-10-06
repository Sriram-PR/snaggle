package io.github.sriram_pr.snaggle.engine

sealed interface EngineError {
    data object BotCheck : EngineError
    data object LoginRequired : EngineError
    data object Unavailable : EngineError
    data object FormatUnavailable : EngineError
    data object Forbidden : EngineError
    data object RateLimited : EngineError
    data object Network : EngineError
    data object JsRuntimeMissing : EngineError
    data object Unsupported : EngineError
    data class Unknown(val raw: String) : EngineError

    companion object {
        // Order matters: more specific patterns first. Matched against yt-dlp's error text.
        private val rules: List<Pair<Regex, EngineError>> = listOf(
            "confirm you.re not a bot" to BotCheck,
            "no supported javascript runtime" to JsRuntimeMissing,
            "requested format is not available" to FormatUnavailable,
            "login required|rate-limit reached|private video|members-only|sign in to" to LoginRequired,
            "video unavailable|this video is not available|is not available in your country" to Unavailable,
            "http error 403" to Forbidden,
            "http error 429|too many requests" to RateLimited,
            "unsupported url" to Unsupported,
            "timed out|connection (refused|reset)|network is unreachable|name or service not known|no address associated|failed to resolve" to Network,
        ).map { (pattern, error) -> Regex(pattern, RegexOption.IGNORE_CASE) to error }

        fun classify(raw: String): EngineError =
            rules.firstOrNull { (regex, _) -> regex.containsMatchIn(raw) }?.second ?: Unknown(raw)
    }
}

class EngineException(val error: EngineError, cause: Throwable? = null) : Exception(error.toString(), cause)
