package dev.placeholder.app

import android.content.Intent
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.placeholder.engine.DownloadEvent
import dev.placeholder.engine.DownloadRequest
import dev.placeholder.engine.EngineException
import dev.placeholder.engine.Mode
import dev.placeholder.engine.chaquopy.ChaquopyEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.TimeSource

/** Throwaway spike UI: exercise the engine and show a log. Not the real app. */
class MainActivity : ComponentActivity() {
    private val sharedUrl = mutableStateOf("")
    private val pendingAction = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { MaterialTheme { SpikeScreen(sharedUrl, pendingAction) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // Scripted runs: adb shell am start -n dev.placeholder.app/.MainActivity --es url <url> --es spike_action probe
        intent?.getStringExtra("url")?.let { sharedUrl.value = it }
        debugParams = intent?.getStringExtra("params")
        intent?.getStringExtra("spike_action")?.let { pendingAction.value = it }
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                sharedUrl.value = Regex("https?://\\S+").find(text)?.value ?: text
            }
        }
    }
}

/** Extra yt-dlp params (JSON) from the launching intent, e.g. --es params '{"extractor_args":{...}}'. */
private var debugParams: String? = null

private object Spike {
    var engine: ChaquopyEngine? = null
    val log = mutableStateListOf<String>()
    var job: Job? = null
    var jobId = 0
}

@Composable
private fun SpikeScreen(
    sharedUrl: androidx.compose.runtime.MutableState<String>,
    pendingAction: androidx.compose.runtime.MutableState<String?>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var url by sharedUrl
    val listState = rememberLazyListState()
    fun log(line: String) {
        Spike.log.add(line)
        android.util.Log.i("Spike", line)
    }

    LaunchedEffect(Spike.log.size) { if (Spike.log.isNotEmpty()) listState.scrollToItem(Spike.log.lastIndex) }

    fun timed(label: String, block: suspend () -> Unit) {
        scope.launch {
            val mark = TimeSource.Monotonic.markNow()
            log("▶ $label")
            try {
                block()
            } catch (e: EngineException) {
                log("✗ ${e.error} ${e.cause?.message?.take(500)}")
            } catch (e: Exception) {
                log("✗ $e")
            }
            log("■ $label took ${mark.elapsedNow().inWholeMilliseconds} ms")
        }
    }

    suspend fun engine(): ChaquopyEngine =
        (Spike.engine ?: ChaquopyEngine(context).also {
            log("init: ${it.init()}")
            Spike.engine = it
        }).also { e ->
            e.setDebugParams(debugParams)
            debugParams?.let { log("debug params: $it") }
        }

    fun download(mode: Mode) {
        Spike.job?.cancel()
        Spike.job = scope.launch {
            val e = engine()
            val id = "job-${++Spike.jobId}"
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "spike")
            val mark = TimeSource.Monotonic.markNow()
            var lastPct = -10
            log("▶ download $mode $id")
            e.download(DownloadRequest(id, url.trim(), mode, dir)).collect { ev ->
                when (ev) {
                    is DownloadEvent.Progress -> {
                        val pct = ((ev.fraction ?: 0f) * 100).toInt()
                        if (pct >= lastPct + 10) {
                            lastPct = pct
                            log("  $pct% ${ev.bytes / 1024} KiB @ ${(ev.speed ?: 0) / 1024} KiB/s eta ${ev.eta}s")
                        }
                    }
                    is DownloadEvent.Log -> if (ev.level != "debug") log("  [${ev.level}] ${ev.message.take(800)}")
                    is DownloadEvent.Done -> log("✓ ${ev.file} (${ev.file.length() / 1024} KiB)")
                    else -> log("  $ev")
                }
            }
            log("■ download took ${mark.elapsedNow().inWholeMilliseconds} ms")
        }
    }

    LaunchedEffect(pendingAction.value) {
        when (pendingAction.value) {
            "init" -> timed("init") { engine(); log(Spike.engine?.versions().toString()) }
            "probe" -> timed("probe") { log(engine().probe(url.trim()).toString()) }
            "video" -> download(Mode.VIDEO_COMPAT)
            "720" -> download(Mode.VIDEO_720)
            "audio" -> download(Mode.AUDIO)
            "cancel" -> { Spike.job?.cancel(); log("cancel requested") }
        }
        pendingAction.value = null
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button({ timed("init") { engine(); log(Spike.engine?.versions().toString()) } }) { Text("Init") }
            Button({ timed("probe") { log(engine().probe(url.trim()).toString()) } }) { Text("Probe") }
            Button({ download(Mode.VIDEO_COMPAT) }) { Text("Video") }
            Button({ download(Mode.VIDEO_720) }) { Text("720p") }
            Button({ download(Mode.AUDIO) }) { Text("Audio") }
            Button({ Spike.job?.cancel(); log("cancel requested") }) { Text("Cancel") }
            Button({ Spike.log.clear() }) { Text("Clear") }
        }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(Spike.log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}
