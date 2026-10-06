package io.github.sriram_pr.snaggle.engine

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlowDownloadDetectorTest {
    private val minute = 60_000L
    private fun detector() = SlowDownloadDetector(windowMs = 5 * minute, minBytesPerSec = 20 * 1024)

    /** Feeds a steady [bytesPerSec] for [minutes], checking every 10 s like the runner's watchdog. */
    private fun SlowDownloadDetector.run(fromMs: Long, minutes: Int, bytesPerSec: Long, startBytes: Long = 0): Pair<Boolean, Long> {
        var bytes = startBytes
        var slow = false
        for (t in 0..minutes * 6) {
            val now = fromMs + t * 10_000L
            onProgress(bytes)
            slow = slow || check(now)
            bytes += bytesPerSec * 10
        }
        return slow to bytes
    }

    @Test fun fastDownloadIsFine() = assertFalse(detector().run(0, 20, 500 * 1024L).first)

    @Test fun slowForTheWholeWindowIsSlow() = assertTrue(detector().run(0, 6, 5 * 1024L).first)

    @Test fun slowButShorterThanWindowIsFine() = assertFalse(detector().run(0, 4, 5 * 1024L).first)

    @Test fun burstThenStallIsSlow() {
        val d = detector()
        val (_, bytes) = d.run(0, 2, 1024 * 1024L)
        assertTrue(d.run(2 * minute, 6, 0, startBytes = bytes).first)
    }

    @Test fun nothingBeforeFirstProgress() {
        val d = detector()
        assertFalse((0..60).any { d.check(it * 10_000L) })
    }

    @Test fun pauseRestartsTheWindow() {
        val d = detector()
        d.run(0, 4, 5 * 1024L)
        d.pause()
        // Post-processing for a while: no progress, no verdict.
        assertFalse((0..30).any { d.check(4 * minute + it * 10_000L) })
        assertFalse(d.run(10 * minute, 4, 5 * 1024L).first)
    }

    @Test fun secondFileCountsFromZero() {
        val d = detector()
        val (_, videoBytes) = d.run(0, 3, 500 * 1024L)
        // The audio file's progress starts again at 0; it must not look like going backwards.
        assertFalse(d.run(3 * minute, 6, 500 * 1024L).first)
        assertTrue(videoBytes > 0)
    }
}
