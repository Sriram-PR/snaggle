package io.github.sriram_pr.snaggle

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sriram_pr.snaggle.data.NightlyRetry
import io.github.sriram_pr.snaggle.data.Settings
import io.github.sriram_pr.snaggle.data.UpdateFrequency
import io.github.sriram_pr.snaggle.download.DownloadScheduler
import io.github.sriram_pr.snaggle.download.EngineUpdates
import io.github.sriram_pr.snaggle.engine.EngineVersions
import io.github.sriram_pr.snaggle.engine.YtDlpInstall
import io.github.sriram_pr.snaggle.ui.SnaggleTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SnaggleTheme { SettingsScreen(onBack = ::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.snaggle
    val scope = rememberCoroutineScope()
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val engine by produceState<Pair<EngineVersions, List<YtDlpInstall>>?>(null, refresh) {
        val e = app.readyEngine()
        value = e.versions() to e.installedYtDlp()
    }
    fun run(action: suspend () -> String) {
        busy = true
        scope.launch {
            message = action()
            busy = false
            refresh++
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Header(stringResource(R.string.settings_downloads))
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(settings.wifiOnly, role = Role.Switch) { v ->
                        scope.launch {
                            app.settings.setWifiOnly(v)
                            DownloadScheduler.rescheduleWaiting(app)
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_wifi_only))
                    Text(
                        stringResource(R.string.settings_wifi_only_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(settings.wifiOnly, null)
            }

            Header(stringResource(R.string.settings_updates))
            for ((value, label) in listOf(
                UpdateFrequency.DAILY to R.string.settings_updates_daily,
                UpdateFrequency.WEEKLY to R.string.settings_updates_weekly,
                UpdateFrequency.MANUAL to R.string.settings_updates_manual,
            )) {
                RadioRow(stringResource(label), settings.updateFrequency == value) { scope.launch { app.settings.setUpdateFrequency(value) } }
            }
            Row(
                Modifier.fillMaxWidth()
                    .toggleable(settings.notifyEngineUpdates, role = Role.Switch) { v -> scope.launch { app.settings.setNotifyEngineUpdates(v) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_notify_updates), Modifier.weight(1f))
                Switch(settings.notifyEngineUpdates, null)
            }

            Header(stringResource(R.string.settings_site_changed))
            RadioRow(stringResource(R.string.settings_nightly_ask), settings.nightlyRetry == NightlyRetry.ASK) {
                scope.launch { app.settings.setNightlyRetry(NightlyRetry.ASK) }
            }
            RadioRow(stringResource(R.string.settings_nightly_auto), settings.nightlyRetry == NightlyRetry.AUTO) {
                scope.launch { app.settings.setNightlyRetry(NightlyRetry.AUTO) }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Header(stringResource(R.string.settings_engine))
            engine?.first?.let { v ->
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val active = engine?.second?.firstOrNull { it.active }
                    Info("yt-dlp", active?.let { "${it.version} (${it.channel.name.lowercase()})" } ?: v.ytDlp)
                    Info("EJS", v.ejs)
                    Info("Python", v.python)
                    Info("QuickJS", v.quickJs)
                    Info("ffmpeg", v.ffmpeg)
                    Text(
                        if (settings.lastEngineCheck == 0L) stringResource(R.string.settings_never_checked)
                        else stringResource(R.string.settings_last_checked, DateUtils.getRelativeTimeSpanString(settings.lastEngineCheck).toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton({ run { EngineUpdates.resultMessage(app, EngineUpdates.checkNow(app)) } }, enabled = !busy) {
                    Text(stringResource(if (busy) R.string.settings_checking else R.string.settings_check_now))
                }
            }
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }

            Header(stringResource(R.string.settings_versions))
            Text(
                stringResource(R.string.settings_versions_hint),
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            engine?.second?.forEach { install ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(install.version, style = MaterialTheme.typography.bodyLarge)
                        Text(install.channel.name.lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (install.held) AssistChip({}, { Text(stringResource(R.string.settings_held)) })
                    if (install.active) {
                        AssistChip({}, { Text(stringResource(R.string.settings_in_use)) })
                    } else {
                        TextButton({ run { EngineUpdates.resultMessage(app, app.readyEngine().useYtDlp(install.id, waitForIdle = true)) } }, enabled = !busy) {
                            Text(stringResource(R.string.settings_use_version))
                        }
                    }
                }
            }

            var showLicenses by remember { mutableStateOf(false) }
            TextButton({ showLicenses = true }, Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) {
                Text(stringResource(R.string.settings_licenses))
            }
            if (showLicenses) {
                val notices = remember { app.assets.open("licenses/THIRD_PARTY_NOTICES.md").bufferedReader().use { it.readText() } }
                AlertDialog(
                    onDismissRequest = { showLicenses = false },
                    confirmButton = { TextButton({ showLicenses = false }) { Text(stringResource(R.string.action_close)) } },
                    title = { Text(stringResource(R.string.settings_licenses)) },
                    text = { Text(notices, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun Info(label: String, value: String?) = Row {
    Text(label, Modifier.weight(0.35f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value ?: "—", Modifier.weight(0.65f), style = MaterialTheme.typography.bodyMedium)
}
