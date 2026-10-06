package io.github.sriram_pr.snaggle.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.download.JobActions
import io.github.sriram_pr.snaggle.engine.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Video / Audio chooser for one link. Used by the share target and the history screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var dataSaver by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Pair<Mode, JobEntity>?>(null) }
    var pendingMode by remember { mutableStateOf<Mode?>(null) }

    fun startNow(mode: Mode) = scope.launch {
        JobActions.start(context, url, mode)
        onDismiss()
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Downloads run either way; without the permission they only show in Task Manager.
        pendingMode?.let { startNow(it) }
    }
    fun begin(mode: Mode) {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingMode = mode
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startNow(mode)
        }
    }
    fun choose(mode: Mode) = scope.launch {
        val found = withContext(Dispatchers.IO) { JobActions.findExisting(context, url, mode) }
        if (found != null) existing = mode to found else begin(mode)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val found = existing
            if (found == null) {
                Text(url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!dataSaver, { dataSaver = false }, { Text(stringResource(R.string.share_quality_compatible)) })
                    FilterChip(dataSaver, { dataSaver = true }, { Text(stringResource(R.string.share_quality_720)) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ choose(if (dataSaver) Mode.VIDEO_720 else Mode.VIDEO_COMPAT) }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_video))
                    }
                    Button({ choose(Mode.AUDIO) }, Modifier.weight(1f)) { Text(stringResource(R.string.share_audio)) }
                }
            } else {
                Text(stringResource(R.string.already_downloaded), style = MaterialTheme.typography.titleMedium)
                Text(found.second.title ?: url, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.already_downloaded_body), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({ JobActions.open(context, found.second); onDismiss() }, Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_open))
                    }
                    Button({ begin(found.first) }, Modifier.weight(1f)) { Text(stringResource(R.string.action_download_again)) }
                }
            }
        }
    }
}
