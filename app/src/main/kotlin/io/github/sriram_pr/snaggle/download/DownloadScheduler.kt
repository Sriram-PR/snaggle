package io.github.sriram_pr.snaggle.download

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Android 14+: user-initiated data transfer jobs (Google's recommended API for user-requested
 * downloads: no fixed time cap, exempt from job quotas). Android 10–13: WorkManager with a
 * foreground worker, as the UIDT docs recommend for older versions.
 */
object DownloadScheduler {
    const val EXTRA_JOB_ID = "job_id"

    fun enqueue(context: Context, jobId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val network = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                // The builder asks for NOT_VPN by default, which left downloads waiting forever behind a VPN.
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val info = JobInfo.Builder(jobId.hashCode(), ComponentName(context, UidtJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetwork(network)
                .setExtras(PersistableBundle().apply { putString(EXTRA_JOB_ID, jobId) })
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(info)
        } else {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(EXTRA_JOB_ID to jobId))
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(jobId, ExistingWorkPolicy.KEEP, request)
        }
    }

    /** Drops a job that hasn't started running yet. */
    fun unschedule(context: Context, jobId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.getSystemService(JobScheduler::class.java).cancel(jobId.hashCode())
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(jobId)
        }
    }
}
