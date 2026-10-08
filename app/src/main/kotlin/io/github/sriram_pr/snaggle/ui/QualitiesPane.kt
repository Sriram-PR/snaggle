package io.github.sriram_pr.snaggle.ui

import android.content.Context
import android.media.MediaCodecList
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.sriram_pr.snaggle.R
import io.github.sriram_pr.snaggle.download.JobActions
import io.github.sriram_pr.snaggle.download.userMessage
import io.github.sriram_pr.snaggle.engine.EngineException
import io.github.sriram_pr.snaggle.engine.MediaInfo
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.engine.Qualities
import io.github.sriram_pr.snaggle.engine.Quality
import io.github.sriram_pr.snaggle.engine.QualityOptions
import io.github.sriram_pr.snaggle.snaggle
import java.util.Locale

/** "More qualities": every resolution the link actually has, plus video-only and audio choices. */
@Composable
fun QualitiesPane(url: String, onDownload: (Mode, JobActions.Choice) -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<Result<Pair<MediaInfo, QualityOptions>>?>(null, url) {
        value = runCatching {
            val info = context.snaggle.readyEngine().probe(url)
            info to Qualities.from(info.formats, hasHardwareAv1())
        }
    }
    when (val result = loaded) {
        null -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        else -> result.fold(
            onSuccess = { (info, options) -> Choices(info, options, onDownload) },
            onFailure = { e ->
                val message = (e as? EngineException)?.error?.userMessage(context) ?: stringResource(R.string.qualities_failed)
                Text(message, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.error)
            },
        )
    }
}

@Composable
private fun Choices(info: MediaInfo, options: QualityOptions, onDownload: (Mode, JobActions.Choice) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(options.default ?: options.audio.firstOrNull()) }
    var language by remember { mutableStateOf(options.defaultLanguage) }
    var showVideoOnly by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val duration = info.durationSec?.let { " · " + DateUtils.formatElapsedTime(it.toLong()) }.orEmpty()
        Text(info.title + duration, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            if (options.video.isNotEmpty()) item { Header(stringResource(R.string.qualities_video)) }
            items(options.video) { q -> Choice(q, q == selected) { selected = q } }
            if (options.videoOnly.isNotEmpty()) {
                item {
                    TextButton({ showVideoOnly = !showVideoOnly }) {
                        Text(stringResource(R.string.qualities_video_only) + if (showVideoOnly) "  ▴" else "  ▾")
                    }
                }
                if (showVideoOnly) items(options.videoOnly) { q -> Choice(q, q == selected) { selected = q } }
            }
            if (options.audio.isNotEmpty()) item { Header(stringResource(R.string.qualities_audio)) }
            items(options.audio) { q -> Choice(q, q == selected) { selected = q } }
            if (options.languages.isNotEmpty()) item { LanguagePicker(options.languages, language) { language = it } }
        }
        val choice = selected
        Button(
            {
                if (choice != null) {
                    val (format, merge) = choice.selector(language)
                    val mode = if (choice.kind == Quality.Kind.AUDIO) Mode.AUDIO else Mode.VIDEO_COMPAT
                    onDownload(mode, JobActions.Choice(format, merge, label(context, choice)))
                }
            },
            Modifier.fillMaxWidth(),
            enabled = choice != null,
        ) {
            val size = choice?.sizeBytes?.let { " " + Formatter.formatShortFileSize(context, it) }.orEmpty()
            Text(stringResource(R.string.action_download) + size)
        }
    }
}

@Composable
private fun Header(text: String) =
    Text(text, Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Choice(q: Quality, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(rowTitle(context, q), style = MaterialTheme.typography.bodyLarge)
            tag(context, q)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        q.sizeBytes?.let { Text(Formatter.formatShortFileSize(context, it), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun LanguagePicker(languages: List<String>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton({ open = true }) {
            Text(stringResource(R.string.qualities_language, displayLanguage(selected ?: languages.first())) + "  ▾")
        }
        DropdownMenu(open, { open = false }) {
            languages.forEach { code ->
                DropdownMenuItem({ Text(displayLanguage(code)) }, { onPick(code); open = false })
            }
        }
    }
}

private fun displayLanguage(code: String) = Locale.forLanguageTag(code).displayName.ifBlank { code }

private fun height(q: Quality): String {
    val h = q.height ?: return ""
    val name = when (h) { 4320 -> "8K"; 2160 -> "4K"; else -> "${h}p" }
    return if ((q.fps ?: 0) > 30) "$name · ${q.fps} fps" else name
}

private fun rowTitle(context: Context, q: Quality): String = when (q.kind) {
    // No bitrate: the site didn't report the codec (the audio comes out of an MP4).
    Quality.Kind.AUDIO -> q.kbps?.let {
        context.getString(if (q.small) R.string.qualities_audio_small else R.string.qualities_audio_best, it)
    } ?: context.getString(R.string.qualities_audio_plain)
    else -> height(q)
}

private fun tag(context: Context, q: Quality): String? = when {
    q.kind == Quality.Kind.AUDIO -> null
    q.playsEverywhere -> context.getString(R.string.qualities_plays_everywhere)
    (q.height ?: 0) > 1080 -> context.getString(R.string.qualities_original)
    else -> null
}

/** Shown in history for downloads made from this list. */
private fun label(context: Context, q: Quality): String = when (q.kind) {
    Quality.Kind.VIDEO -> listOfNotNull(height(q), tag(context, q)?.takeIf { (q.height ?: 0) > 1080 }).joinToString(" · ")
    Quality.Kind.VIDEO_ONLY -> context.getString(R.string.qualities_label_video_only, height(q))
    Quality.Kind.AUDIO -> rowTitle(context, q)
}

/** AV1 only when a hardware decoder exists; software AV1 can't keep up with high resolutions. */
private fun hasHardwareAv1() = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any {
    !it.isEncoder && it.isHardwareAccelerated && "video/av01" in it.supportedTypes
}
