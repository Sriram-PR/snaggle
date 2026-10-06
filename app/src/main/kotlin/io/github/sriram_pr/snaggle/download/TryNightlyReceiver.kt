package io.github.sriram_pr.snaggle.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The failure notification's "Try latest engine" action. Only marks and re-queues the job: the
 * download job itself installs the nightly, since a receiver gets just seconds to run.
 */
class TryNightlyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(DownloadScheduler.EXTRA_JOB_ID) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            JobActions.tryLatestEngine(context, jobId)
            pending.finish()
        }
    }

    companion object {
        fun pendingIntent(context: Context, jobId: String): PendingIntent = PendingIntent.getBroadcast(
            context, jobId.hashCode() + 7,
            Intent(context, TryNightlyReceiver::class.java).putExtra(DownloadScheduler.EXTRA_JOB_ID, jobId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
