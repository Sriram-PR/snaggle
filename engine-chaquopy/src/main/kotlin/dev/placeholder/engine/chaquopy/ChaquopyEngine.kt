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
import dev.placeholder.engine.YtDlpVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel as KChannel

/** Called from Python (bootstrap.py) with one JSON-encoded event per call. */
fun interface PyEventSink {
    fun onEvent(json: String)
}

private const val TAG = "Engine"

class ChaquopyEngine(context: Context) : Engine {
    private val appContext = context.applicationContext

    private val store = YtDlpStore(File(appContext.filesDir, "ytdlp"), appContext.assets)

    // yt-dlp can only be swapped while nothing is using it: probes/downloads count as jobs,
    // and updateLock keeps a job from starting mid-swap.
    private val updateLock = Mutex()
    private val activeJobs = AtomicInteger()

    @Volatile private var bootstrap: PyObject? = null
    @Volatile private var versions = EngineVersions(null, null, null, null, null)

    private fun py(): PyObject = bootstrap ?: throw IllegalStateException("ChaquopyEngine.init() not called")

    override suspend fun init(): EngineStatus = withContext(Dispatchers.IO) {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(appContext))
            val zip = store.ensureActive().zip
            val cache = File(appContext.cacheDir, "engine").apply { mkdirs() }
            val module = Python.getInstance().getModule("bootstrap")
            val ffmpeg = installFfmpeg()
            val quickJs = findQuickJs()
            versions = EngineVersions(null, null, null, quickJs = quickJs?.second, ffmpeg = ffmpeg?.second)
            val loaded = try {
                module.callAttr("init", zip.path, cache.path, quickJs?.first?.path, ffmpeg?.first?.path)
            } catch (e: PyException) {
                // A downloaded version that won't import: fall back to the previous one.
                val broken = store.active
                Log.w(TAG, "yt-dlp ${broken?.id} failed to load, rolling back", e)
                store.rollback() ?: throw e
                broken?.let(store::discard)
                module.callAttr("load", store.active!!.zip.path)
            }
            bootstrap = module
            applyVersions(loaded)
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

    private fun applyVersions(json: PyObject) {
        val v = JSONObject(json.toString())
        versions = versions.copy(ytDlp = v.str("yt_dlp"), ejs = v.str("ejs"), python = v.str("python"))
    }

    /** Runs [block] as a job: waits for any yt-dlp swap to finish and blocks new swaps until done. */
    private suspend fun <T> job(block: suspend () -> T): T {
        updateLock.withLock { activeJobs.incrementAndGet() }
        try {
            return block()
        } finally {
            activeJobs.decrementAndGet()
        }
    }

    /** Loads [next] into Python; if it fails, reloads the current version so the engine keeps working. */
    private fun switchTo(next: Installed) {
        try {
            applyVersions(py().callAttr("load", next.zip.path))
        } catch (e: PyException) {
            store.active?.let { applyVersions(py().callAttr("load", it.zip.path)) }
            store.discard(next)
            throw e
        }
    }

    override suspend fun probe(url: String): MediaInfo = withContext(Dispatchers.IO) {
        job { probeNow(url) }
    }

    private fun probeNow(url: String): MediaInfo {
        try {
            val sink = PyEventSink { json ->
                val e = JSONObject(json)
                if (e.optString("level") == "debug") Log.d(TAG, e.optString("message")) else Log.i(TAG, e.optString("message"))
            }
            val result = JSONObject(py().callAttr("probe", url, sink).toString())
            val formats = result.getJSONArray("formats")
            for (i in 0 until formats.length()) Log.d(TAG, "format ${formats.getJSONObject(i)}")
            return result.toMediaInfo()
        } catch (e: PyException) {
            throw EngineException(EngineError.classify(e.message ?: ""), e)
        }
    }

    override fun download(req: DownloadRequest): Flow<DownloadEvent> = callbackFlow {
        val sink = PyEventSink { json -> parseEvent(JSONObject(json)).forEach { trySendBlocking(it) } }
        val (format, merge) = Formats.forMode(req.mode)
        req.outputDir.mkdirs()
        launch(Dispatchers.IO) {
            job {
                try {
                    py().callAttr("download", req.jobId, req.url, format, merge, req.outputDir.path, sink)
                } catch (e: PyException) {
                    trySendBlocking(DownloadEvent.Failed(EngineError.Unknown(e.message ?: e.toString())))
                }
            }
            close()
        }
        // Collector gone (e.g. job cancelled from the UI): make Python stop at its next hook call.
        awaitClose { bootstrap?.callAttr("cancel", req.jobId) }
    }.buffer(KChannel.UNLIMITED)

    override suspend fun cancel(jobId: String) {
        withContext(Dispatchers.IO) { py().callAttr("cancel", jobId) }
    }

    /** Switches to the latest [channel] release, but never to a version older than the active one. */
    override suspend fun updateYtDlp(channel: Channel): UpdateResult = withContext(Dispatchers.IO) {
        updateLock.withLock {
            if (activeJobs.get() > 0) return@withLock UpdateResult.Failed("A download is running")
            val current = store.active ?: return@withLock UpdateResult.Failed("Engine not initialised")
            try {
                val latest = store.latestVersion(channel)
                if (!YtDlpVersion.isNewer(latest, current.version)) return@withLock UpdateResult.UpToDate(current.version)
                val next = store.download(channel, latest)
                switchTo(next)
                store.activate(next)
                UpdateResult.Updated(current.id, next.id)
            } catch (e: Exception) {
                UpdateResult.Failed(e.message ?: e.toString())
            }
        }
    }

    override suspend fun rollbackYtDlp(): UpdateResult = withContext(Dispatchers.IO) {
        updateLock.withLock {
            if (activeJobs.get() > 0) return@withLock UpdateResult.Failed("A download is running")
            val current = store.active ?: return@withLock UpdateResult.Failed("Engine not initialised")
            val target = store.previous ?: return@withLock UpdateResult.Failed("Nothing to roll back to")
            try {
                switchTo(target)
                store.rollback()
                UpdateResult.Updated(current.id, target.id)
            } catch (e: Exception) {
                UpdateResult.Failed(e.message ?: e.toString())
            }
        }
    }

    /** Installed yt-dlp ids (e.g. "nightly-2026.09.27.232945"), for the engine settings screen and logs. */
    fun installedYtDlp(): Pair<String?, String?> = store.active?.id to store.previous?.id

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
