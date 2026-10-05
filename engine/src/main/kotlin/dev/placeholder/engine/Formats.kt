package dev.placeholder.engine

/** yt-dlp format selectors that avoid transcoding: H.264 + AAC only, so ffmpeg just remuxes. */
object Formats {
    private fun video(maxHeight: Int) =
        "bv*[vcodec^=avc1][height<=$maxHeight]+ba[acodec^=mp4a]" +
            "/b[vcodec^=avc1][acodec^=mp4a][height<=$maxHeight]" +
            "/bv*[vcodec^=avc1]+ba[acodec^=mp4a]" +
            "/b[ext=mp4]"

    /** Returns the format selector and the merge container (null when no merge happens). */
    fun forMode(mode: Mode): Pair<String, String?> = when (mode) {
        Mode.VIDEO_COMPAT -> video(1080) to "mp4"
        Mode.VIDEO_720 -> video(720) to "mp4"
        Mode.AUDIO -> "ba[acodec^=mp4a]/ba[ext=m4a]" to null
    }
}
