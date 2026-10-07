package io.github.sriram_pr.snaggle.engine

import io.github.sriram_pr.snaggle.engine.Quality.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QualitiesTest {
    private fun video(id: String, vcodec: String?, height: Int, size: Long?, fps: Double? = 60.0, hdr: Boolean = false, acodec: String? = "none") =
        MediaFormat(id, "mp4", vcodec, acodec, height, fps, null, null, size, null, null, if (hdr) "HDR10" else "SDR")

    private fun audio(id: String, acodec: String, abr: Double, size: Long?, language: String? = null, preference: Int? = null) =
        MediaFormat(id, "m4a", "none", acodec, null, null, null, abr, size, language, preference, null)

    private val mb = 1_000_000L

    // Shaped like YouTube's "Big Buck Bunny 60fps 4K" (yt-dlp 2026.08.19, visionos client).
    private val youtube = listOf(
        video("160", "avc1.4d400c", 144, 4 * mb, 30.0), video("133", "avc1.4d4015", 240, 9 * mb, 30.0),
        video("134", "avc1.4d401e", 360, 18 * mb, 30.0), video("135", "avc1.4d401f", 480, 28 * mb, 30.0),
        video("298", "avc1.4d4020", 720, 151 * mb), video("299", "avc1.64002a", 1080, 258 * mb),
        video("302", "vp9", 720, 113 * mb), video("303", "vp9", 1080, 169 * mb),
        video("308", "vp9", 1440, 473 * mb), video("315", "vp9", 2160, 1362 * mb),
        video("337", "vp9.2", 2160, 2000 * mb, hdr = true),
        video("398", "av01.0.08M.08", 720, 71 * mb), video("399", "av01.0.09M.08", 1080, 124 * mb),
        video("400", "av01.0.12M.08", 1440, 313 * mb), video("401", "av01.0.13M.08", 2160, 712 * mb),
        audio("139", "mp4a.40.5", 48.8, 4 * mb), audio("140", "mp4a.40.2", 129.5, 10 * mb),
        audio("140-drc", "mp4a.40.2", 129.5, 10 * mb), audio("251", "opus", 128.6, 10 * mb),
    )

    @Test fun youtubeRowsUseH264UpTo1080AndVp9Above() {
        val o = Qualities.from(youtube, hardwareAv1 = false)
        assertEquals(listOf(2160, 1440, 1080, 720, 480, 360, 240, 144), o.video.map { it.height })
        assertEquals(listOf(VideoCodec.VP9, VideoCodec.VP9), o.video.take(2).map { it.codec })
        assertTrue(o.video.drop(2).all { it.codec == VideoCodec.H264 && it.playsEverywhere })
    }

    @Test fun hardwareAv1PrefersAv1AboveH264Range() {
        val o = Qualities.from(youtube, hardwareAv1 = true)
        assertEquals(listOf(VideoCodec.AV1, VideoCodec.AV1, VideoCodec.H264), o.video.take(3).map { it.codec })
    }

    @Test fun sizesAddTheAudio() {
        val o = Qualities.from(youtube, hardwareAv1 = false)
        assertEquals(268 * mb, o.video.first { it.height == 1080 }.sizeBytes)
        assertEquals(1372 * mb, o.video.first { it.height == 2160 }.sizeBytes)
        assertEquals(258 * mb, o.videoOnly.first { it.height == 1080 }.sizeBytes)
    }

    @Test fun defaultIsBestCompatible() {
        val d = Qualities.from(youtube, hardwareAv1 = false).default
        assertEquals(Kind.VIDEO, d?.kind)
        assertEquals(1080, d?.height)
        assertEquals(VideoCodec.H264, d?.codec)
    }

    @Test fun audioIsAacBestAndSmallWithoutDrcOrOpus() {
        val a = Qualities.from(youtube, hardwareAv1 = false).audio
        assertEquals(listOf(false, true), a.map { it.small })
        assertEquals(listOf(10 * mb, 4 * mb), a.map { it.sizeBytes })
        assertEquals(listOf(130, 49), a.map { it.kbps })
    }

    @Test fun hdrOnlyHeightIsSkipped() {
        val o = Qualities.from(listOf(video("337", "vp9.2", 2160, mb, hdr = true), video("299", "avc1", 1080, mb), audio("140", "mp4a.40.2", 129.5, mb)), hardwareAv1 = false)
        assertEquals(listOf(1080), o.video.map { it.height })
    }

    @Test fun softwareOnlyAv1HeightIsSkipped() {
        val o = Qualities.from(listOf(video("401", "av01.0.13M.08", 2160, mb), video("299", "avc1", 1080, mb), audio("140", "mp4a.40.2", 129.5, mb)), hardwareAv1 = false)
        assertEquals(listOf(1080), o.video.map { it.height })
    }

    @Test fun progressiveOnlySite() {
        // X/Twitter: single files with both tracks, no separate audio.
        val x = listOf(
            video("http-632", "avc1.4d401e", 360, 2 * mb, 30.0, acodec = "mp4a.40.2"),
            video("http-2176", "avc1.640020", 720, 6 * mb, 30.0, acodec = "mp4a.40.2"),
        )
        val o = Qualities.from(x, hardwareAv1 = false)
        assertEquals(listOf(720, 360), o.video.map { it.height })
        assertEquals(6 * mb, o.video.first().sizeBytes)
        assertTrue(o.videoOnly.isEmpty())
        assertTrue(o.audio.isEmpty())
    }

    @Test fun unknownCodecSingleFiles() {
        // X/Twitter as yt-dlp 2026.08.19 reports it: progressive MP4s with unknown codecs, HLS video-only H.264,
        // and HLS audio of unknown codec.
        val x = listOf(
            video("http-256", null, 270, 705_856, null, acodec = null),
            video("hls-268", "avc1.4D4015", 270, null, null),
            video("http-2176", null, 720, 5_999_776, null, acodec = null),
            video("hls-2254", "avc1.64001F", 720, null, null),
            MediaFormat("hls-audio-128000-Audio", "mp4", "none", null, null, null, 128.0, 128.0, null, null, null, null),
        )
        val o = Qualities.from(x, hardwareAv1 = false)
        assertEquals(listOf(720, 270), o.video.map { it.height })
        assertNull(o.video.first().codec)
        assertEquals(false, o.video.first().playsEverywhere)
        assertEquals(5_999_776L, o.video.first().sizeBytes)
        assertEquals("b[height=720][ext=mp4]" to "mp4", o.video.first().selector(null))
        assertEquals(listOf(720, 270), o.videoOnly.map { it.height })
        assertTrue(o.audio.isEmpty())
        assertEquals(720, o.default?.height)
    }

    @Test fun languagesOnlyWhenSeveral() {
        assertTrue(Qualities.from(youtube, hardwareAv1 = false).languages.isEmpty())
        val dubbed = youtube + listOf(
            audio("140-0", "mp4a.40.2", 129.5, 10 * mb, "es", -1),
            audio("140-1", "mp4a.40.2", 129.5, 10 * mb, "en", 10),
        )
        val o = Qualities.from(dubbed, hardwareAv1 = false)
        assertEquals(listOf("en", "es"), o.languages)
        assertEquals("en", o.defaultLanguage)
    }

    @Test fun selectors() {
        val o = Qualities.from(youtube, hardwareAv1 = false)
        val (v1080, merge) = o.video.first { it.height == 1080 }.selector(language = null)
        assertEquals("mp4", merge)
        assertTrue("[height=1080]" in v1080 && "avc|h264" in v1080 && v1080.startsWith("bv*"), v1080)
        assertTrue("[dynamic_range!*=?HDR]" in v1080, v1080)
        val (v4k, _) = o.video.first { it.height == 2160 }.selector(language = "es")
        assertTrue("^vp0?9" in v4k && "[language=es]" in v4k, v4k)
        val (only, onlyMerge) = o.videoOnly.first().selector(null)
        assertTrue(only.startsWith("bv[") && "+" !in only, only)
        assertEquals("mp4", onlyMerge)
        val (small, smallMerge) = o.audio.first { it.small }.selector(null)
        assertTrue(small.startsWith("wa["), small)
        assertNull(smallMerge)
    }
}
