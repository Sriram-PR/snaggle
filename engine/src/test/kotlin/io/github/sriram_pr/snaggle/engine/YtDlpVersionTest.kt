package io.github.sriram_pr.snaggle.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YtDlpVersionTest {
    @Test fun laterDateIsNewer() = assertTrue(YtDlpVersion.isNewer("2026.09.01", "2026.08.19"))
    @Test fun nightlyIsNewerThanSameDayStable() = assertTrue(YtDlpVersion.isNewer("2026.08.19.120000", "2026.08.19"))
    @Test fun nightlyBeforeNextStable() = assertTrue(YtDlpVersion.isNewer("2026.09.30", "2026.09.27.232945"))
    @Test fun equalIsNotNewer() = assertFalse(YtDlpVersion.isNewer("2026.08.19", "2026.08.19"))
    @Test fun numericNotLexicographic() = assertTrue(YtDlpVersion.isNewer("2026.10.01", "2026.9.30"))
}
