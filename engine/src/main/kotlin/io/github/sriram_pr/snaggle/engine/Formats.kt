package io.github.sriram_pr.snaggle.engine

/** yt-dlp format selectors that avoid transcoding: H.264 + AAC only, so ffmpeg just remuxes. */
object Formats {
    // Sites name codecs differently: YouTube "avc1.64001F"/"mp4a.40.2", TikTok "h264"/"aac".
    private const val H264 = "[vcodec~='^(avc|h264)']"
    private const val AAC = "[acodec~='^(mp4a|aac)']"

    /**
     * Audio when no stream is known to be AAC (X reports none): an audio-only M4A/MP4, else the
     * single MP4 file. The engine then copies the AAC track into .m4a; other codecs fail cleanly.
     */
    const val AUDIO_FALLBACK = "ba[ext=m4a]/ba[ext=mp4]/b[ext=mp4]"

    private fun video(maxHeight: Int) =
        "bv*$H264[height<=$maxHeight]+ba$AAC" +
            "/b$H264$AAC[height<=$maxHeight]" +
            "/bv*$H264+ba$AAC" +
            "/b$H264$AAC" +
            "/b[ext=mp4]"

    /** Returns the format selector and the merge container (null when no merge happens). */
    fun forMode(mode: Mode): Pair<String, String?> = when (mode) {
        Mode.VIDEO_COMPAT -> video(1080) to "mp4"
        Mode.VIDEO_720 -> video(720) to "mp4"
        Mode.AUDIO -> "ba$AAC/$AUDIO_FALLBACK" to null
    }
}
