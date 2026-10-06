package io.github.sriram_pr.snaggle.download

import android.app.job.JobParameters
import android.app.job.JobService
import androidx.annotation.RequiresApi
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@RequiresApi(34)
class UidtJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Int, Job>()
    private val stopped: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    override fun onStartJob(params: JobParameters): Boolean {
        val jobId = params.extras.getString(DownloadScheduler.EXTRA_JOB_ID) ?: return false
        val id = Notifications.progressId(jobId)
        // UIDT jobs must post their notification promptly after starting.
        setNotification(
            params, id,
            Notifications.progress(this, jobId, getString(R.string.app_name), null, getString(R.string.status_starting)),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        running[params.jobId] = scope.launch {
            val outcome = DownloadRunner(snaggle).run(jobId) { n ->
                setNotification(params, id, n, JOB_END_NOTIFICATION_POLICY_REMOVE)
            }
            running.remove(params.jobId)
            // After onStopJob the system owns rescheduling; otherwise we must always finish the job,
            // asking for a reschedule if the runner left it queued.
            if (!stopped.remove(params.jobId)) jobFinished(params, outcome == DownloadRunner.Outcome.RESCHEDULE)
        }
        return true
    }

    // Stopped by the system: the runner marks the job QUEUED; returning true reschedules it.
    override fun onStopJob(params: JobParameters): Boolean {
        stopped += params.jobId
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
