package io.github.sriram_pr.snaggle.engine.chaquopy

import android.content.res.AssetManager
import io.github.sriram_pr.snaggle.engine.Channel
import io.github.sriram_pr.snaggle.engine.YtDlpSignature
import io.github.sriram_pr.snaggle.engine.YtDlpVersion
import org.json.JSONArray
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
 * Keeps the active yt-dlp plus up to [KEEP_OLDER] older versions on disk (for rollback, like
 * old kernels kept in a boot menu) and records them in state.json. A version the user rolled back
 * from can be held: automatic updates then skip it until something newer is released.
 * Not thread-safe; callers serialize access.
 */
class YtDlpStore(private val root: File, private val assets: AssetManager) {
    private val stateFile = File(root, "state.json")

    var active: Installed? = null
        private set
    /** Older installed versions, most recently used first. */
    var older: List<Installed> = emptyList()
        private set
    val previous: Installed? get() = older.firstOrNull()
    /** Version the user rolled back from; automatic updates skip versions up to and including it. */
    var held: String? = null
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

    /**
     * Downloads [version] if it isn't on disk yet, verifying it against the release's SHA2-256SUMS,
     * which must carry a valid signature by yt-dlp's release key.
     */
    fun download(channel: Channel, version: String): Installed {
        val target = Installed(channel, version, dirFor(channel, version))
        if (target.zip.exists()) return target
        val base = "https://github.com/${repo(channel)}/releases/download/$version"
        val sums = fetch("$base/SHA2-256SUMS")
        if (!YtDlpSignature.verify(sums, fetch("$base/SHA2-256SUMS.sig"))) {
            throw IOException("Bad signature on SHA2-256SUMS of yt-dlp $version")
        }
        val expected = sums.decodeToString().lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1] == "yt-dlp" }?.get(0)
            ?: throw IOException("yt-dlp missing from SHA2-256SUMS of $version")
        val bytes = fetch("$base/yt-dlp")
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (!actual.equals(expected, ignoreCase = true)) throw IOException("Checksum mismatch for yt-dlp $version")
        writeAtomically(target.dir) { tmp -> File(tmp, "yt-dlp.zip").writeBytes(bytes) }
        return target
    }

    /** Makes [next] active; the old active joins the older list and anything beyond it is deleted. */
    fun activate(next: Installed) {
        val current = active
        if (current?.id != next.id) {
            older = (listOfNotNull(current) + older).filter { it.id != next.id }.take(KEEP_OLDER)
            active = next
        }
        writeState()
        prune()
    }

    /** Switches to an installed older version. Returns it, or null if it isn't installed. */
    fun use(id: String): Installed? {
        val target = older.firstOrNull { it.id == id && it.zip.exists() } ?: return null
        older = (listOfNotNull(active) + older).filter { it.id != id }.take(KEEP_OLDER)
        active = target
        writeState()
        return target
    }

    /** Switches to the most recently used older version. */
    fun rollback(): Installed? = previous?.let { use(it.id) }

    fun hold(version: String?) {
        held = version
        writeState()
    }

    /** Deletes a version that failed to load so a later update re-downloads it instead of reusing it. */
    fun discard(broken: Installed) {
        if (active?.id == broken.id) return
        older = older.filter { it.id != broken.id }
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
        val keep = setOfNotNull(active?.id, stateFile.name) + older.map { it.id }
        root.listFiles()?.filter { it.name !in keep }?.forEach { it.deleteRecursively() }
    }

    private fun readState() {
        if (!stateFile.exists()) return
        val json = JSONObject(stateFile.readText())
        active = parse(json.optString("active"))
        val ids = json.optJSONArray("older")?.let { a -> List(a.length()) { a.getString(it) } }
            ?: listOf(json.optString("previous")) // state written before multi-version rollback
        older = ids.mapNotNull(::parse).take(KEEP_OLDER)
        held = json.optString("held").ifEmpty { null }
    }

    private fun writeState() {
        val json = JSONObject().put("active", active?.id).put("older", JSONArray(older.map { it.id })).put("held", held)
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
        const val KEEP_OLDER = 3
    }
}
