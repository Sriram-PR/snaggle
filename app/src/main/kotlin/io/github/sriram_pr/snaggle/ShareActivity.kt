package io.github.sriram_pr.snaggle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.sriram_pr.snaggle.data.JobEntity
import io.github.sriram_pr.snaggle.data.JobStatus
import io.github.sriram_pr.snaggle.download.DownloadScheduler
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.ui.SnaggleTheme
import kotlinx.coroutines.launch
import java.util.UUID

/** Share-sheet target: a bottom sheet over whatever app shared the link. */
class ShareActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { URL_REGEX.find(it)?.value }
        if (url == null) {
            Toast.makeText(this, R.string.share_no_link, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        setContent {
            SnaggleTheme {
                var dataSaver by remember { mutableStateOf(false) }
                var pendingMode by remember { mutableStateOf<Mode?>(null) }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    // Downloads run either way; without the permission they only show in Task Manager.
                    pendingMode?.let { start(url, it) }
                }
                fun choose(mode: Mode) {
                    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    if (needsPermission) {
                        pendingMode = mode
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        start(url, mode)
                    }
                }
                ModalBottomSheet(onDismissRequest = { finish() }) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp).navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
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
                    }
                }
            }
        }
    }

    private fun start(url: String, mode: Mode) {
        val app = snaggle
        lifecycleScope.launch {
            val id = UUID.randomUUID().toString()
            app.db.jobs().insert(JobEntity(id = id, url = url, mode = mode, status = JobStatus.QUEUED))
            DownloadScheduler.enqueue(app, id)
            finish()
        }
    }

    private companion object {
        val URL_REGEX = Regex("https?://\\S+")
    }
}
