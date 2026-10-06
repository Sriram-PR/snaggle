package io.github.sriram_pr.snaggle.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles the notification's Cancel action. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(DownloadScheduler.EXTRA_JOB_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            JobActions.cancel(context, jobId)
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
