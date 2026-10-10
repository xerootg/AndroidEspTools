package io.github.xerootg.esploader.image

import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.chips.ChipDefinition
import io.github.xerootg.esploader.chips.Chips
import io.github.xerootg.esploader.util.hex
import io.github.xerootg.esploader.util.readLe16
import io.github.xerootg.esploader.util.readLe32
import io.github.xerootg.esploader.util.sha256
import io.github.xerootg.esploader.util.u8

class ImageSegment(val addr: Long, val data: ByteArray, val fileOffset: Int) {
    val size get() = data.size
    fun memoryType(chip: ChipDefinition?): String {
        if (chip == null) return ""
        return chip.memoryMap.filter { addr >= it.start && addr < it.end }.joinToString("/") { it.name }
    }
}

/** ESP-IDF `esp_app_desc_t`, found at the start of the first DROM segment of an application. */
data class AppDescription(
    val secureVersion: Long,
    val version: String,
    val projectName: String,
    val time: String,
    val date: String,
    val idfVersion: String,
    val elfSha256: String,
    val minEfuseBlkRevFull: Int,
    val maxEfuseBlkRevFull: Int,
    val mmuPageSize: Int?,
)

/** ESP-IDF `esp_bootloader_desc_t`. */
data class BootloaderDescription(val version: Long, val idfVersion: String, val dateTime: String)

/** A parsed ESP firmware image (ESP32-family or ESP8266 v1 layout). */
class FirmwareImage(
    val esp8266: Boolean,
    val flashMode: Int,
    val flashSizeFreq: Int,
    val entrypoint: Long,
    val segments: List<ImageSegment>,
    val checksum: Int,
    val calculatedChecksum: Int,
    /** Extended header (ESP32-family only). */
    val wpPin: Int,
    val chipId: Int,
    val minRev: Int,
    val minRevFull: Int,
    val maxRevFull: Int,
    val appendDigest: Boolean,
    val storedDigest: ByteArray?,
    val calculatedDigest: ByteArray?,
    /** Bytes covered by the digest: header + segments + checksum padding. */
    val dataLength: Int,
) {
    val chip: ChipDefinition? get() = if (esp8266) Chips.byName("esp8266") else Chips.byImageChipId(chipId)
    val flashSizeCode get() = flashSizeFreq and 0xF0
    val flashFreqCode get() = flashSizeFreq and 0x0F
    val flashModeName get() = FLASH_MODES.entries.firstOrNull { it.value == flashMode }?.key ?: "unknown(0x%x)".format(flashMode)
    fun flashSizeName(): String = chip?.flashSizes?.entries?.firstOrNull { it.value == flashSizeCode }?.key ?: "unknown(0x%x)".format(flashSizeCode)
    fun flashFreqName(): String = chip?.flashFrequency?.entries?.firstOrNull { it.value == flashFreqCode }?.key ?: "unknown(0x%x)".format(flashFreqCode)
    val checksumValid get() = checksum == calculatedChecksum
    val digestValid: Boolean? get() = if (storedDigest != null && calculatedDigest != null) storedDigest.contentEquals(calculatedDigest) else null

    val appDescription: AppDescription? by lazy { segments.firstNotNullOfOrNull { parseAppDescription(it.data) } }
    val bootloaderDescription: BootloaderDescription? by lazy { segments.firstNotNullOfOrNull { parseBootloaderDescription(it.data) } }

    companion object {
        val FLASH_MODES = mapOf("qio" to 0, "qout" to 1, "dio" to 2, "dout" to 3)
        const val ESP8266_V2_MAGIC = 0xEA

        /** Cheap check: does [data] start with the image magic byte? */
        fun looksLikeImage(data: ByteArray) = data.size >= 8 && data.u8(0) == EspLoader.ESP_IMAGE_MAGIC

        /**
         * Parse an image. [esp8266] selects the 8-byte-header ESP8266 layout; when null the
         * layout is guessed (ESP8266 v2 magic 0xEA, otherwise the ESP32 layout with extended header).
         */
        fun parse(data: ByteArray, esp8266: Boolean? = null): FirmwareImage {
            if (data.size < 8) throw EspException("Image is too short (${data.size} bytes)")
            val magic = data.u8(0)
            if (magic == ESP8266_V2_MAGIC) {
                // ESP8266 "v2" OTA image: the v1 image is embedded after one IROM segment
                val segAddr = data.readLe32(8); val segSize = data.readLe32(12).toInt()
                val inner = data.copyOfRange(16 + segSize, data.size)
                val v1 = parse(inner, esp8266 = true)
                val irom = ImageSegment(segAddr, data.copyOfRange(16, 16 + segSize), 16)
                return FirmwareImage(true, data.u8(2), data.u8(3), data.readLe32(4), listOf(irom) + v1.segments, v1.checksum, v1.calculatedChecksum,
                    0, -1, 0, 0, 0, false, null, null, data.size)
            }
            if (magic != EspLoader.ESP_IMAGE_MAGIC) throw EspException("Invalid firmware image magic=0x%02x".format(magic))
            val segmentCount = data.u8(1)
            val flashMode = data.u8(2)
            val flashSizeFreq = data.u8(3)
            val entry = data.readLe32(4)
            var pos = 8
            var is8266 = esp8266 ?: false
            var wpPin = 0; var chipId = -1; var minRev = 0; var minRevFull = 0; var maxRevFull = 0; var appendDigest = false
            if (esp8266 == null) {
                // Heuristic: a valid ESP32 extended header has append_digest in {0,1} and a known chip id.
                if (data.size >= 24) {
                    val ad = data.u8(8 + 15)
                    val cid = data.readLe16(8 + 4)
                    is8266 = !(ad in 0..1 && Chips.byImageChipId(cid) != null)
                } else is8266 = true
            }
            if (!is8266) {
                if (data.size < 24) throw EspException("Image too short for ESP32 extended header")
                wpPin = data.u8(8)
                chipId = data.readLe16(8 + 4)
                minRev = data.u8(8 + 6)
                minRevFull = data.readLe16(8 + 7)
                maxRevFull = data.readLe16(8 + 9)
                val ad = data.u8(8 + 15)
                if (ad !in 0..1) throw EspException("Invalid value for append_digest field (0x%02x). Should be 0 or 1.".format(ad))
                appendDigest = ad == 1
                pos = 24
            }
            val segments = ArrayList<ImageSegment>(segmentCount)
            var checksum = EspLoader.ESP_CHECKSUM_MAGIC
            repeat(segmentCount) {
                if (pos + 8 > data.size) throw EspException("End of file reading segment header at offset $pos")
                val addr = data.readLe32(pos); val size = data.readLe32(pos + 4).toInt()
                if (size < 0 || pos + 8 + size > data.size) throw EspException("End of file reading segment 0x%x, length %d (actual length %d)".format(addr, size, data.size - pos - 8))
                val seg = data.copyOfRange(pos + 8, pos + 8 + size)
                segments += ImageSegment(addr, seg, pos)
                checksum = EspLoader.checksum(seg, checksum)
                pos += 8 + size
            }
            // checksum is in the last byte of the 16-byte-aligned padding
            val padded = (pos + 16) / 16 * 16 // align_file_position: pad so file is a multiple of 16, checksum last
            val checksumPos = padded - 1
            if (checksumPos >= data.size) throw EspException("Image truncated before checksum")
            val stored = data.u8(checksumPos)
            val dataLength = padded
            var storedDigest: ByteArray? = null; var calcDigest: ByteArray? = null
            if (!is8266 && appendDigest) {
                if (dataLength + 32 <= data.size) storedDigest = data.copyOfRange(dataLength, dataLength + 32)
                calcDigest = sha256(data, 0, dataLength)
            }
            if (segmentCount > 16) throw EspException("Invalid segment count $segmentCount (max 16). Usually this indicates a linker script problem.")
            return FirmwareImage(is8266, flashMode, flashSizeFreq, entry, segments, stored, checksum,
                wpPin, chipId, minRev, minRevFull, maxRevFull, appendDigest, storedDigest, calcDigest, dataLength)
        }

        private fun cString(b: ByteArray, from: Int, len: Int): String {
            val end = minOf(from + len, b.size)
            var n = from
            while (n < end && b[n].toInt() != 0) n++
            return String(b, from, n - from, Charsets.UTF_8).filter { it.code in 0x20..0x7E }
        }

        fun parseAppDescription(seg: ByteArray): AppDescription? {
            if (seg.size < 256) return null
            if (seg.readLe32(0) != 0xABCD5432L) return null
            val mmu = seg.u8(16 + 32 + 32 + 16 + 16 + 32 + 32 + 4)
            return AppDescription(
                secureVersion = seg.readLe32(4),
                version = cString(seg, 16, 32),
                projectName = cString(seg, 48, 32),
                time = cString(seg, 80, 16),
                date = cString(seg, 96, 16),
                idfVersion = cString(seg, 112, 32),
                elfSha256 = seg.copyOfRange(144, 176).hex(),
                minEfuseBlkRevFull = seg.readLe16(176),
                maxEfuseBlkRevFull = seg.readLe16(178),
                mmuPageSize = if (mmu != 0) (1 shl mmu) else null,
            )
        }

        fun parseBootloaderDescription(seg: ByteArray): BootloaderDescription? {
            if (seg.size < 80) return null
            if (seg.u8(0) != 0x50) return null
            val idf = cString(seg, 8, 32)
            if (!idf.startsWith("v")) return null
            return BootloaderDescription(seg.readLe32(4), idf, cString(seg, 40, 24))
        }
    }
}

/**
 * Patch the flash mode / size / frequency bytes of a bootloader image header (and recompute
 * the appended SHA-256 if present). Mirrors esptool's `_update_image_flash_params`.
 * Any null parameter is kept as-is. Returns the (possibly new) image bytes.
 */
fun patchFlashParams(chip: ChipDefinition, address: Long, image: ByteArray, flashMode: Int?, flashFreq: Int?, flashSize: Int?, log: io.github.xerootg.esploader.EspLog): ByteArray {
    if (image.size < 8) return image
    if (address != chip.bootloaderFlashOffset) return image
    if (flashMode == null && flashFreq == null && flashSize == null) return image
    if (image.u8(0) != EspLoader.ESP_IMAGE_MAGIC) {
        log.warn("Image file at 0x%x doesn't look like an image file, so not changing any flash settings.".format(address))
        return image
    }
    val parsed = try { FirmwareImage.parse(image, esp8266 = chip.chipName == "ESP8266") } catch (e: Exception) {
        log.warn("Image file at 0x%x is not a valid ${chip.chipName} image, so not changing any flash settings.".format(address))
        return image
    }
    var mode = image.u8(2)
    var freq = image.u8(3) and 0x0F
    var size = image.u8(3) and 0xF0
    if (flashMode != null) mode = flashMode
    if (flashFreq != null) freq = flashFreq
    if (flashSize != null) size = flashSize
    val newParams = byteArrayOf(mode.toByte(), (size + freq).toByte())
    if (newParams[0] == image[2] && newParams[1] == image[3]) return image
    val out = image.copyOf()
    out[2] = newParams[0]; out[3] = newParams[1]
    log.info("Flash parameters set to 0x%02x%02x.".format(out[2].toInt() and 0xFF, out[3].toInt() and 0xFF))
    val shaAppended = !parsed.esp8266 && parsed.appendDigest
    if (shaAppended && parsed.dataLength + 32 <= out.size) {
        val digest = sha256(out, 0, parsed.dataLength)
        System.arraycopy(digest, 0, out, parsed.dataLength, 32)
        log.info("SHA digest in image updated.")
    }
    return out
}
