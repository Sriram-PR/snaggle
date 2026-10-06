package io.github.sriram_pr.snaggle.engine

import kotlinx.coroutines.flow.Flow
import java.io.File

interface Engine {
    suspend fun init(): EngineStatus
    /** Single extraction; the result is cached so a following [download] of the same URL doesn't re-extract. */
    suspend fun probe(url: String): MediaInfo
    fun download(req: DownloadRequest): Flow<DownloadEvent>
    suspend fun cancel(jobId: String)
    suspend fun updateYtDlp(channel: Channel): UpdateResult
    suspend fun rollbackYtDlp(): UpdateResult
    fun versions(): EngineVersions
}

enum class Mode { VIDEO_COMPAT, VIDEO_720, AUDIO }

enum class Channel { STABLE, NIGHTLY }

data class DownloadRequest(val jobId: String, val url: String, val mode: Mode, val outputDir: File)

data class MediaInfo(
    val id: String,
    val title: String,
    val extractor: String,
    val durationSec: Double?,
    val thumbnail: String?,
)

sealed interface EngineStatus {
    data class Ready(val versions: EngineVersions) : EngineStatus
    data class Failed(val error: EngineError) : EngineStatus
}

data class EngineVersions(
    val ytDlp: String?,
    val ejs: String?,
    val python: String?,
    val quickJs: String?,
    val ffmpeg: String?,
)

sealed interface UpdateResult {
    data class Updated(val from: String, val to: String) : UpdateResult
    data class UpToDate(val version: String) : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}

sealed interface DownloadEvent {
    data class Progress(val fraction: Float?, val bytes: Long, val total: Long?, val speed: Long?, val eta: Long?) : DownloadEvent
    data object PostProcessing : DownloadEvent
    data class Log(val level: String, val message: String) : DownloadEvent
    data class Done(val file: File, val info: MediaInfo) : DownloadEvent
    data object Cancelled : DownloadEvent
    data class Failed(val error: EngineError) : DownloadEvent
}
