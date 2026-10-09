package io.github.sriram_pr.snaggle.download

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.data.NightlyState
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID

/** User-facing operations on jobs, shared by the share sheet, the history screen and notifications. */
object JobActions {
    /** A "More qualities" choice; null means the [Mode]'s default. */
    data class Choice(val format: String, val merge: String?, val label: String)

    suspend fun start(context: Context, url: String, mode: Mode, choice: Choice? = null): String {
        val id = UUID.randomUUID().toString()
        context.snaggle.db.jobs().insert(
            JobEntity(
                id = id, url = url, mode = mode, status = JobStatus.QUEUED,
                format = choice?.format, merge = choice?.merge, qualityLabel = choice?.label,
            ),
        )
        DownloadScheduler.enqueue(context, id)
        return id
    }

    /** A finished download of the same link, mode and quality whose file still exists, if any. */
    suspend fun findExisting(context: Context, url: String, mode: Mode, format: String? = null): JobEntity? =
        context.snaggle.db.jobs().findDone(url, mode, format)?.takeIf { fileState(context, it) != FileState.MISSING }

    suspend fun retry(context: Context, job: JobEntity) {
        context.snaggle.db.jobs().requeue(job.id)
        DownloadScheduler.enqueue(context, job.id)
    }

    /** "Try latest engine": the next run of this job installs the newest nightly yt-dlp first. */
    suspend fun tryLatestEngine(context: Context, jobId: String) {
        val dao = context.snaggle.db.jobs()
        dao.setNightly(jobId, NightlyState.REQUESTED)
        dao.requeue(jobId)
        Notifications.dismissResult(context, jobId)
        DownloadScheduler.enqueue(context, jobId)
    }

    suspend fun cancel(context: Context, jobId: String) {
        if (DownloadRunner.cancel(jobId)) return
        // Not running in this process yet: drop it from the scheduler and mark it cancelled.
        DownloadScheduler.unschedule(context, jobId)
        context.snaggle.db.jobs().setStatus(jobId, JobStatus.CANCELLED)
        DownloadRunner.workDir(context.snaggle, jobId).deleteRecursively()
    }

    fun open(context: Context, job: JobEntity): Boolean {
        val uri = job.outputUri?.let(Uri::parse) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, job.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun share(context: Context, job: JobEntity) {
        val uri = job.outputUri?.let(Uri::parse) ?: return
        val send = Intent(Intent.ACTION_SEND).setType(job.mimeType).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        /** Android needs the user's consent for [jobIds]' files (we no longer own them, e.g. after a reinstall). */
        data class NeedsConsent(val request: PendingIntent, val jobIds: List<String>) : DeleteResult
    }

    suspend fun delete(context: Context, job: JobEntity): DeleteResult = deleteAll(context, listOf(job), deleteFiles = true)

    /**
     * Removes [jobs] from history and, with [deleteFiles], their files too. Files we no longer own are
     * collected into one consent request; their entries stay until the user agrees.
     */
    suspend fun deleteAll(context: Context, jobs: List<JobEntity>, deleteFiles: Boolean): DeleteResult {
        val needConsent = mutableListOf<Pair<String, Uri>>()
        for (job in jobs) {
            if (job.status == JobStatus.RUNNING || job.status == JobStatus.QUEUED) cancel(context, job.id)
            val uri = job.outputUri?.let(Uri::parse)
            // A file already removed outside the app makes MediaStore throw (SecurityException on Android 16,
            // then "Invalid Uri" for a consent request), so only touch files that still exist.
            if (deleteFiles && uri != null && withContext(Dispatchers.IO) { fileState(context, job) } != FileState.MISSING) {
                try {
                    withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) }
                } catch (e: SecurityException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        needConsent += job.id to uri
                        continue
                    }
                    // Android 10 has no consent dialog for this; the file stays in the gallery.
                }
            }
            forget(context, job.id)
        }
        if (needConsent.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return DeleteResult.Deleted
        return try {
            DeleteResult.NeedsConsent(MediaStore.createDeleteRequest(context.contentResolver, needConsent.map { it.second }), needConsent.map { it.first })
        } catch (e: IllegalArgumentException) {
            // Gone between the check and the request; nothing left to delete.
            needConsent.forEach { forget(context, it.first) }
            DeleteResult.Deleted
        }
    }

    suspend fun forget(context: Context, jobId: String) {
        DownloadRunner.workDir(context.snaggle, jobId).deleteRecursively()
        context.snaggle.db.jobs().delete(jobId)
    }

    enum class FileState {
        AVAILABLE,
        /** Still on the device but no longer Snaggle's (e.g. after a reinstall): deleting needs consent. */
        NOT_OURS,
        MISSING,
    }

    // MediaStore on Android 16: a removed item throws FileNotFoundException ("No item at …"),
    // someone else's item throws SecurityException ("has no access").
    fun fileState(context: Context, job: JobEntity): FileState {
        val uri = job.outputUri?.let(Uri::parse) ?: return FileState.MISSING
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { FileState.AVAILABLE } ?: FileState.MISSING
        } catch (e: FileNotFoundException) {
            FileState.MISSING
        } catch (e: SecurityException) {
            FileState.NOT_OURS
        }
    }
}
