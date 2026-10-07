package io.github.sriram_pr.snaggle.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.sriram_pr.snaggle.R

object Notifications {
    private const val CHANNEL_PROGRESS = "progress"
    private const val CHANNEL_RESULTS = "results"
    private const val CHANNEL_ENGINE = "engine"
    private const val ENGINE_UPDATED_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.channel_progress), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ENGINE, context.getString(R.string.channel_engine), NotificationManager.IMPORTANCE_LOW),
        )
    }

    // The foreground/job notification and the result notification need different ids: the
    // system removes the former when the job ends.
    fun progressId(jobId: String) = jobId.hashCode()
    private fun resultId(jobId: String) = jobId.hashCode() + 1

    fun progress(context: Context, jobId: String, title: String, fraction: Float?, status: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_cancel), CancelReceiver.pendingIntent(context, jobId))
            .build()

    fun showSaved(context: Context, jobId: String, title: String, uri: Uri, mime: String) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val share = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(context, resultId(jobId), view, flags)
        val sharePi = PendingIntent.getActivity(context, resultId(jobId) + 1, share, flags)
        post(
            context, resultId(jobId),
            NotificationCompat.Builder(context, CHANNEL_RESULTS)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.status_saved))
                .setContentIntent(openPi)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.action_open), openPi)
                .addAction(0, context.getString(R.string.action_share), sharePi)
                .build(),
        )
    }

    fun showFailed(context: Context, jobId: String, title: String, message: String, offerLatestEngine: Boolean) {
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.status_failed))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$message"))
            .setAutoCancel(true)
        if (offerLatestEngine) {
            builder.addAction(0, context.getString(R.string.action_try_latest_engine), TryNightlyReceiver.pendingIntent(context, jobId))
        }
        post(context, resultId(jobId), builder.build())
    }

    fun dismissResult(context: Context, jobId: String) = NotificationManagerCompat.from(context).cancel(resultId(jobId))

    fun showEngineUpdated(context: Context, version: String) {
        post(
            context, ENGINE_UPDATED_ID,
            NotificationCompat.Builder(context, CHANNEL_ENGINE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.engine_updated))
                .setContentText(context.getString(R.string.engine_updated_body, version))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(id, notification)
        }
    }
}
