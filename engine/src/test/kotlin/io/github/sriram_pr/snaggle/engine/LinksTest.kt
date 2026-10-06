package io.github.sriram_pr.snaggle.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinksTest {
    @Test fun extractsFirstLinkFromText() =
        assertEquals("https://youtu.be/abc", Links.extract("Check this https://youtu.be/abc, and that https://x.com/1"))

    @Test fun noLink() = assertNull(Links.extract("nothing here"))

    @Test fun stripsTrackingParams() =
        assertEquals("https://youtu.be/abc?t=42", Links.normalize("https://youtu.be/abc?si=XYZ&t=42&utm_source=share"))

    @Test fun dropsEmptyQuery() =
        assertEquals("https://www.instagram.com/reel/Dd9/", Links.normalize("https://www.instagram.com/reel/Dd9/?igsh=abc"))

    @Test fun keepsFragment() =
        assertEquals("https://example.com/v?id=1#x", Links.normalize("https://example.com/v?id=1&fbclid=z#x"))

    @Test fun keepsEncodingOfOtherParams() =
        assertEquals("https://example.com/v?q=a%20b", Links.normalize("https://example.com/v?q=a%20b&ref=home"))

    @Test fun youtubeVideoDropsPlaylistContext() = assertEquals(
        "https://www.youtube.com/watch?v=aqz-KE-bpKQ&t=10",
        Links.normalize("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PLx&index=3&t=10&pp=iAQB&start_radio=1"),
    )

    @Test fun youtuBeDropsPlaylistContext() =
        assertEquals("https://youtu.be/aqz-KE-bpKQ", Links.normalize("https://youtu.be/aqz-KE-bpKQ?list=PLx&si=abc"))

    @Test fun mobileAndMusicHosts() {
        assertEquals("https://m.youtube.com/watch?v=a", Links.normalize("https://m.youtube.com/watch?v=a&list=PLx"))
        assertEquals("https://music.youtube.com/watch?v=a", Links.normalize("https://music.youtube.com/watch?v=a&list=RDx"))
    }

    @Test fun youtubePlaylistLinkKeepsList() = assertEquals(
        "https://www.youtube.com/playlist?list=PLx",
        Links.normalize("https://www.youtube.com/playlist?list=PLx&si=abc"),
    )

    @Test fun otherSitesKeepList() =
        assertEquals("https://example.com/watch?v=a&list=1", Links.normalize("https://example.com/watch?v=a&list=1"))
}
