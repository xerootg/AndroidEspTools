package io.github.xerootg.esploader

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.Base64

/** A flasher stub binary ready to be uploaded to RAM. */
class StubImage(
    val name: String,
    val entry: Long,
    val text: ByteArray,
    val textStart: Long,
    val data: ByteArray?,
    val dataStart: Long?,
    val bssStart: Long?,
) {
    /** Address ranges occupied by the running stub: (start, end) for IRAM and DRAM. */
    fun residentRanges(): List<Pair<Long, Long>> {
        val ranges = mutableListOf(textStart to textStart + text.size)
        if (data != null && dataStart != null) ranges += (bssStart ?: dataStart) to (dataStart + data.size)
        return ranges
    }
}

/** Loads the bundled esp-flasher-stub JSON blobs (see resources/esploader/stubs/README.md). */
object StubFlasher {
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = HashMap<String, StubImage>()

    fun load(jsonName: String): StubImage = synchronized(cache) {
        cache.getOrPut(jsonName) {
            val stream = StubFlasher::class.java.getResourceAsStream("/esploader/stubs/$jsonName")
                ?: throw EspException("Flasher stub data is missing for $jsonName. The esploader resources are incomplete.")
            val obj = json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
            val b64 = Base64.getDecoder()
            StubImage(
                name = jsonName,
                entry = obj.getValue("entry").jsonPrimitive.long,
                text = b64.decode(obj.getValue("text").jsonPrimitive.content),
                textStart = obj.getValue("text_start").jsonPrimitive.long,
                data = obj["data"]?.let { b64.decode(it.jsonPrimitive.content) },
                dataStart = obj["data_start"]?.jsonPrimitive?.long,
                bssStart = obj["bss_start"]?.jsonPrimitive?.long,
            )
        }
    }
}
