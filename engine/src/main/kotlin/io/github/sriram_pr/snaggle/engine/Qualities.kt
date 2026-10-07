package io.github.sriram_pr.snaggle.engine

import kotlin.math.roundToInt

enum class VideoCodec(val pattern: String) {
    // Sites name codecs differently: YouTube "avc1.64001F"/"vp9"/"av01.0.08M.08", TikTok "h264".
    H264("^(avc|h264)"),
    VP9("^vp0?9"),
    AV1("^av01"),
}

/** One row of "More qualities". Video rows always come with AAC audio; MP4 output throughout. */
data class Quality(
    val kind: Kind,
    val height: Int? = null,
    val fps: Int? = null,
    /** Null for a single file whose codecs the site doesn't report. */
    val codec: VideoCodec? = null,
    /** Audio rows: the low-bitrate AAC instead of the best one. */
    val small: Boolean = false,
    val sizeBytes: Long? = null,
    /** Audio rows: AAC bitrate. */
    val kbps: Int? = null,
) {
    enum class Kind { VIDEO, VIDEO_ONLY, AUDIO }

    val playsEverywhere get() = codec == VideoCodec.H264

    /**
     * yt-dlp format selector and merge/remux container. Filters rather than format ids, so a retry
     * that extracts again still finds the same quality; each step falls back to a looser match.
     */
    fun selector(language: String?): Pair<String, String?> {
        val lang = language?.let { "[language=$it]" }
        val audio = listOfNotNull(lang?.let { "$AAC$NO_DRC$it" }, "$AAC$NO_DRC", AAC)
        return when (kind) {
            Kind.AUDIO -> audio.joinToString("/") { (if (small) "wa" else "ba") + it } to null
            Kind.VIDEO_ONLY -> "bv${videoFilter()}" to "mp4"
            Kind.VIDEO -> if (codec == null) "b[height=$height][ext=mp4]" to "mp4" else {
                val v = videoFilter()
                (audio.map { "bv*$v+ba$it" } + "b$v$AAC").joinToString("/") to "mp4"
            }
        }
    }

    private fun videoFilter() = "[vcodec~='${codec!!.pattern}'][height=$height]$SDR"

    companion object {
        internal const val AAC = "[acodec~='^(mp4a|aac)']"
        internal const val NO_DRC = "[format_id!*=drc]"
        internal const val SDR = "[dynamic_range!*=?HDR]"
    }
}

data class QualityOptions(
    val video: List<Quality>,
    val videoOnly: List<Quality>,
    val audio: List<Quality>,
    /** Audio languages, only when there is more than one; the original first. */
    val languages: List<String>,
    val defaultLanguage: String?,
    /** What the plain Video button would get: the best H.264 up to 1080p. */
    val default: Quality?,
)

object Qualities {
    private const val COMPATIBLE_MAX_HEIGHT = 1080

    fun from(formats: List<MediaFormat>, hardwareAv1: Boolean): QualityOptions {
        val sdrVideo = formats.filter { it.vcodec != NONE && (it.height ?: 0) > 0 && it.dynamicRange?.contains("HDR") != true }
        val aacAudio = formats.filter { it.vcodec == NONE && it.acodec.isCodec(AAC) && "drc" !in it.id }
        val bestAudio = aacAudio.maxByOrNull { it.abr ?: 0.0 }

        fun rows(kind: Quality.Kind) = sdrVideo.mapNotNull { it.height }.distinct().sortedDescending().mapNotNull { height ->
            val atHeight = sdrVideo.filter { it.height == height }
            known(kind, height, atHeight, bestAudio, hardwareAv1) ?: if (kind == Quality.Kind.VIDEO) unknown(height, atHeight) else null
        }

        val video = rows(Quality.Kind.VIDEO)
        val videoOnly = rows(Quality.Kind.VIDEO_ONLY)

        val audio = buildList {
            bestAudio?.let { add(Quality(Quality.Kind.AUDIO, sizeBytes = it.sizeBytes, kbps = it.abr?.roundToInt())) }
            val smallest = aacAudio.minByOrNull { it.abr ?: Double.MAX_VALUE }
            if (bestAudio != null && smallest != null && (smallest.abr ?: 0.0) < (bestAudio.abr ?: 0.0) * 0.75) {
                add(Quality(Quality.Kind.AUDIO, small = true, sizeBytes = smallest.sizeBytes, kbps = smallest.abr?.roundToInt()))
            }
        }

        val languages = aacAudio.filter { it.language != null }
            .sortedWith(compareByDescending<MediaFormat> { it.languagePreference ?: Int.MIN_VALUE }.thenBy { it.language })
            .mapNotNull { it.language }.distinct()
            .takeIf { it.size > 1 }.orEmpty()

        return QualityOptions(
            video = video,
            videoOnly = videoOnly,
            audio = audio,
            languages = languages,
            defaultLanguage = languages.firstOrNull(),
            default = video.firstOrNull { it.codec == VideoCodec.H264 && (it.height ?: 0) <= COMPATIBLE_MAX_HEIGHT }
                ?: video.firstOrNull { (it.height ?: 0) <= COMPATIBLE_MAX_HEIGHT } ?: video.firstOrNull(),
        )
    }

    /** A row from streams with known codecs: separate video + AAC (best on YouTube), else one H.264+AAC file. */
    private fun known(kind: Quality.Kind, height: Int, atHeight: List<MediaFormat>, bestAudio: MediaFormat?, hardwareAv1: Boolean): Quality? {
        val usable = atHeight.filter { it.acodec == NONE || it.acodec.isCodec(AAC) }
        val codec = pickCodec(height, usable, hardwareAv1) ?: return null
        val candidates = usable.filter { it.vcodec.isCodec(codec.pattern) }
        val videoOnly = candidates.filter { it.acodec == NONE }.maxByOrNull { it.sizeBytes ?: 0 }
        val stream = when (kind) {
            Quality.Kind.VIDEO_ONLY -> videoOnly
            else -> videoOnly?.takeIf { bestAudio != null } ?: candidates.filter { it.acodec.isCodec(AAC) }.maxByOrNull { it.sizeBytes ?: 0 }
        } ?: return null
        val audioSize = if (kind == Quality.Kind.VIDEO && stream.acodec == NONE) bestAudio?.sizeBytes else 0L
        return Quality(
            kind = kind,
            height = height,
            fps = candidates.mapNotNull { it.fps }.maxOrNull()?.toInt(),
            codec = codec,
            sizeBytes = stream.sizeBytes?.let { s -> audioSize?.let { s + it } },
        )
    }

    /** A single MP4 whose codecs the site doesn't report (X, Reddit…); usually H.264 + AAC but unverified. */
    private fun unknown(height: Int, atHeight: List<MediaFormat>): Quality? {
        val file = atHeight.filter { it.vcodec == null && it.acodec == null && it.ext == "mp4" }.maxByOrNull { it.sizeBytes ?: 0 } ?: return null
        return Quality(Quality.Kind.VIDEO, height = height, fps = file.fps?.toInt(), sizeBytes = file.sizeBytes)
    }

    /** H.264 where it exists up to 1080p; above that AV1 only with a hardware decoder, else VP9. */
    private fun pickCodec(height: Int, streams: List<MediaFormat>, hardwareAv1: Boolean): VideoCodec? {
        fun has(codec: VideoCodec) = streams.any { it.vcodec.isCodec(codec.pattern) }
        return when {
            height <= COMPATIBLE_MAX_HEIGHT && has(VideoCodec.H264) -> VideoCodec.H264
            hardwareAv1 && has(VideoCodec.AV1) -> VideoCodec.AV1
            has(VideoCodec.VP9) -> VideoCodec.VP9
            has(VideoCodec.H264) -> VideoCodec.H264
            else -> null
        }
    }

    private const val AAC = "^(mp4a|aac)"
    /** yt-dlp's "no such track"; null means the site didn't say. */
    private const val NONE = "none"
    private fun String?.isCodec(pattern: String) = this != null && Regex(pattern).containsMatchIn(this)
}
