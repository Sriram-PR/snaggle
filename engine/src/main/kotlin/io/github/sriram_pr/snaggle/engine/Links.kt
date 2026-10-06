package io.github.sriram_pr.snaggle.engine

import java.net.URI

object Links {
    private val URL = Regex("https?://\\S+")
    // Share/tracking parameters that make the same video look like a different link.
    private val TRACKING = setOf("si", "feature", "igsh", "igshid", "is_from_webapp", "sender_device", "fbclid", "ref", "ref_src")
    // Playlist context on a single YouTube video; the video alone is what gets downloaded.
    private val YOUTUBE_PLAYLIST = setOf("list", "index", "pp", "start_radio")
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    /** First http(s) link in shared text, with tracking parameters removed. */
    fun extract(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')?.let(::normalize)

    fun normalize(url: String): String {
        val fragmentAt = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val queryAt = url.indexOf('?').takeIf { it in 0 until fragmentAt } ?: return url
        val params = url.substring(queryAt + 1, fragmentAt).split('&').filter { it.isNotEmpty() }
        val youtubeVideo = isYoutubeVideo(url.substring(0, queryAt), params)
        val keep = params.filterNot {
            val name = it.substringBefore('=')
            name in TRACKING || name.startsWith("utm_") || (youtubeVideo && name in YOUTUBE_PLAYLIST)
        }
        val query = if (keep.isEmpty()) "" else keep.joinToString("&", prefix = "?")
        return url.substring(0, queryAt) + query + url.substring(fragmentAt)
    }

    private fun isYoutubeVideo(base: String, params: List<String>): Boolean {
        val uri = runCatching { URI(base) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return host == "youtu.be" ||
            (host in YOUTUBE_HOSTS && uri.path == "/watch" && params.any { it.startsWith("v=") })
    }
}
