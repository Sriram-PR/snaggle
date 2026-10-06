package io.github.sriram_pr.snaggle.engine

/**
 * Flags a download whose average speed over the last [windowMs] stayed below [minBytesPerSec].
 * Only time spent downloading counts: extraction and post-processing (see [pause]) don't.
 * Synchronized: progress and the periodic check usually arrive on different threads.
 */
class SlowDownloadDetector(private val windowMs: Long, private val minBytesPerSec: Long) {
    private var total = 0L
    private var fileBytes = 0L
    private var downloading = false
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** [bytes] is the current file's downloaded size; it restarts near 0 for each new file. */
    @Synchronized fun onProgress(bytes: Long) {
        total += if (bytes >= fileBytes) bytes - fileBytes else bytes
        fileBytes = bytes
        downloading = true
    }

    /** Not downloading any more (post-processing); the window starts over with the next progress. */
    @Synchronized fun pause() {
        downloading = false
        fileBytes = 0
        samples.clear()
    }

    /** Records a sample at [now]; true once a full window has been too slow. */
    @Synchronized fun check(now: Long): Boolean {
        if (!downloading) return false
        samples.addLast(now to total)
        // Keep the newest sample that is at least a window old as the baseline.
        while (samples.size > 1 && samples[1].first <= now - windowMs) samples.removeFirst()
        val (since, bytesThen) = samples.first()
        val elapsed = now - since
        if (elapsed < windowMs) return false
        return (total - bytesThen) * 1000 / elapsed < minBytesPerSec
    }
}
