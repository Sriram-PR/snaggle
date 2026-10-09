package io.github.sriram_pr.snaggle.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Settings
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
import io.github.sriram_pr.snaggle.engine.Links
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.data.NightlyState
import io.github.sriram_pr.snaggle.download.JobActions
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val jobs by remember { context.snaggle.db.jobs().observeAll() }.collectAsStateWithLifecycle(emptyList())
    val wifiOnly by remember { context.snaggle.settings.settings.map { it.wifiOnly } }.collectAsStateWithLifecycle(false)
    var linkText by rememberSaveable { mutableStateOf("") }
    var sheetUrl by remember { mutableStateOf<String?>(null) }
    var consentIds by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmSelected by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Gone from the list but not yet from disk while "Undo" is offered.
    val undoable = remember { mutableStateMapOf<String, JobEntity>() }
    val visible = jobs.filter { it.id !in undoable }
    val selectable = visible.filter { it.status in SELECTABLE }

    val deleteConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val ids = consentIds
        consentIds = emptyList()
        if (result.resultCode == Activity.RESULT_OK) scope.launch { ids.forEach { JobActions.forget(context, it) } }
    }
    fun handle(result: JobActions.DeleteResult) {
        if (result is JobActions.DeleteResult.NeedsConsent) {
            consentIds = result.jobIds
            deleteConsent.launch(IntentSenderRequest.Builder(result.request).build())
        }
    }
    fun deleteAll(list: List<JobEntity>, deleteFiles: Boolean) = scope.launch { handle(JobActions.deleteAll(context, list, deleteFiles)) }
    fun deleteWithUndo(job: JobEntity) {
        snackbar.currentSnackbarData?.dismiss() // the previous one is committed now
        undoable[job.id] = job
        scope.launch {
            val result = snackbar.showSnackbar(context.getString(R.string.snackbar_deleted), context.getString(R.string.action_undo), duration = SnackbarDuration.Short)
            val pending = undoable.remove(job.id) ?: return@launch
            if (result != SnackbarResult.ActionPerformed) handle(JobActions.delete(context, pending))
        }
    }
    // Leaving the screen while "Undo" is showing commits the delete (a consent dialog can't be shown then).
    DisposableEffect(Unit) {
        onDispose {
            val left = undoable.values.toList()
            undoable.clear()
            if (left.isNotEmpty()) context.snaggle.appScope.launch { JobActions.deleteAll(context.snaggle, left, deleteFiles = true) }
        }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    fun submit() {
        val url = Links.extract(linkText)
        if (url == null) Toast.makeText(context, R.string.share_no_link, Toast.LENGTH_SHORT).show() else sheetUrl = url
    }

    Scaffold(
        topBar = {
            if (selected.isNotEmpty()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.history_selected, selected.size)) },
                    navigationIcon = { IconButton({ selected = emptySet() }) { Icon(Icons.Default.Close, stringResource(R.string.action_cancel)) } },
                    actions = {
                        TextButton({ selected = selectable.map { it.id }.toSet() }) { Text(stringResource(R.string.action_select_all)) }
                        IconButton({ confirmSelected = true }) { Icon(Icons.Default.Delete, stringResource(R.string.action_delete)) }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        IconButton(onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) }
                        Box {
                            IconButton({ menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                            DropdownMenu(menuOpen, { menuOpen = false }) {
                                DropdownMenuItem(
                                    { Text(stringResource(R.string.action_clear_history)) },
                                    { menuOpen = false; confirmClear = true },
                                    enabled = selectable.isNotEmpty(),
                                )
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
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
            item { EngineUpdatesPrompt(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (visible.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.history_empty),
                        Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(visible, key = { it.id }) { job ->
                fun toggle() {
                    selected = if (job.id in selected) selected - job.id else selected + job.id
                }
                JobRow(
                    job,
                    wifiOnly,
                    selection = if (job.status in SELECTABLE) (job.id in selected) else null,
                    selectionMode = selected.isNotEmpty(),
                    onToggle = ::toggle,
                    onOpen = { if (!JobActions.open(context, job)) Toast.makeText(context, R.string.open_failed, Toast.LENGTH_SHORT).show() },
                    onShare = { JobActions.share(context, job) },
                    onCancel = { scope.launch { JobActions.cancel(context, job.id) } },
                    onRetry = { scope.launch { JobActions.retry(context, job) } },
                    onTryLatestEngine = { scope.launch { JobActions.tryLatestEngine(context, job.id) } },
                    onDelete = { deleteWithUndo(job) },
                )
            }
        }
    }
    if (confirmSelected) {
        val chosen = visible.filter { it.id in selected }
        AlertDialog(
            onDismissRequest = { confirmSelected = false },
            title = { Text(pluralStringResource(R.plurals.delete_selected_title, chosen.size, chosen.size)) },
            text = { Text(stringResource(R.string.delete_selected_body)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton({ deleteAll(chosen, true); selected = emptySet(); confirmSelected = false }) {
                        Text(stringResource(R.string.action_delete_files), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton({ deleteAll(chosen, false); selected = emptySet(); confirmSelected = false }) {
                        Text(stringResource(R.string.action_remove_from_history))
                    }
                    TextButton({ confirmSelected = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
    if (confirmClear) {
        var alsoFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_history_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.clear_history_body))
                    Row(
                        Modifier.fillMaxWidth().toggleable(alsoFiles, role = Role.Checkbox) { alsoFiles = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(alsoFiles, null)
                        Text(stringResource(R.string.clear_history_files), Modifier.padding(start = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton({ deleteAll(selectable, alsoFiles); confirmClear = false }) {
                    Text(stringResource(R.string.action_clear), color = if (alsoFiles) MaterialTheme.colorScheme.error else Color.Unspecified)
                }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    sheetUrl?.let { url ->
        DownloadSheet(url) {
            sheetUrl = null
            linkText = ""
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobRow(
    job: JobEntity,
    wifiOnly: Boolean,
    /** null: this row can't be selected (running or waiting). */
    selection: Boolean?,
    selectionMode: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onTryLatestEngine: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    // Thumbnail from MediaStore for finished jobs; null bitmap + present=false means the file is gone.
    val thumb by produceState<Pair<Bitmap?, Boolean>?>(null, job.outputUri, job.status) {
        value = if (job.status != JobStatus.DONE || job.outputUri == null) null else withContext(Dispatchers.IO) {
            if (JobActions.fileState(context, job) == JobActions.FileState.MISSING) null to false
            else runCatching { context.contentResolver.loadThumbnail(Uri.parse(job.outputUri), Size(320, 180), null) }.getOrNull() to true
        }
    }
    val missing = thumb?.second == false
    val openable = job.status == JobStatus.DONE && !missing
    Row(
        Modifier.fillMaxWidth()
            .background(if (selection == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                enabled = if (selectionMode) selection != null else openable || selection != null,
                onLongClick = if (selection != null && !selectionMode) onToggle else null,
                onClick = { if (selectionMode) onToggle() else if (openable) onOpen() },
            )
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
                    statusText(job, missing, wifiOnly),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (job.status == JobStatus.FAILED || missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (job.status == JobStatus.FAILED) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (job.siteChange && job.nightly == NightlyState.NONE) {
                    TextButton(onTryLatestEngine, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_try_latest_engine)) }
                }
                job.report?.let { report ->
                    TextButton({ copyReport(context, report) }, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_copy_report)) }
                }
            }
        }
        if (selectionMode && selection != null) Checkbox(selection, null)
        else when (job.status) {
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

private val SELECTABLE = setOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED)

private fun copyReport(context: Context, report: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Snaggle debug report", report))
    // Android 13+ confirms clipboard writes itself.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.report_copied, Toast.LENGTH_SHORT).show()
}

@Composable
private fun statusText(job: JobEntity, missing: Boolean, wifiOnly: Boolean): String = when {
    missing -> stringResource(R.string.status_file_missing)
    job.status == JobStatus.QUEUED -> stringResource(if (wifiOnly) R.string.status_waiting_wifi else R.string.status_queued)
    job.status == JobStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    job.status == JobStatus.FAILED -> job.error ?: stringResource(R.string.status_failed)
    job.qualityLabel != null -> stringResource(
        if (job.mode == Mode.AUDIO) R.string.status_saved_quality_audio else R.string.status_saved_quality_video,
        job.qualityLabel,
    )
    job.mode == Mode.AUDIO -> stringResource(R.string.status_saved_audio)
    else -> stringResource(R.string.status_saved_video)
}
