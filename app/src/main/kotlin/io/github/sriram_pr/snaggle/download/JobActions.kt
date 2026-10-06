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
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.util.UUID

/** User-facing operations on jobs, shared by the share sheet, the history screen and notifications. */
object JobActions {
    suspend fun start(context: Context, url: String, mode: Mode): String {
        val id = UUID.randomUUID().toString()
        context.snaggle.db.jobs().insert(JobEntity(id = id, url = url, mode = mode, status = JobStatus.QUEUED))
        DownloadScheduler.enqueue(context, id)
        return id
    }

    /** A finished download of the same link and mode whose file still exists, if any. */
    suspend fun findExisting(context: Context, url: String, mode: Mode): JobEntity? =
        context.snaggle.db.jobs().findDone(url, mode)?.takeIf { exists(context, it) }

    suspend fun retry(context: Context, job: JobEntity) {
        context.snaggle.db.jobs().requeue(job.id)
        DownloadScheduler.enqueue(context, job.id)
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
        /** Android needs the user's consent (we no longer own the file, e.g. after a reinstall). */
        data class NeedsConsent(val request: PendingIntent) : DeleteResult
    }

    suspend fun delete(context: Context, job: JobEntity): DeleteResult {
        if (job.status == JobStatus.RUNNING || job.status == JobStatus.QUEUED) cancel(context, job.id)
        val uri = job.outputUri?.let(Uri::parse)
        // A file already removed outside the app makes MediaStore throw (SecurityException on Android 16,
        // then "Invalid Uri" for a consent request), so only touch files that still exist.
        if (uri != null && withContext(Dispatchers.IO) { exists(context, job) }) {
            try {
                withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) }
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        return DeleteResult.NeedsConsent(MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)))
                    } catch (e: IllegalArgumentException) {
                        // Gone between the check and the request; nothing left to delete.
                    }
                }
                // Android 10 has no consent dialog for this; the file stays in the gallery.
            }
        }
        forget(context, job.id)
        return DeleteResult.Deleted
    }

    suspend fun forget(context: Context, jobId: String) {
        DownloadRunner.workDir(context.snaggle, jobId).deleteRecursively()
        context.snaggle.db.jobs().delete(jobId)
    }

    fun exists(context: Context, job: JobEntity): Boolean {
        val uri = job.outputUri?.let(Uri::parse) ?: return false
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (e: FileNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
