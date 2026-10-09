package io.github.sriram_pr.snaggle

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
import io.github.sriram_pr.snaggle.download.userMessage
import io.github.sriram_pr.snaggle.engine.Channel
import io.github.sriram_pr.snaggle.engine.DownloadEvent
import io.github.sriram_pr.snaggle.engine.DownloadRequest
import io.github.sriram_pr.snaggle.engine.EngineException
import io.github.sriram_pr.snaggle.engine.Mode
import io.github.sriram_pr.snaggle.engine.chaquopy.ChaquopyEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.time.TimeSource

/** Debug builds only: drive the engine directly (used by scripts/spike.sh) and show its log. */
class DebugActivity : ComponentActivity() {
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
        // Scripted runs: adb shell am start -n io.github.sriram_pr.snaggle/.MainActivity --es url <url> --es spike_action probe
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
    var lastFile: File? = null
    var lastSaved: android.net.Uri? = null
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
        (Spike.engine ?: context.snaggle.readyEngine().also {
            log("init: Ready(versions=${it.versions()})")
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
                    is DownloadEvent.Done -> {
                        Spike.lastFile = ev.file
                        log("✓ ${ev.file} (${ev.file.length() / 1024} KiB)")
                    }
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
            "update-stable" -> timed("update-stable") { log("${engine().updateYtDlp(Channel.STABLE)} installed=${engine().installedYtDlp()}") }
            "update-nightly" -> timed("update-nightly") { log("${engine().updateYtDlp(Channel.NIGHTLY)} installed=${engine().installedYtDlp()}") }
            "rollback" -> timed("rollback") { log("${engine().rollbackYtDlp()} installed=${engine().installedYtDlp()}") }
            "uri" -> timed("uri") {
                // How MediaStore reports a URI to this app: readable, missing, or someone else's.
                val uri = android.net.Uri.parse(url.trim())
                log("open: " + runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { "ok" } }.fold({ it }, { it.javaClass.name + ": " + it.message }))
                log("query: " + runCatching { context.contentResolver.query(uri, null, null, null, null)?.use { "rows=${it.count}" } }.fold({ it }, { it.javaClass.name + ": " + it.message }))
            }
            "sig" -> timed("sig") {
                // yt-dlp release signature check with the device's own crypto provider.
                val tag = url.trim().ifEmpty { "yt-dlp/yt-dlp/releases/download/2026.08.19" }
                val (sums, sig) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    java.net.URI("https://github.com/$tag/SHA2-256SUMS").toURL().readBytes() to
                        java.net.URI("https://github.com/$tag/SHA2-256SUMS.sig").toURL().readBytes()
                }
                log("valid: ${io.github.sriram_pr.snaggle.engine.YtDlpSignature.verify(sums, sig)}")
                log("tampered: ${io.github.sriram_pr.snaggle.engine.YtDlpSignature.verify(sums + 0x0a, sig)}")
            }
            "codecs" -> timed("codecs") {
                // Decoders the "Original quality" choice would rely on.
                val list = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS)
                for (mime in listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")) {
                    list.codecInfos.filter { !it.isEncoder && mime in it.supportedTypes }.forEach { info ->
                        val v = info.getCapabilitiesForType(mime).videoCapabilities
                        log("$mime ${info.name} hw=${info.isHardwareAccelerated} sw=${info.isSoftwareOnly} " +
                            "1440p60=${v?.areSizeAndRateSupported(2560, 1440, 60.0)} 2160p60=${v?.areSizeAndRateSupported(3840, 2160, 60.0)}")
                    }
                }
            }
            "save" -> timed("save") {
                // Last debug download → gallery, the way real downloads are saved.
                val file = Spike.lastFile ?: error("no download yet")
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.sriram_pr.snaggle.download.MediaStoreSaver.save(context, file)
                }
                Spike.lastSaved = saved.uri
                log("saved ${saved.uri} ${saved.mimeType}")
                context.contentResolver.query(saved.uri, arrayOf("mime_type", "width", "height", "duration", "_size"), null, null, null)?.use {
                    if (it.moveToFirst()) log("mediastore: mime=${it.getString(0)} ${it.getInt(1)}x${it.getInt(2)} duration=${it.getLong(3)}ms size=${it.getLong(4)}")
                }
                val thumb = runCatching { context.contentResolver.loadThumbnail(saved.uri, android.util.Size(320, 180), null) }
                log("thumbnail: " + thumb.fold({ "${it.width}x${it.height}" }, { it.toString() }))
            }
            "play" -> timed("play") {
                val uri = Spike.lastSaved ?: error("nothing saved")
                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, context.contentResolver.getType(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            "botmsg" -> timed("botmsg") {
                // The bot-check message as it would be shown right now (depends on whether a VPN is active).
                log(io.github.sriram_pr.snaggle.engine.EngineError.BotCheck.userMessage(context))
            }
            "versions" -> timed("versions") { log("${engine().versions()} installed=${engine().installedYtDlp()}") }
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
