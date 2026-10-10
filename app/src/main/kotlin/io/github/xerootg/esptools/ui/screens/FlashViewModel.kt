package io.github.xerootg.esptools.ui.screens

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.FlashPart
import io.github.xerootg.esploader.manifest.FlashManifest
import io.github.xerootg.esploader.util.parseAddress
import io.github.xerootg.esptools.firmware.FirmwareSources
import io.github.xerootg.esptools.firmware.ImportedBundle
import io.github.xerootg.esptools.firmware.LoadedFile
import io.github.xerootg.esptools.firmware.ReleaseAsset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class FlashEntry(val id: Long, val offsetText: String, val file: LoadedFile, val note: String? = null) {
    val offset: Long? get() = runCatching { parseAddress(offsetText) }.getOrNull()
}

data class FlashOptionsState(
    val flashMode: String = "keep",
    val flashSize: String = "keep",
    val flashFreq: String = "keep",
    val eraseAll: Boolean = false,
    val force: Boolean = false,
)

/** How to turn an imported bundle into flash entries. */
enum class BundleMode(val label: String, val description: String) {
    COMPONENTS("Components", "Bootloader, partition table, OTA data and app at the offsets from the manifest"),
    MERGED("Merged image", "Single merged image written at 0x0 (full install)"),
    APP_ONLY("App only", "Only the application image (keeps NVS / data partitions)"),
    ALL_BINS("All .bin files", "Every .bin in the archive with guessed offsets; review offsets before flashing"),
}

class FlashViewModel(app: Application) : AndroidViewModel(app) {
    private val ids = AtomicLong(1)
    private val _entries = MutableStateFlow<List<FlashEntry>>(emptyList())
    val entries: StateFlow<List<FlashEntry>> = _entries
    private val _options = MutableStateFlow(FlashOptionsState())
    val options: StateFlow<FlashOptionsState> = _options
    private val _pendingBundle = MutableStateFlow<ImportedBundle?>(null)
    val pendingBundle: StateFlow<ImportedBundle?> = _pendingBundle
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus
    private val _releaseAssets = MutableStateFlow<Pair<String, List<ReleaseAsset>>?>(null)
    val releaseAssets: StateFlow<Pair<String, List<ReleaseAsset>>?> = _releaseAssets
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    /** Manifest that supplied the current entries (for flash settings and chip family hints). */
    var manifest: FlashManifest? = null
        private set

    /** Bootloader offset of the connected chip, supplied by the screen for offset guessing. */
    var bootloaderOffset: Long? = null

    fun clearError() { _error.value = null }

    fun guessOffset(name: String): Long? {
        val n = name.lowercase()
        return when {
            n.contains("merged") || n.contains("full") || n.contains("factory") || n.contains("combined") -> 0L
            n.contains("bootloader") -> bootloaderOffset
            n.contains("partition") -> 0x8000L
            n.contains("ota_data") || n.contains("otadata") -> 0xD000L
            n.contains("nvs") -> 0x9000L
            else -> 0x10000L
        }
    }

    fun addFiles(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                try {
                    _importStatus.value = "Reading ${FirmwareSources.displayName(getApplication(), uri)}"
                    val f = FirmwareSources.readUri(getApplication(), uri)
                    if (FirmwareSources.isZip(f.name, f.data)) {
                        _pendingBundle.value = FirmwareSources.importZip(f.name, f.data)
                    } else if (FirmwareSources.isJson(f.name, f.data) && f.name.endsWith(".json")) {
                        _error.value = "Manifest JSON files must be imported from a URL (parts are downloaded relative to it) or inside a zip."
                    } else {
                        addEntry(f, guessOffset(f.name))
                    }
                } catch (e: Exception) {
                    _error.value = e.message ?: "Import failed"
                } finally { _importStatus.value = null }
            }
        }
    }

    fun importUrl(url: String) {
        viewModelScope.launch {
            try {
                _importStatus.value = "Downloading…"
                val bundle = FirmwareSources.importUrl(url.trim()) { _importStatus.value = it }
                if (bundle.manifests.isEmpty() && bundle.files.size == 1 && bundle.binFiles.size == 1) {
                    addEntry(bundle.binFiles.single(), guessOffset(bundle.binFiles.single().name))
                } else _pendingBundle.value = bundle
            } catch (e: Exception) {
                _error.value = e.message ?: "Download failed"
            } finally { _importStatus.value = null }
        }
    }

    fun fetchRelease(url: String) {
        viewModelScope.launch {
            try {
                _importStatus.value = "Fetching release…"
                _releaseAssets.value = FirmwareSources.githubReleaseAssets(url)
            } catch (e: Exception) {
                _error.value = e.message ?: "Fetch failed"
            } finally { _importStatus.value = null }
        }
    }

    fun dismissRelease() { _releaseAssets.value = null }
    fun dismissBundle() { _pendingBundle.value = null }

    fun applyBundle(bundle: ImportedBundle, mode: BundleMode, chipFamily: String?) {
        val m = bundle.manifests.firstOrNull { it.chipFamily == null || chipFamily == null || it.chipFamily.equals(chipFamily, true) } ?: bundle.manifests.firstOrNull()
        val list = mutableListOf<FlashEntry>()
        when (mode) {
            BundleMode.COMPONENTS -> {
                if (m == null) { _error.value = "No manifest in bundle"; return }
                for (p in m.parts) {
                    val f = bundle.resolve(p) ?: run { _error.value = "File ${p.path} missing from bundle"; return }
                    list += FlashEntry(ids.getAndIncrement(), hexText(p.offset), f, "from manifest")
                }
            }
            BundleMode.MERGED -> {
                val f = bundle.mergedImage ?: run { _error.value = "No merged image in bundle"; return }
                list += FlashEntry(ids.getAndIncrement(), "0x0", f, "merged image")
            }
            BundleMode.APP_ONLY -> {
                if (m == null) { _error.value = "No manifest in bundle"; return }
                val p = m.parts.maxByOrNull { it.offset }!!
                val f = bundle.resolve(p) ?: run { _error.value = "File ${p.path} missing"; return }
                list += FlashEntry(ids.getAndIncrement(), hexText(p.offset), f, "app")
            }
            BundleMode.ALL_BINS -> bundle.binFiles.forEach { f -> list += FlashEntry(ids.getAndIncrement(), guessOffset(f.name)?.let { hexText(it) } ?: "", f, "guessed offset") }
        }
        manifest = m
        _entries.update { it + list }
        if (m != null) {
            _options.update { o -> o.copy(flashMode = m.flashMode ?: o.flashMode, flashSize = m.flashSize ?: o.flashSize, flashFreq = m.flashFreq ?: o.flashFreq) }
        }
        _pendingBundle.value = null
    }

    private fun hexText(v: Long) = "0x" + v.toString(16)

    fun addEntry(file: LoadedFile, offset: Long?) {
        _entries.update { it + FlashEntry(ids.getAndIncrement(), offset?.let { o -> hexText(o) } ?: "", file) }
    }

    fun setOffset(id: Long, text: String) = _entries.update { list -> list.map { if (it.id == id) it.copy(offsetText = text) else it } }
    fun remove(id: Long) = _entries.update { list -> list.filter { it.id != id } }
    fun clear() { _entries.value = emptyList(); manifest = null }
    fun updateOptions(block: (FlashOptionsState) -> FlashOptionsState) = _options.update(block)

    /** Validate and build the parts, sorted by offset. */
    fun buildParts(): List<FlashPart> {
        val es = _entries.value
        if (es.isEmpty()) throw EspException("Add at least one file to flash")
        val parts = es.map { e ->
            val off = e.offset ?: throw EspException("Invalid offset '${e.offsetText}' for ${e.file.name}")
            FlashPart(off, e.file.data, e.file.name)
        }.sortedBy { it.address }
        for (i in 1 until parts.size) {
            val prev = parts[i - 1]
            if (prev.address + prev.data.size > parts[i].address) throw EspException("${prev.name} (${hexText(prev.address)}, ${prev.data.size} bytes) overlaps ${parts[i].name} at ${hexText(parts[i].address)}")
        }
        return parts
    }
}
