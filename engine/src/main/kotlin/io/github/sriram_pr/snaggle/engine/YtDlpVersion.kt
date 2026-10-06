package io.github.sriram_pr.snaggle.engine

/** yt-dlp versions are dotted dates: stable "2026.08.19", nightly "2026.09.27.232945". */
object YtDlpVersion : Comparator<String> {
    private fun parts(v: String) = v.split('.').map { it.toIntOrNull() ?: 0 }

    override fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = pa.getOrElse(i) { 0 }.compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    fun isNewer(candidate: String, than: String) = compare(candidate, than) > 0
}
