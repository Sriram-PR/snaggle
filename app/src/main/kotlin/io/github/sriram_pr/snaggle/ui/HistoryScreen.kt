package io.github.sriram_pr.snaggle.ui

import android.app.Activity
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sriram_pr.snaggle.Links
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.download.JobActions
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by remember { context.snaggle.db.jobs().observeAll() }.collectAsStateWithLifecycle(emptyList())
    var linkText by rememberSaveable { mutableStateOf("") }
    var sheetUrl by remember { mutableStateOf<String?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val id = pendingDeleteId
        pendingDeleteId = null
        if (result.resultCode == Activity.RESULT_OK && id != null) scope.launch { JobActions.forget(context, id) }
    }
    fun delete(job: JobEntity) = scope.launch {
        when (val r = JobActions.delete(context, job)) {
            JobActions.DeleteResult.Deleted -> Unit
            is JobActions.DeleteResult.NeedsConsent -> {
                pendingDeleteId = job.id
                deleteConsent.launch(IntentSenderRequest.Builder(r.request).build())
            }
        }
    }
    fun submit() {
        val url = Links.extract(linkText)
        if (url == null) Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show() else sheetUrl = url
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(contentPadding = padding) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        linkText, { linkText = it }, Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.link_hint)) },
                        singleLine = true,
                        trailingIcon = {
                            TextButton({
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let { linkText = it.toString() }
                            }) { Text(stringResource(R.string.action_paste)) }
                        },
                    )
                    Button(::submit, enabled = linkText.isNotBlank()) { Text(stringResource(R.string.action_download)) }
                }
            }
            if (jobs.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(jobs, key = { it.id }) { job ->
                JobRow(
                    job,
                    onOpen = { if (!JobActions.open(context, job)) Toast.makeText(context, R.string.open_failed, Toast.LENGTH_SHORT).show() },
                    onShare = { JobActions.share(context, job) },
                    onCancel = { scope.launch { JobActions.cancel(context, job.id) } },
                    onRetry = { scope.launch { JobActions.retry(context, job) } },
                    onDelete = { delete(job) },
                )
            }
        }
    }
    sheetUrl?.let { url ->
        DownloadSheet(url) {
            sheetUrl = null
            linkText = ""
        }
    }
}

@Composable
private fun JobRow(
    job: JobEntity,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Thumbnail from MediaStore for finished jobs; null bitmap + present=false means the file is gone.
    val thumb by produceState<Pair<Bitmap?, Boolean>?>(null, job.outputUri, job.status) {
        value = if (job.status != JobStatus.DONE || job.outputUri == null) null else withContext(Dispatchers.IO) {
            if (!JobActions.exists(context, job)) null to false
            else runCatching { context.contentResolver.loadThumbnail(Uri.parse(job.outputUri), Size(320, 180), null) }.getOrNull() to true
        }
    }
    val missing = thumb?.second == false
    Row(
        Modifier.fillMaxWidth().clickable(enabled = job.status == JobStatus.DONE && !missing, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 72.dp, height = 48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = thumb?.first
            when {
                bitmap != null -> Image(bitmap.asImageBitmap(), null, Modifier.size(width = 72.dp, height = 48.dp), contentScale = ContentScale.Crop)
                missing || job.status == JobStatus.FAILED -> Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                job.mode == Mode.AUDIO -> Text("♪", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(job.title ?: job.url, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            when {
                job.status == JobStatus.RUNNING -> {
                    val p = job.progress
                    if (p != null) LinearProgressIndicator({ p }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> Text(
                    statusText(job, missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == JobStatus.FAILED || missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        when (job.status) {
            JobStatus.QUEUED, JobStatus.RUNNING ->
                IconButton(onCancel) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) }
            JobStatus.DONE -> {
                if (!missing) IconButton(onShare) { Icon(Icons.Default.Share, stringResource(R.string.action_share)) }
                IconButton(onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
            }
            JobStatus.FAILED, JobStatus.CANCELLED -> {
                IconButton(onRetry) { Icon(Icons.Default.Refresh, stringResource(R.string.action_retry)) }
                IconButton(onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
            }
        }
    }
}

@Composable
private fun statusText(job: JobEntity, missing: Boolean): String = when {
    missing -> stringResource(R.string.status_file_missing)
    job.status == JobStatus.QUEUED -> stringResource(R.string.status_queued)
    job.status == JobStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    job.status == JobStatus.FAILED -> job.error ?: stringResource(R.string.status_failed)
    job.mode == Mode.AUDIO -> stringResource(R.string.status_saved_audio)
    else -> stringResource(R.string.status_saved_video)
}
