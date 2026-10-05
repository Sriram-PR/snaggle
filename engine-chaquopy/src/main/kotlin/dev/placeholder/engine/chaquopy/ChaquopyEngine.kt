package dev.placeholder.engine.chaquopy

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.placeholder.engine.Channel
import dev.placeholder.engine.DownloadEvent
import dev.placeholder.engine.DownloadRequest
import dev.placeholder.engine.Engine
import dev.placeholder.engine.EngineError
import dev.placeholder.engine.EngineException
import dev.placeholder.engine.EngineStatus
import dev.placeholder.engine.EngineVersions
import dev.placeholder.engine.Formats
import dev.placeholder.engine.MediaInfo
import dev.placeholder.engine.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlinx.coroutines.channels.Channel as KChannel

/** Called from Python (bootstrap.py) with one JSON-encoded event per call. */
fun interface PyEventSink {
    fun onEvent(json: String)
}

private const val TAG = "Engine"

class ChaquopyEngine(context: Context) : Engine {
    private val appContext = context.applicationContext

    @Volatile private var bootstrap: PyObject? = null
    @Volatile private var versions = EngineVersions(null, null, null, null, null)

    private fun py(): PyObject = bootstrap ?: throw IllegalStateException("ChaquopyEngine.init() not called")

    override suspend fun init(): EngineStatus = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            val zip = installBaseline()
            val cache = File(appContext.cacheDir, "engine").apply { mkdirs() }
            val module = Python.getInstance().getModule("bootstrap")
            val v = JSONObject(module.callAttr("init", zip.path, cache.path, null, null).toString())
            bootstrap = module
            versions = EngineVersions(
                ytDlp = v.str("yt_dlp"),
                ejs = v.str("ejs"),
                python = v.str("python"),
                quickJs = null,
                ffmpeg = null,
            )
            EngineStatus.Ready(versions)
        } catch (e: PyException) {
            EngineStatus.Failed(EngineError.Unknown(e.message ?: e.toString()))
        }
    }

    /** Copies the APK's baseline yt-dlp to filesDir/ytdlp/stable-<version>/ once; zipimport needs a real file. */
    private fun installBaseline(): File {
        val version = appContext.assets.open("ytdlp/version.txt").bufferedReader().use { it.readText().trim() }
        val target = File(appContext.filesDir, "ytdlp/stable-$version/yt-dlp.zip")
        if (!target.exists()) {
            target.parentFile!!.mkdirs()
            val tmp = File(target.parentFile, "yt-dlp.zip.tmp")
            appContext.assets.open("ytdlp/yt-dlp.zip").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(tmp.renameTo(target)) { "Could not install $target" }
        }
        return target
    }

    override suspend fun probe(url: String): MediaInfo = withContext(Dispatchers.IO) {
        try {
            val sink = PyEventSink { json ->
                val e = JSONObject(json)
                if (e.optString("level") != "debug") Log.i(TAG, e.optString("message"))
            }
            val result = JSONObject(py().callAttr("probe", url, sink).toString())
            val formats = result.getJSONArray("formats")
            for (i in 0 until formats.length()) Log.d(TAG, "format ${formats.getJSONObject(i)}")
            result.toMediaInfo()
        } catch (e: PyException) {
            throw EngineException(EngineError.classify(e.message ?: ""), e)
        }
    }

    override fun download(req: DownloadRequest): Flow<DownloadEvent> = callbackFlow {
        val sink = PyEventSink { json -> parseEvent(JSONObject(json)).forEach { trySendBlocking(it) } }
        val (format, merge) = Formats.forMode(req.mode)
        req.outputDir.mkdirs()
        launch(Dispatchers.IO) {
            try {
                py().callAttr("download", req.jobId, req.url, format, merge, req.outputDir.path, sink)
            } catch (e: PyException) {
                trySendBlocking(DownloadEvent.Failed(EngineError.Unknown(e.message ?: e.toString())))
            }
            close()
        }
        // Collector gone (e.g. job cancelled from the UI): make Python stop at its next hook call.
        awaitClose { bootstrap?.callAttr("cancel", req.jobId) }
    }.buffer(KChannel.UNLIMITED)

    override suspend fun cancel(jobId: String) {
        withContext(Dispatchers.IO) { py().callAttr("cancel", jobId) }
    }

    override suspend fun updateYtDlp(channel: Channel): UpdateResult = UpdateResult.Failed("Not implemented yet (spike step 5)")

    override suspend fun rollbackYtDlp(): UpdateResult = UpdateResult.Failed("Not implemented yet (spike step 5)")

    override fun versions(): EngineVersions = versions

    private fun parseEvent(e: JSONObject): List<DownloadEvent> = when (e.getString("type")) {
        "progress" -> {
            val bytes = e.getLong("bytes")
            val total = e.long("total")
            listOf(
                DownloadEvent.Progress(
                    fraction = total?.takeIf { it > 0 }?.let { bytes.toFloat() / it },
                    bytes = bytes,
                    total = total,
                    speed = e.long("speed"),
                    eta = e.long("eta"),
                ),
            )
        }
        "postprocessing" -> listOf(DownloadEvent.PostProcessing)
        "log" -> listOf(DownloadEvent.Log(e.getString("level"), e.getString("message")))
        "done" -> listOf(
            e.str("path")?.let { DownloadEvent.Done(File(it), e.getJSONObject("info").toMediaInfo()) }
                ?: DownloadEvent.Failed(EngineError.Unknown("yt-dlp finished without an output file")),
        )
        "cancelled" -> listOf(DownloadEvent.Cancelled)
        "error" -> listOf(
            DownloadEvent.Log("error", e.getString("trace")),
            DownloadEvent.Failed(EngineError.classify(e.getString("message"))),
        )
        else -> listOf(DownloadEvent.Log("warning", "Unknown engine event: $e"))
    }
}

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONObject.long(key: String): Long? = if (isNull(key)) null else getDouble(key).toLong()

private fun JSONObject.toMediaInfo() = MediaInfo(
    id = getString("id"),
    title = getString("title"),
    extractor = getString("extractor"),
    durationSec = if (isNull("duration")) null else getDouble("duration"),
    thumbnail = str("thumbnail"),
)
