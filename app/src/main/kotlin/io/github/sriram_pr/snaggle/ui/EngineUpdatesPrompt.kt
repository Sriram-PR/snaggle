package io.github.sriram_pr.snaggle.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.data.UpdateFrequency
import io.github.sriram_pr.snaggle.download.EngineUpdates
import io.github.sriram_pr.snaggle.snaggle
import kotlinx.coroutines.launch

/**
 * One-time consent for downloading yt-dlp updates (F-Droid/IzzyOnDroid policy): opt-in, and the two
 * choices look identical so declining is as easy as accepting.
 */
@Composable
fun EngineUpdatesPrompt(modifier: Modifier = Modifier) {
    val app = LocalContext.current.snaggle
    val settings by app.settings.settings.collectAsStateWithLifecycle(null)
    if (settings?.updatesChosen != false) return

    fun choose(frequency: UpdateFrequency) = app.appScope.launch {
        app.settings.setUpdateFrequency(frequency)
        runCatching { EngineUpdates.checkIfDue(app) }
    }
    Card(modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.updates_prompt_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.updates_prompt_body), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton({ choose(UpdateFrequency.DAILY) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_prompt_auto))
            }
            OutlinedButton({ choose(UpdateFrequency.MANUAL) }, Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.updates_prompt_manual))
            }
        }
    }
}
