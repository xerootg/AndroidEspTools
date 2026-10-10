package io.github.xerootg.esptools.firmware

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.manifest.FlashManifest
import io.github.xerootg.esploader.manifest.FlashManifests
import io.github.xerootg.esploader.manifest.ManifestPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A firmware file loaded into memory. */
data class LoadedFile(val name: String, val data: ByteArray, val origin: String) {
    val sizeText: String get() = when {
        data.size >= 1024 * 1024 -> "%.2f MB".format(data.size / 1048576.0)
        data.size >= 1024 -> "%.1f KB".format(data.size / 1024.0)
        else -> "${data.size} B"
    }
}

/** Files extracted from a zip / URL together with the manifest that describes their offsets. */
class ImportedBundle(
    val name: String,
    val files: Map<String, LoadedFile>,
    val manifests: List<FlashManifest>,
) {
    /** The merged "flash at 0x0" image if the bundle contains one. */
    val mergedImage: LoadedFile? get() = files.values.firstOrNull { it.name.lowercase().let { n -> n.endsWith(".bin") && (n.contains("merged") || n.contains("full")) } }
    val appImage: LoadedFile? get() = manifests.firstOrNull()?.let { m -> m.parts.maxByOrNull { it.offset }?.let { resolve(it) } }
    val binFiles: List<LoadedFile> get() = files.values.filter { it.name.lowercase().endsWith(".bin") }

    /** Match a manifest path against the zip entries: exact, then by basename. */
    fun resolve(part: ManifestPart): LoadedFile? {
        files[part.path]?.let { return it }
        val base = part.path.substringAfterLast('/')
        return files.values.firstOrNull { it.name == base || it.name.endsWith("/$base") }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

object FirmwareSources {
    private val json = Json { ignoreUnknownKeys = true }

    fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) { val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) c.getString(i)?.let { return it } }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    suspend fun readUri(context: Context, uri: Uri): LoadedFile = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw EspException("Cannot open $name")
        LoadedFile(name, data, uri.toString())
    }

    fun isZip(name: String, data: ByteArray) =
        name.lowercase().endsWith(".zip") || (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte())

    fun isJson(name: String, data: ByteArray) =
        name.lowercase().endsWith(".json") || data.take(64).map { it.toInt().toChar() }.joinToString("").trimStart().startsWith("{")

    /** Unpack a zip, strip the common top-level directory, and parse any manifest it contains. */
    fun importZip(name: String, zip: ByteArray): ImportedBundle {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                if (e.name.startsWith("__MACOSX/")) continue
                entries[e.name] = zis.readBytes()
                zis.closeEntry()
            }
        }
        if (entries.isEmpty()) throw EspException("Zip is empty")
        // Strip a single common top-level directory (e.g. "rrcloud-ingest-0.1.0-esp32p4/")
        val tops = entries.keys.map { it.substringBefore('/', "") }.toSet()
        val prefix = if (tops.size == 1 && tops.first().isNotEmpty() && entries.keys.all { it.contains('/') }) tops.first() + "/" else ""
        val files = entries.entries.associate { (k, v) -> val n = k.removePrefix(prefix); n to LoadedFile(n, v, "$name!$k") }
        val manifests = mutableListOf<FlashManifest>()
        files.values.filter { it.name.endsWith(".json") }.forEach { f ->
            try { manifests += FlashManifests.parse(String(f.data), name.removeSuffix(".zip")) } catch (e: EspException) { /* not a manifest */ }
        }
        return ImportedBundle(name, files, manifests)
    }

    /** Download with redirects. [progress] gets (downloaded, total or -1). */
    suspend fun download(url: String, progress: ((Long, Long) -> Unit)? = null): ByteArray = withContext(Dispatchers.IO) {
        var current = url
        repeat(6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "AndroidEspTools")
            conn.setRequestProperty("Accept", "application/octet-stream, application/json, */*")
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw EspException("Redirect without location")
                conn.disconnect()
                current = URL(URL(current), loc).toString()
                return@repeat
            }
            if (code !in 200..299) { conn.disconnect(); throw EspException("HTTP $code for $current") }
            val total = conn.contentLengthLong
            val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 1 shl 20)
            conn.inputStream.use { ins ->
                val buf = ByteArray(64 * 1024)
                var n: Int; var done = 0L
                while (ins.read(buf).also { n = it } > 0) { out.write(buf, 0, n); done += n; progress?.invoke(done, total) }
            }
            conn.disconnect()
            return@withContext out.toByteArray()
        }
        throw EspException("Too many redirects")
    }

    /** Import a URL pointing at a zip, a manifest JSON (parts downloaded relative to it) or a .bin. */
    suspend fun importUrl(url: String, progress: ((String) -> Unit)? = null): ImportedBundle {
        progress?.invoke("Downloading ${url.substringAfterLast('/')}")
        val data = download(url) { d, t -> progress?.invoke("Downloading ${url.substringAfterLast('/')}: ${d / 1024} KB" + if (t > 0) " / ${t / 1024} KB" else "") }
        val name = url.substringAfterLast('/').substringBefore('?').ifEmpty { "download" }
        if (isZip(name, data)) return importZip(name, data)
        if (isJson(name, data)) {
            val manifests = FlashManifests.parse(String(data), name)
            val files = LinkedHashMap<String, LoadedFile>()
            for (m in manifests) for (p in m.parts) {
                if (p.path in files) continue
                val partUrl = URL(URL(url), p.path).toString()
                progress?.invoke("Downloading ${p.path}")
                files[p.path] = LoadedFile(p.path, download(partUrl), partUrl)
            }
            return ImportedBundle(name, files, manifests)
        }
        return ImportedBundle(name, mapOf(name to LoadedFile(name, data, url)), emptyList())
    }

    /**
     * Resolve a GitHub release page (…/releases/tag/X, …/releases/latest or an API URL) to its assets.
     */
    suspend fun githubReleaseAssets(input: String): Pair<String, List<ReleaseAsset>> {
        val t = input.trim().removeSuffix("/")
        val m = Regex("""^(?:https?://)?(?:www\.)?github\.com/([^/]+)/([^/]+)(?:/releases(?:/tag/([^/]+)|/(latest))?)?$""").find(t)
            ?: throw EspException("Enter a GitHub release URL such as https://github.com/owner/repo/releases/tag/v1.0 (or /releases/latest)")
        val (owner, repo, tag, latest) = m.destructured
        val api = when {
            tag.isNotEmpty() -> "https://api.github.com/repos/$owner/$repo/releases/tags/$tag"
            else -> "https://api.github.com/repos/$owner/$repo/releases" + if (latest.isNotEmpty()) "/latest" else "?per_page=10"
        }
        val body = String(download(api))
        val el = json.parseToJsonElement(body)
        val release = if (el is kotlinx.serialization.json.JsonArray) {
            // No tag given: prefer the newest release (pre-releases included) that has assets.
            el.jsonArray.map { it.jsonObject }.firstOrNull { (it["assets"]?.jsonArray?.size ?: 0) > 0 } ?: throw EspException("No releases with assets found")
        } else el.jsonObject
        val title = release["name"]?.jsonPrimitive?.content?.ifBlank { null } ?: release["tag_name"]?.jsonPrimitive?.content ?: "release"
        val assets = release["assets"]?.jsonArray?.map { a ->
            val o = a.jsonObject
            ReleaseAsset(o["name"]!!.jsonPrimitive.content, o["browser_download_url"]!!.jsonPrimitive.content, o["size"]?.jsonPrimitive?.long ?: 0)
        } ?: emptyList()
        return title to assets
    }
}
