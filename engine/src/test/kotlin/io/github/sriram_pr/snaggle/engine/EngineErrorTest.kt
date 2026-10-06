package io.github.sriram_pr.snaggle.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class EngineErrorTest {
    private fun assertClass(expected: EngineError, raw: String) = assertEquals(expected, EngineError.classify(raw))

    @Test fun botCheck() = assertClass(EngineError.BotCheck, "ERROR: [youtube] abc: Sign in to confirm you’re not a bot. Use --cookies")
    @Test fun jsRuntime() = assertClass(EngineError.JsRuntimeMissing, "WARNING: [youtube] No supported JavaScript runtime could be found")
    @Test fun format() = assertClass(EngineError.FormatUnavailable, "ERROR: [youtube] abc: Requested format is not available.")
    @Test fun privateVideo() = assertClass(EngineError.LoginRequired, "ERROR: [youtube] abc: Private video. Sign in if you've been granted access")
    @Test fun instagram() = assertClass(EngineError.LoginRequired, "ERROR: [Instagram] xyz: Requested content is not available, rate-limit reached or login required.")
    @Test fun unavailable() = assertClass(EngineError.Unavailable, "ERROR: [youtube] abc: Video unavailable")
    @Test fun forbidden() = assertClass(EngineError.Forbidden, "ERROR: unable to download video data: HTTP Error 403: Forbidden")
    @Test fun rateLimited() = assertClass(EngineError.RateLimited, "ERROR: HTTP Error 429: Too Many Requests")
    @Test fun unsupported() = assertClass(EngineError.Unsupported, "ERROR: Unsupported URL: https://example.com/")
    @Test fun network() = assertClass(EngineError.Network, "ERROR: Unable to download webpage: <urlopen error [Errno 7] No address associated with hostname>")
    @Test fun unknown() = assertClass(EngineError.Unknown("boom"), "boom")
}
