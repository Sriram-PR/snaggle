package dev.placeholder.engine.chaquopy

import android.content.Context
import android.system.Os
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
            val ffmpeg = installFfmpeg()
            val quickJs = findQuickJs()
            val v = JSONObject(
                module.callAttr("init", zip.path, cache.path, quickJs?.first?.path, ffmpeg?.first?.path).toString(),
            )
            bootstrap = module
            versions = EngineVersions(
                ytDlp = v.str("yt_dlp"),
                ejs = v.str("ejs"),
                python = v.str("python"),
                quickJs = quickJs?.second,
                ffmpeg = ffmpeg?.second,
            )
            EngineStatus.Ready(versions)
        } catch (e: PyException) {
            EngineStatus.Failed(EngineError.Unknown(e.message ?: e.toString()))
        }
    }

    /**
     * Android only lets apps exec files from nativeLibraryDir, where they must be named lib*.so.
     * yt-dlp looks for `ffmpeg`/`ffprobe` in a directory, so point symlinks with those names at them.
     * Recreated on every init because nativeLibraryDir changes when the app is updated.
     * Returns the symlink directory and `ffmpeg -version`'s first line, or null if not bundled.
     */
    private fun installFfmpeg(): Pair<File, String>? {
        val libDir = File(appContext.applicationInfo.nativeLibraryDir)
        if (!File(libDir, "libffmpeg.so").exists()) return null
        val binDir = File(appContext.filesDir, "bin").apply { mkdirs() }
        for (name in listOf("ffmpeg", "ffprobe")) {
            val link = File(binDir, name)
            link.delete()
            Os.symlink(File(libDir, "lib$name.so").path, link.path)
        }
        val process = ProcessBuilder(File(binDir, "ffmpeg").path, "-hide_banner", "-version").redirectErrorStream(true).start()
        val firstLine = process.inputStream.bufferedReader().use { it.readLine() }.orEmpty()
        check(process.waitFor() == 0) { "ffmpeg failed to run: $firstLine" }
        return binDir to firstLine.removePrefix("ffmpeg version ").substringBefore(' ')
    }

    /** yt-dlp accepts a direct path for the JS runtime, so no symlink is needed. Returns path and version. */
    private fun findQuickJs(): Pair<File, String>? {
        val qjs = File(appContext.applicationInfo.nativeLibraryDir, "libqjs.so")
        if (!qjs.exists()) return null
        // qjs has no --version; --help prints "QuickJS version <date>" and exits 1.
        val firstLine = runForFirstLine(qjs.path, "--help")
        return qjs to firstLine.substringAfter("version ").trim()
    }

    private fun runForFirstLine(vararg cmd: String): String {
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val firstLine = process.inputStream.bufferedReader().use { it.readLine() }.orEmpty()
        process.waitFor()
        return firstLine
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
                if (e.optString("level") == "debug") Log.d(TAG, e.optString("message")) else Log.i(TAG, e.optString("message"))
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

    /** Spike/testing only: extra yt-dlp params (JSON object) merged into every call; null clears. */
    fun setDebugParams(json: String?) {
        py().callAttr("set_debug_params", json)
    }

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
