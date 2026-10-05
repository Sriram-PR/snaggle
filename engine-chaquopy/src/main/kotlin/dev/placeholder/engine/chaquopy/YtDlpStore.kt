package dev.placeholder.engine.chaquopy

import android.content.res.AssetManager
import dev.placeholder.engine.Channel
import dev.placeholder.engine.YtDlpVersion
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** One yt-dlp zipapp on disk: filesDir/ytdlp/<channel>-<version>/yt-dlp.zip. */
data class Installed(val channel: Channel, val version: String, val dir: File) {
    val zip: File get() = File(dir, "yt-dlp.zip")
    val id: String get() = dir.name
}

/**
 * Keeps at most two yt-dlp versions on disk (active + previous, for rollback) and records
 * which is active in state.json. Not thread-safe; callers serialize access.
 */
class YtDlpStore(private val root: File, private val assets: AssetManager) {
    private val stateFile = File(root, "state.json")

    var active: Installed? = null
        private set
    var previous: Installed? = null
        private set

    /** Loads state; installs the APK baseline if nothing is active or the baseline is newer (app update). */
    fun ensureActive(): Installed {
        root.mkdirs()
        readState()
        val baselineVersion = assets.open("ytdlp/version.txt").bufferedReader().use { it.readText().trim() }
        val current = active
        if (current == null || YtDlpVersion.isNewer(baselineVersion, current.version)) {
            val baseline = Installed(Channel.STABLE, baselineVersion, dirFor(Channel.STABLE, baselineVersion))
            if (!baseline.zip.exists()) {
                writeAtomically(baseline.dir) { tmp ->
                    assets.open("ytdlp/yt-dlp.zip").use { input -> File(tmp, "yt-dlp.zip").outputStream().use { input.copyTo(it) } }
                }
            }
            activate(baseline)
        }
        return active!!
    }

    /** Latest released version for [channel], read from GitHub's releases/latest redirect (no API rate limit). */
    fun latestVersion(channel: Channel): String {
        val conn = URI("https://github.com/${repo(channel)}/releases/latest").toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            val location = conn.getHeaderField("Location")
                ?: throw IOException("No redirect from releases/latest (HTTP ${conn.responseCode})")
            return location.substringAfterLast("/tag/")
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [version] if it isn't on disk yet, verifying it against the release's SHA2-256SUMS. */
    fun download(channel: Channel, version: String): Installed {
        val target = Installed(channel, version, dirFor(channel, version))
        if (target.zip.exists()) return target
        val base = "https://github.com/${repo(channel)}/releases/download/$version"
        val expected = fetch("$base/SHA2-256SUMS").decodeToString().lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1] == "yt-dlp" }?.get(0)
            ?: throw IOException("yt-dlp missing from SHA2-256SUMS of $version")
        val bytes = fetch("$base/yt-dlp")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("Checksum mismatch for yt-dlp $version")
        writeAtomically(target.dir) { tmp -> File(tmp, "yt-dlp.zip").writeBytes(bytes) }
        return target
    }

    /** Makes [next] active, keeps the old active as previous, and deletes everything else. */
    fun activate(next: Installed) {
        val current = active
        if (current?.id != next.id) {
            previous = current
            active = next
        }
        writeState()
        prune()
    }

    /** Swaps active and previous. Returns the new active, or null if there is nothing to roll back to. */
    fun rollback(): Installed? {
        val target = previous?.takeIf { it.zip.exists() } ?: return null
        previous = active
        active = target
        writeState()
        return target
    }

    /** Deletes a version that failed to load so a later update re-downloads it instead of reusing it. */
    fun discard(broken: Installed) {
        if (active?.id == broken.id) return
        if (previous?.id == broken.id) previous = null
        broken.dir.deleteRecursively()
        writeState()
    }

    private fun fetch(url: String): ByteArray {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeAtomically(dir: File, write: (File) -> Unit) {
        val tmp = File(root, "${dir.name}.tmp").apply { deleteRecursively(); mkdirs() }
        write(tmp)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IOException("Could not move $tmp to $dir")
    }

    private fun prune() {
        val keep = setOfNotNull(active?.id, previous?.id, stateFile.name)
        root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun readState() {
        if (!stateFile.exists()) return
        val json = JSONObject(stateFile.readText())
        active = parse(json.optString("active"))
        previous = parse(json.optString("previous"))
    }

    private fun writeState() {
        val json = JSONObject().put("active", active?.id).put("previous", previous?.id)
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(stateFile)) throw IOException("Could not write $stateFile")
    }

    private fun parse(id: String): Installed? {
        if (id.isEmpty()) return null
        val channel = Channel.entries.firstOrNull { id.startsWith(it.name.lowercase() + "-") } ?: return null
        val installed = Installed(channel, id.substringAfter('-'), File(root, id))
        return installed.takeIf { it.zip.exists() }
    }

    private fun dirFor(channel: Channel, version: String) = File(root, "${channel.name.lowercase()}-$version")

    private fun repo(channel: Channel) = when (channel) {
        Channel.STABLE -> "yt-dlp/yt-dlp"
        Channel.NIGHTLY -> "yt-dlp/yt-dlp-nightly-builds"
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}
