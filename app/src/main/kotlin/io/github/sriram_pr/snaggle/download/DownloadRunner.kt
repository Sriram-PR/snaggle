package io.github.sriram_pr.snaggle.download

import android.app.Notification
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.SnaggleApp
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.data.NightlyRetry
import io.github.sriram_pr.snaggle.data.NightlyState
import io.github.sriram_pr.snaggle.engine.DownloadEvent
import io.github.sriram_pr.snaggle.engine.DownloadRequest
import io.github.sriram_pr.snaggle.engine.EngineError
import io.github.sriram_pr.snaggle.engine.SlowDownloadDetector
import io.github.sriram_pr.snaggle.engine.UpdateResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs one queued job end to end: engine download into the cache, copy into MediaStore,
 * job state in Room, notifications. Platform wrappers (UIDT job / WorkManager worker) only
 * provide the process lifetime and the foreground notification slot.
 */
class DownloadRunner(private val app: SnaggleApp) {
    enum class Outcome { DONE, FAILED, CANCELLED, RESCHEDULE }

    private class StalledException : Exception()
    private class TooSlowException : Exception()

    suspend fun run(jobId: String, showProgress: suspend (Notification) -> Unit): Outcome {
        val dao = app.db.jobs()
        val job = dao.get(jobId) ?: return Outcome.FAILED
        if (job.status == JobStatus.DONE || job.status == JobStatus.CANCELLED) return Outcome.DONE
        val label = job.title ?: job.url
        showProgress(Notifications.progress(app, jobId, label, null, app.getString(R.string.status_waiting)))

        active[jobId] = currentCoroutineContext()[Job]!!
        try {
            return slots.withPermit { execute(jobId, label, showProgress) }
        } catch (e: CancellationException) {
            return withContext(NonCancellable) {
                if (userCancelled.remove(jobId)) {
                    dao.setStatus(jobId, JobStatus.CANCELLED)
                    workDir(app, jobId).deleteRecursively()
                    Outcome.CANCELLED
                } else {
                    // Stopped by the system (constraints, memory): leave it queued; .part files resume.
                    dao.setStatus(jobId, JobStatus.QUEUED)
                    Outcome.RESCHEDULE
                }
            }
        } finally {
            active.remove(jobId)
        }
    }

    private sealed interface Attempt {
        data object Done : Attempt
        data class Failed(val error: EngineError) : Attempt
        data object Stalled : Attempt
        data object TooSlow : Attempt
    }

    /** Failures that usually pass on their own (YouTube's occasional 403, a network blip, a stall). */
    private fun Attempt.isTransient() =
        this is Attempt.Stalled || (this is Attempt.Failed && (error == EngineError.Forbidden || error == EngineError.Network))

    private suspend fun execute(jobId: String, label: String, showProgress: suspend (Notification) -> Unit): Outcome {
        val dao = app.db.jobs()
        val job = dao.get(jobId)!!
        dao.setStatus(jobId, JobStatus.RUNNING)
        showProgress(Notifications.progress(app, jobId, label, null, app.getString(R.string.status_starting)))
        // An update check never blocks the download: network trouble here is ignored.
        runCatching { EngineUpdates.checkIfDue(app) }
        if (job.nightly == NightlyState.REQUESTED) {
            dao.setNightly(jobId, NightlyState.TRIED)
            showProgress(Notifications.progress(app, jobId, label, null, app.getString(R.string.status_updating_engine)))
            runCatching { EngineUpdates.tryNightly(app) }
        }

        val title = arrayOf(job.title ?: label)
        val log = ArrayDeque<String>()
        var attempt = attempt(job, title, log, showProgress)
        if (attempt.isTransient()) {
            showProgress(Notifications.progress(app, jobId, title[0], null, app.getString(R.string.status_retrying)))
            delay(RETRY_DELAY_MS)
            attempt = attempt(job, title, log, showProgress, fresh = attempt is Attempt.Stalled)
        }
        // YouTube's 403s often clear within half a minute; one more, slower try for those only.
        if (attempt is Attempt.Failed && attempt.error == EngineError.Forbidden) {
            showProgress(Notifications.progress(app, jobId, title[0], null, app.getString(R.string.status_retrying_forbidden)))
            delay(FORBIDDEN_RETRY_DELAY_MS)
            attempt = attempt(job, title, log, showProgress)
        }
        if (attempt is Attempt.Failed && attempt.error.isSiteChange() && dao.get(jobId)?.nightly == NightlyState.NONE &&
            app.settings.current().nightlyRetry == NightlyRetry.AUTO
        ) {
            dao.setNightly(jobId, NightlyState.TRIED)
            showProgress(Notifications.progress(app, jobId, title[0], null, app.getString(R.string.status_updating_engine)))
            val update = runCatching { EngineUpdates.tryNightly(app) }.getOrNull()
            // Only worth retrying if we actually moved to a newer yt-dlp.
            if (update is UpdateResult.Updated) attempt = attempt(job, title, log, showProgress)
        }

        return when (attempt) {
            Attempt.Done -> Outcome.DONE
            is Attempt.Failed -> {
                val siteChange = attempt.error.isSiteChange()
                fail(job, title[0], attempt.error.userMessage(app), log, siteChange, offerNightly = siteChange && dao.get(jobId)?.nightly == NightlyState.NONE)
                Outcome.FAILED
            }
            Attempt.Stalled -> {
                fail(job, title[0], app.getString(R.string.error_stalled), log, siteChange = false, offerNightly = false)
                Outcome.FAILED
            }
            Attempt.TooSlow -> {
                fail(job, title[0], app.getString(R.string.error_too_slow), log, siteChange = false, offerNightly = false)
                Outcome.FAILED
            }
        }
    }

    private suspend fun fail(job: JobEntity, title: String, message: String, log: Collection<String>, siteChange: Boolean, offerNightly: Boolean) {
        app.db.jobs().setFailed(job.id, message, siteChange, DebugReport.build(app, job, message, log))
        Notifications.showFailed(app, job.id, title, message, offerNightly)
    }

    /**
     * One engine download of [job]; on success the file is in MediaStore and the job is DONE.
     * [fresh] uses its own engine id and directory: after a stall the old Python call may still be
     * unwinding, still writing its files and holding a cancel flag for its id.
     */
    private suspend fun attempt(
        job: JobEntity,
        title: Array<String>,
        log: ArrayDeque<String>,
        showProgress: suspend (Notification) -> Unit,
        fresh: Boolean = false,
    ): Attempt {
        val dao = app.db.jobs()
        val jobId = job.id
        val workDir = (if (fresh) File(workDir(app, jobId), "retry") else workDir(app, jobId)).apply { mkdirs() }
        val engineId = if (fresh) "$jobId/retry" else jobId
        var lastEvent = System.currentTimeMillis()
        var lastUi = 0L
        val speed = SlowDownloadDetector(SLOW_WINDOW_MS, SLOW_BYTES_PER_SEC)
        var result: Attempt = Attempt.Failed(EngineError.Unknown("Engine finished without a result"))
        try {
            coroutineScope {
                val watchdog = launch {
                    while (true) {
                        delay(10_000)
                        val now = System.currentTimeMillis()
                        val stop = when {
                            now - lastEvent > STALL_MS -> StalledException()
                            speed.check(now) -> TooSlowException()
                            else -> null
                        }
                        if (stop != null) {
                            // Like other failures, keep the partial files so Retry resumes them.
                            app.readyEngine().cancel(engineId, keepFiles = true)
                            throw stop
                        }
                    }
                }
                app.readyEngine().download(DownloadRequest(engineId, job.url, job.mode, workDir, job.format, job.merge)).collect { event ->
                    lastEvent = System.currentTimeMillis()
                    when (event) {
                        is DownloadEvent.Started -> {
                            title[0] = event.info.title.ifBlank { title[0] }
                            dao.setProgress(jobId, null, title[0])
                            showProgress(Notifications.progress(app, jobId, title[0], null, app.getString(R.string.status_starting)))
                        }
                        is DownloadEvent.Progress -> {
                            speed.onProgress(event.bytes)
                            if (lastEvent - lastUi > UI_INTERVAL_MS) {
                                lastUi = lastEvent
                                dao.setProgress(jobId, event.fraction, null)
                                val pct = event.fraction?.let { "${(it * 100).toInt()}%" } ?: app.getString(R.string.status_starting)
                                showProgress(Notifications.progress(app, jobId, title[0], event.fraction, pct))
                            }
                        }
                        DownloadEvent.PostProcessing -> {
                            speed.pause()
                            showProgress(Notifications.progress(app, jobId, title[0], null, app.getString(R.string.status_finishing)))
                        }
                        is DownloadEvent.Done -> {
                            title[0] = event.info.title.ifBlank { title[0] }
                            val saved = MediaStoreSaver.save(app, event.file)
                            dao.setDone(jobId, title[0], saved.uri.toString(), saved.mimeType)
                            workDir(app, jobId).deleteRecursively()
                            Notifications.showSaved(app, jobId, title[0], saved.uri, saved.mimeType)
                            result = Attempt.Done
                        }
                        is DownloadEvent.Failed -> result = Attempt.Failed(event.error)
                        // Only a user cancel unwinds as cancellation; anything else is an engine failure.
                        DownloadEvent.Cancelled -> if (jobId in userCancelled) {
                            throw CancellationException("Cancelled in engine")
                        } else {
                            result = Attempt.Failed(EngineError.Unknown("Download stopped unexpectedly"))
                        }
                        is DownloadEvent.Log -> {
                            if (log.size == DebugReport.LOG_LINES) log.removeFirst()
                            log.addLast("${event.level}: ${event.message.take(DebugReport.LINE_CHARS)}")
                        }
                    }
                }
                watchdog.cancel()
            }
        } catch (e: StalledException) {
            result = Attempt.Stalled
        } catch (e: TooSlowException) {
            result = Attempt.TooSlow
        }
        return result
    }

    companion object {
        private const val STALL_MS = 120_000L
        private const val SLOW_WINDOW_MS = 5 * 60_000L
        private const val SLOW_BYTES_PER_SEC = 20 * 1024L
        private const val RETRY_DELAY_MS = 10_000L
        private const val FORBIDDEN_RETRY_DELAY_MS = 30_000L
        private const val UI_INTERVAL_MS = 500L

        // At most two downloads at once; the rest wait with a "waiting" notification.
        private val slots = Semaphore(2)
        private val active = ConcurrentHashMap<String, Job>()
        private val userCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Scratch directory for one job; kept after failures so a retry can resume `.part` files. */
        fun workDir(app: SnaggleApp, jobId: String) = File(app.cacheDir, "downloads/$jobId")

        /** Returns true if the job was running in this process and is now being cancelled. */
        fun cancel(jobId: String): Boolean {
            val job = active[jobId] ?: return false
            userCancelled += jobId
            job.cancel()
            return true
        }
    }
}
