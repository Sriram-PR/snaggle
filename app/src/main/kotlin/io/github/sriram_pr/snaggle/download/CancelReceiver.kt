package io.github.sriram_pr.snaggle.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the notification's Cancel action. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(DownloadScheduler.EXTRA_JOB_ID) ?: return
        if (DownloadRunner.cancel(jobId)) return
        // Not running in this process yet: drop it from the scheduler and mark it cancelled.
        DownloadScheduler.unschedule(context, jobId)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            context.snaggle.db.jobs().setStatus(jobId, JobStatus.CANCELLED)
            pending.finish()
        }
    }

    companion object {
        fun pendingIntent(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, jobId.hashCode(),
            Intent(context, CancelReceiver::class.java).putExtra(DownloadScheduler.EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
