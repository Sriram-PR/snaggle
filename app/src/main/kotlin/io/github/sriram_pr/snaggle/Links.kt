package io.github.sriram_pr.snaggle

import android.net.Uri

object Links {
    private val URL = Regex("https?://\\S+")
    // Share/tracking parameters that make the same video look like a different link.
    private val TRACKING = setOf("si", "feature", "igsh", "igshid", "is_from_webapp", "sender_device", "fbclid", "ref", "ref_src")

    /** First http(s) link in shared text, with tracking parameters removed. */
    fun extract(text: String): String? = URL.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')?.let(::normalize)

    fun normalize(url: String): String {
        val uri = Uri.parse(url)
        if (uri.isOpaque || uri.query == null) return url
        val keep = uri.queryParameterNames.filterNot { it in TRACKING || it.startsWith("utm_") }
        val builder = uri.buildUpon().clearQuery()
        for (name in keep) for (value in uri.getQueryParameters(name)) builder.appendQueryParameter(name, value)
        return builder.build().toString()
    }
}
