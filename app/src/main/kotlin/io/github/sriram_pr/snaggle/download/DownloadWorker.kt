package io.github.sriram_pr.snaggle.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.snaggle

/** Android 10–13 fallback for [UidtJobService]. */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getString(DownloadScheduler.EXTRA_JOB_ID) ?: return Result.failure()
        val id = Notifications.progressId(jobId)
        val info = { n: android.app.Notification -> ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) }
        setForeground(info(Notifications.progress(applicationContext, jobId, applicationContext.getString(R.string.app_name), null, applicationContext.getString(R.string.status_starting))))
        val outcome = DownloadRunner(applicationContext.snaggle).run(jobId) { n -> setForeground(info(n)) }
        return if (outcome == DownloadRunner.Outcome.RESCHEDULE) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val jobId = inputData.getString(DownloadScheduler.EXTRA_JOB_ID).orEmpty()
        return ForegroundInfo(
            Notifications.progressId(jobId),
            Notifications.progress(applicationContext, jobId, applicationContext.getString(R.string.app_name), null, applicationContext.getString(R.string.status_starting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }
}
