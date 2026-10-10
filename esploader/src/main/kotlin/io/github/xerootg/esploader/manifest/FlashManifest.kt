package io.github.xerootg.esploader.manifest

import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.util.parseAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One file of a manifest: a relative path (inside a zip / next to the manifest / a URL) and its flash offset. */
data class ManifestPart(val path: String, val offset: Long)

/** A set of files to flash, as described by an ESP-IDF `flasher_args.json` or an ESP Web Tools manifest build. */
data class FlashManifest(
    val name: String?,
    val version: String?,
    /** Chip family name such as "ESP32-P4", or null if unspecified. */
    val chipFamily: String?,
    val parts: List<ManifestPart>,
    val flashMode: String? = null,
    val flashSize: String? = null,
    val flashFreq: String? = null,
    /** ESP Web Tools: offer to erase the whole flash for a new install. */
    val eraseFirst: Boolean = false,
    val source: Source,
) {
    enum class Source { ESP_IDF_FLASHER_ARGS, ESP_WEB_TOOLS }
}

/** Parsers for the two manifest formats found in the wild. */
object FlashManifests {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    const val ESP_IDF_FLASHER_ARGS_FILENAME = "flasher_args.json"

    /** Detect the format from the JSON keys and parse accordingly. */
    fun parse(text: String, name: String? = null): List<FlashManifest> {
        val obj = try { json.parseToJsonElement(text).jsonObject } catch (e: Exception) { throw EspException("Not a JSON manifest: ${e.message}") }
        return when {
            "flash_files" in obj -> listOf(parseFlasherArgs(obj, name))
            "builds" in obj -> parseWebTools(obj)
            else -> throw EspException("Unrecognised manifest: expected ESP-IDF flasher_args.json (flash_files) or an ESP Web Tools manifest (builds).")
        }
    }

    /** ESP-IDF build output: `{"flash_files": {"0x2000": "bootloader/bootloader.bin", ...}, "flash_settings": {...}}`. */
    fun parseFlasherArgs(obj: JsonObject, name: String? = null): FlashManifest {
        val files = obj["flash_files"]?.jsonObject ?: throw EspException("flasher_args.json has no flash_files")
        val parts = files.entries.map { (offset, path) -> ManifestPart(path.jsonPrimitive.content, parseAddress(offset)) }.sortedBy { it.offset }
        val settings = obj["flash_settings"]?.jsonObject
        val extra = obj["extra_esptool_args"]?.jsonObject
        val chip = extra?.get("chip")?.jsonPrimitive?.content
        return FlashManifest(
            name = name,
            version = null,
            chipFamily = chip?.let { normaliseChipFamily(it) },
            parts = parts,
            flashMode = settings?.get("flash_mode")?.jsonPrimitive?.content,
            flashSize = settings?.get("flash_size")?.jsonPrimitive?.content,
            flashFreq = settings?.get("flash_freq")?.jsonPrimitive?.content,
            source = FlashManifest.Source.ESP_IDF_FLASHER_ARGS,
        )
    }

    /** ESP Web Tools manifest: one [FlashManifest] per `builds[]` entry. */
    fun parseWebTools(obj: JsonObject): List<FlashManifest> {
        val name = obj["name"]?.jsonPrimitive?.content
        val version = obj["version"]?.jsonPrimitive?.content
        val erase = obj["new_install_prompt_erase"]?.jsonPrimitive?.booleanOrNull ?: false
        val builds = obj["builds"]?.jsonArray ?: throw EspException("Manifest has no builds")
        return builds.map { b ->
            val build = b.jsonObject
            val parts = build["parts"]?.jsonArray?.map { p ->
                val po = p.jsonObject
                val offsetEl = po["offset"]?.jsonPrimitive ?: throw EspException("Manifest part without offset")
                val offset = offsetEl.longOrNull ?: parseAddress(offsetEl.content)
                ManifestPart(po["path"]?.jsonPrimitive?.content ?: throw EspException("Manifest part without path"), offset)
            } ?: emptyList()
            FlashManifest(
                name = name, version = version,
                chipFamily = build["chipFamily"]?.jsonPrimitive?.content?.let { normaliseChipFamily(it) },
                parts = parts.sortedBy { it.offset },
                eraseFirst = erase,
                source = FlashManifest.Source.ESP_WEB_TOOLS,
            )
        }
    }

    /** "esp32p4", "ESP32-P4", "esp32_p4" → "ESP32-P4". */
    fun normaliseChipFamily(s: String): String {
        val t = s.trim().uppercase().replace("_", "").replace("-", "")
        if (t == "ESP8266") return "ESP8266"
        return if (t.startsWith("ESP32") && t.length > 5) "ESP32-" + t.substring(5) else t
    }
}
