package io.github.sriram_pr.snaggle.download

import android.app.Notification
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.SnaggleApp
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.engine.DownloadEvent
import io.github.sriram_pr.snaggle.engine.DownloadRequest
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

    private suspend fun execute(jobId: String, label: String, showProgress: suspend (Notification) -> Unit): Outcome {
        val dao = app.db.jobs()
        val job = dao.get(jobId)!!
        dao.setStatus(jobId, JobStatus.RUNNING)
        showProgress(Notifications.progress(app, jobId, label, null, app.getString(R.string.status_starting)))
        val engine = app.readyEngine()
        val workDir = File(app.cacheDir, "downloads/$jobId").apply { mkdirs() }

        var title = label
        var lastEvent = System.currentTimeMillis()
        var lastUi = 0L
        var outcome = Outcome.FAILED
        try {
            coroutineScope {
                val watchdog = launch {
                    while (true) {
                        delay(10_000)
                        if (System.currentTimeMillis() - lastEvent > STALL_MS) throw StalledException()
                    }
                }
                engine.download(DownloadRequest(jobId, job.url, job.mode, workDir)).collect { event ->
                    lastEvent = System.currentTimeMillis()
                    when (event) {
                        is DownloadEvent.Started -> {
                            title = event.info.title.ifBlank { label }
                            dao.setProgress(jobId, null, title)
                            showProgress(Notifications.progress(app, jobId, title, null, app.getString(R.string.status_starting)))
                        }
                        is DownloadEvent.Progress -> if (lastEvent - lastUi > UI_INTERVAL_MS) {
                            lastUi = lastEvent
                            dao.setProgress(jobId, event.fraction, null)
                            val pct = event.fraction?.let { "${(it * 100).toInt()}%" } ?: app.getString(R.string.status_starting)
                            showProgress(Notifications.progress(app, jobId, title, event.fraction, pct))
                        }
                        DownloadEvent.PostProcessing ->
                            showProgress(Notifications.progress(app, jobId, title, null, app.getString(R.string.status_finishing)))
                        is DownloadEvent.Done -> {
                            title = event.info.title.ifBlank { label }
                            val saved = MediaStoreSaver.save(app, event.file)
                            dao.setDone(jobId, title, saved.uri.toString(), saved.mimeType)
                            workDir.deleteRecursively()
                            Notifications.showSaved(app, jobId, title, saved.uri, saved.mimeType)
                            outcome = Outcome.DONE
                        }
                        is DownloadEvent.Failed -> {
                            val message = event.error.userMessage(app)
                            dao.setFailed(jobId, message)
                            Notifications.showFailed(app, jobId, title, message)
                            outcome = Outcome.FAILED
                        }
                        DownloadEvent.Cancelled -> throw CancellationException("Cancelled in engine")
                        is DownloadEvent.Log -> Unit
                    }
                }
                watchdog.cancel()
            }
        } catch (e: StalledException) {
            val message = app.getString(R.string.error_stalled)
            dao.setFailed(jobId, message)
            Notifications.showFailed(app, jobId, title, message)
            outcome = Outcome.FAILED
        }
        return outcome
    }

    companion object {
        private const val STALL_MS = 120_000L
        private const val UI_INTERVAL_MS = 500L

        // At most two downloads at once; the rest wait with a "waiting" notification.
        private val slots = Semaphore(2)
        private val active = ConcurrentHashMap<String, Job>()
        private val userCancelled: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** Returns true if the job was running in this process and is now being cancelled. */
        fun cancel(jobId: String): Boolean {
            val job = active[jobId] ?: return false
            userCancelled += jobId
            job.cancel()
            return true
        }
    }
}
