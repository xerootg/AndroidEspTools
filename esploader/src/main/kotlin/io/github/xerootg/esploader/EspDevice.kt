package io.github.xerootg.esploader

import io.github.xerootg.esploader.chips.Esp8266
import io.github.xerootg.esploader.image.FirmwareImage
import io.github.xerootg.esploader.image.patchFlashParams
import io.github.xerootg.esploader.util.divRoundUp
import io.github.xerootg.esploader.util.formatMac
import io.github.xerootg.esploader.util.formatSize
import io.github.xerootg.esploader.util.md5Hex
import io.github.xerootg.esploader.util.padTo
import io.github.xerootg.esploader.util.parseSize
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One (address, data) pair to write. */
class FlashPart(val address: Long, val data: ByteArray, val name: String? = null)

data class WriteFlashOptions(
    /** "keep" or a name from the chip's flashFrequency table (e.g. "80m"). */
    val flashFreq: String = "keep",
    /** "keep", "qio", "qout", "dio", "dout". */
    val flashMode: String = "keep",
    /** "keep", "detect" or a size such as "4MB". */
    val flashSize: String = "keep",
    /** null = compress when the stub is running. */
    val compress: Boolean? = null,
    val eraseAll: Boolean = false,
    /** Skip safety checks (secure boot, chip id / revision mismatch, security features). */
    val force: Boolean = false,
    /** Verify every written region by MD5 (always on in esptool; exposed for speed). */
    val verify: Boolean = true,
)

data class FlashInfo(val flashId: Long, val vendorId: Int, val deviceId: Int, val sizeName: String?) {
    val sizeBytes: Long? get() = sizeName?.let { parseSize(it) }
}

data class ChipInfo(
    val chipName: String,
    val description: String,
    val features: List<String>,
    val crystalMhz: Int?,
    val macs: Map<String, String>,
    val usbMode: String?,
    val flash: FlashInfo?,
    val flashVoltage: String?,
    val secureBoot: Boolean?,
    val flashEncryption: Boolean?,
    val securityInfo: SecurityInfo?,
    val secureDownloadMode: Boolean,
    val stubRunning: Boolean,
    val chipRevision: Int?,
)

enum class ResetAfter { HARD_RESET, SOFT_RESET, NO_RESET, NO_RESET_STUB, WATCHDOG_RESET }

data class VerifyResult(val address: Long, val size: Int, val name: String?, val expectedMd5: String, val actualMd5: String) {
    val matches get() = expectedMd5.equals(actualMd5, ignoreCase = true)
}

/**
 * High level operations on a connected chip: the equivalents of the esptool sub-commands
 * (write-flash, read-flash, erase-flash, …). Wraps an [EspLoader].
 */
class EspDevice(val loader: EspLoader) {
    val log: EspLog get() = loader.log
    val chip get() = loader.chip
    val isStub get() = loader.isStub
    private val cancel get() = loader.cancel

    companion object {
        const val ADESTO_VENDOR_ID = 0x1F
        const val XMC_VENDOR_ID = 0x20
        val DETECTED_FLASH_SIZES_ADESTO = mapOf(0x04 to "512KB", 0x05 to "1MB", 0x06 to "2MB", 0x07 to "4MB", 0x08 to "8MB", 0x09 to "16MB")
        val DETECTED_FLASH_SIZES = mapOf(
            0x12 to "256KB", 0x13 to "512KB", 0x14 to "1MB", 0x15 to "2MB", 0x16 to "4MB", 0x17 to "8MB", 0x18 to "16MB",
            0x19 to "32MB", 0x1A to "64MB", 0x1B to "128MB", 0x1C to "256MB", 0x20 to "64MB", 0x21 to "128MB", 0x22 to "256MB",
            0x32 to "256KB", 0x33 to "512KB", 0x34 to "1MB", 0x35 to "2MB", 0x36 to "4MB", 0x37 to "8MB", 0x38 to "16MB",
            0x39 to "32MB", 0x3A to "64MB",
        )
        val FLASH_MODES = FirmwareImage.FLASH_MODES
    }

    // ------------------------------------------------------------------ session setup

    /**
     * Full connection sequence: reset into the bootloader, detect the chip, optionally run
     * the stub, raise the baud rate and attach the SPI flash.
     */
    fun connect(resetMode: ResetMode = ResetMode.DEFAULT, useStub: Boolean = true, baud: Int = 460800, attempts: Int = EspLoader.DEFAULT_CONNECT_ATTEMPTS) {
        loader.connect(resetMode, attempts)
        log.info("Chip is ${chipDescriptionSafe()}")
        if (useStub) loader.runStub()
        if (baud > loader.baudRate) {
            if (loader.usbMode() != null) {
                log.note("Native USB interface detected (${loader.usbMode()}), baud rate change is not needed.")
            } else {
                try { loader.changeBaud(baud) } catch (e: NotImplementedInRomException) { log.warn(e.message ?: "Baud change not supported") }
            }
        }
        attachFlash()
    }

    private fun chipDescriptionSafe(): String = try { chip.chipDescription(loader) } catch (e: EspException) { chip.chipName }

    /** Configure and attach the SPI flash, run the XMC start-up fix and verify the connection. */
    fun attachFlash() {
        if (!loader.isStub) {
            if (chip.chipName != "ESP32" || loader.secureDownloadMode) {
                log.info("Enabling default SPI flash mode...")
                loader.flashSpiAttach(0) // ROM loader doesn't enable flash unless we explicitly do it
            } else {
                // ROM doesn't attach in-package flash chips
                val pads = chip.chipSpiPads(loader)!!
                if (pads.any { it != 0 }) {
                    val (clk, q, d, hd, cs) = pads.toList()
                    val value = (hd.toLong() shl 24) or (cs.toLong() shl 18) or (d.toLong() shl 12) or (q.toLong() shl 6) or clk.toLong()
                    log.info("Attaching flash from eFuses' SPI pads configuration (CLK:$clk, Q:$q, D:$d, HD:$hd, CS:$cs)...")
                    loader.flashSpiAttach(value)
                } else {
                    log.info("Enabling default SPI flash mode...")
                    loader.flashSpiAttach(0)
                }
            }
        }
        if (!loader.secureDownloadMode) {
            try { flashXmcStartup() } catch (e: EspException) { if (e is EspCancelledException) throw e; log.trace("Unable to perform XMC flash chip startup sequence (${e.message}).") }
        }
        try {
            if (!loader.verifyFlashConnection()) {
                log.warn("Failed to communicate with the flash chip, read/write operations will fail. Try checking the chip connections or removing any other hardware connected to IOs.")
            }
        } catch (e: EspException) {
            if (e is EspCancelledException) throw e
            throw EspException("Unable to verify flash chip connection (${e.message}).", e)
        }
        if (!loader.secureDownloadMode) {
            // Reset the flash chip so the ROM does not inherit a complex state left by the app.
            try {
                loader.runSpiflashCommand(0x66) // reset enable
                loader.runSpiflashCommand(0x99) // reset
                loader.sleep(1)
            } catch (e: EspException) { if (e is EspCancelledException) throw e; log.trace("Unable to reset flash chip (${e.message}).") }
        }
    }

    private fun isXmcChipStrict(): Boolean {
        val info = flashInfo(cache = false)
        if (info.vendorId != XMC_VENDOR_ID) return false
        val mfid = (info.deviceId shr 8) and 0xFF
        val cpid = info.deviceId and 0xFF
        return when (mfid) {
            0x40 -> cpid in 0x13..0x20
            0x41 -> cpid in 0x17..0x20
            0x50 -> cpid in 0x15..0x16
            else -> false
        }
    }

    private fun flashXmcStartup() {
        if (isXmcChipStrict()) return // successful XMC boot-up detected by RDID
        val mfId = loader.readSpiflashSfdp(0x10, 8)
        if (mfId != XMC_VENDOR_ID.toLong()) return // non-XMC chip
        log.warn("XMC flash chip boot-up failure detected! Running XMC25QHxxC startup flow.")
        loader.runSpiflashCommand(0xB9) // Enter DPD
        loader.runSpiflashCommand(0x79) // Enter UDPD
        loader.runSpiflashCommand(0xFF) // Exit UDPD
        loader.sleep(2)
        loader.runSpiflashCommand(0xAB) // Release power-down
        loader.sleep(1)
        if (isXmcChipStrict()) log.info("XMC flash chip boot-up fix successful!")
        else log.warn("Could not confirm XMC flash chip boot-up fix. The flash chip may not work.")
    }

    // ------------------------------------------------------------------ info

    fun flashInfo(cache: Boolean = true): FlashInfo {
        val flashId = loader.flashId(cache)
        val vendorId = (flashId and 0xFF).toInt()
        val deviceId = (((flashId shr 16) and 0xFF) or (((flashId shr 8) and 0xFF) shl 8)).toInt()
        val size = if (vendorId == ADESTO_VENDOR_ID) DETECTED_FLASH_SIZES_ADESTO[((flashId shr 8) and 0x1F).toInt()]
        else DETECTED_FLASH_SIZES[(flashId shr 16).toInt()]
        return FlashInfo(flashId, vendorId, deviceId, size)
    }

    fun detectFlashSize(): String? {
        if (loader.secureDownloadMode) throw EspException("Detecting flash size is not supported in secure download mode. Need to manually specify flash size.")
        return flashInfo().sizeName
    }

    /** Gather everything the "chip info" screen shows. Individual failures are tolerated. */
    fun chipInfo(): ChipInfo {
        val l = loader
        fun <T> safe(block: () -> T): T? = try { block() } catch (e: EspCancelledException) { throw e } catch (e: Exception) { log.trace("info: ${e.message}"); null }
        val macs = safe { chip.readMacs(l).mapValues { formatMac(it.value) } } ?: emptyMap()
        val si = if (chip.securityInfoSupported) safe { l.getSecurityInfo() } else null
        return ChipInfo(
            chipName = chip.chipName,
            description = safe { chip.chipDescription(l) } ?: chip.chipName,
            features = safe { chip.chipFeatures(l) } ?: emptyList(),
            crystalMhz = safe { chip.crystalFreq(l) },
            macs = macs,
            usbMode = l.usbMode(),
            flash = if (l.secureDownloadMode) null else safe { flashInfo() },
            flashVoltage = safe { chip.flashVoltage(l) },
            secureBoot = if (l.secureDownloadMode || chip === Esp8266) null else safe { chip.secureBootEnabled(l) },
            flashEncryption = if (l.secureDownloadMode || chip === Esp8266) null else safe { chip.flashEncryptionEnabled(l) },
            securityInfo = si,
            secureDownloadMode = l.secureDownloadMode,
            stubRunning = l.isStub,
            chipRevision = safe { chip.chipRevision(l) },
        )
    }

    // ------------------------------------------------------------------ flash parameters

    /**
     * Configure the chip's flash size ("detect", "keep" or explicit). Returns the size actually
     * used for the image header ("keep" stays "keep").
     */
    fun setFlashParameters(flashSize: String = "keep"): String {
        log.info("Configuring flash size...")
        val keep = flashSize == "keep"
        var size: String? = flashSize
        if (flashSize == "detect") {
            size = detectFlashSize()
            if (size == null) { log.warn("Could not auto-detect flash size, defaulting to 4MB."); size = "4MB" }
            else log.info("Auto-detected flash size: $size")
        } else if (flashSize == "keep") {
            size = if (loader.secureDownloadMode) null else detectFlashSize()
            if (!loader.isStub) log.note("In case of failure, please set a specific flash size.")
        }
        if (size != null) {
            val bytes = parseSize(size)
            loader.flashSetParameters(bytes)
            if (!(loader.isStub && chip.flash32BitAddrSupported) && bytes > 16L * 1024 * 1024) {
                log.note("Flash sizes larger than 16MB are not fully supported. Change the flash size argument in case of a failure.")
            }
        }
        return if (keep) "keep" else size!!
    }

    // ------------------------------------------------------------------ write flash

    private fun imageIncompatibility(image: FirmwareImage, name: String?): String? {
        if (image.chipId != chip.imageChipId) {
            return (if (name == null) "Input does not contain" else "'$name' is not") + " an ${chip.chipName} image"
        }
        val desc = if (name == null) "Input" else "'$name'"
        val useRevFull = when {
            image.maxRevFull == 0 -> false
            image.maxRevFull == 65535 -> !(image.minRevFull == 0 && image.minRev != 0)
            else -> true
        }
        if (useRevFull) {
            val rev = chip.chipRevision(loader)
            if (rev in image.minRevFull..image.maxRevFull) return null
            val req = when {
                image.maxRevFull == 65535 -> "v${image.minRevFull / 100}.${image.minRevFull % 100} or higher"
                image.minRevFull == 0 -> "v${image.maxRevFull / 100}.${image.maxRevFull % 100} or lower"
                else -> "in range v${image.minRevFull / 100}.${image.minRevFull % 100} - v${image.maxRevFull / 100}.${image.maxRevFull % 100}"
            }
            return "$desc requires chip revision $req (this chip is revision v${rev / 100}.${rev % 100})"
        }
        val rev = if (chip.chipName == "ESP32-C3") chip.minorChipVersion(loader) else chip.majorChipVersion(loader)
        if (rev >= image.minRev) return null
        return "$desc requires chip revision ${image.minRev} or higher (this chip is revision $rev)"
    }

    private fun validateImageCompatibility(parts: List<FlashPart>) {
        for (p in parts) {
            val image = try { FirmwareImage.parse(p.data, esp8266 = chip === Esp8266) } catch (e: Exception) { continue }
            if (image.esp8266) continue
            val err = imageIncompatibility(image, p.name) ?: continue
            if (p.address == chip.bootloaderFlashOffset) throw EspException("$err. Enable 'force' to flash anyway.")
            log.note(err)
        }
    }

    private fun deflBlockTimeout(uncompressedSize: Long): Int {
        // The stub starts one flash-block erase before decompression and one more per deflate buffer cycle.
        val STUB_DEFLATE_BUFFER_SIZE = 32L * 1024; val FLASH_BLOCK_SIZE = 64L * 1024
        var size = uncompressedSize
        if (loader.isStub) size += (divRoundUp(uncompressedSize, STUB_DEFLATE_BUFFER_SIZE) + 1) * FLASH_BLOCK_SIZE
        return EspLoader.timeoutPerMb(EspLoader.ERASE_WRITE_TIMEOUT_PER_MB, size)
    }

    private fun zlibCompress(data: ByteArray): ByteArray {
        val d = Deflater(9)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) { val n = d.deflate(buf); out.write(buf, 0, n) }
        d.end()
        return out.toByteArray()
    }

    /**
     * Write [parts] to flash, the esptool `write-flash` algorithm: safety checks, header patching,
     * compression (stub), per-block upload with retries and MD5 verification of each region.
     */
    fun writeFlash(parts: List<FlashPart>, options: WriteFlashOptions = WriteFlashOptions(), progress: ProgressListener = ProgressListener.None) {
        val l = loader
        val compress = options.compress ?: l.isStub
        if (!options.force && chip !== Esp8266 && !l.secureDownloadMode) {
            if (chip.secureBootV1Enabled(l) && parts.any { it.address < 0x8000 }) {
                throw EspException("Secure Boot V1 detected, writing to flash regions < 0x8000 is disabled to protect the bootloader. Use 'force' to override, please use with caution, otherwise it may brick your device!")
            }
            if (chip.flashEncryptionEnabled(l) && chip.usesKeyManagerForFlashEncryption(l) && parts.any { it.address < 0x2000 }) {
                throw EspException("Flash encryption with Key Manager detected, writing to flash region 0x0-0x2000 is disabled to protect key recovery info. Use 'force' to override, please use with caution, otherwise it may brick your device!")
            }
            validateImageCompatibility(parts)
            if (chip.chipName != "ESP32" && l.secureDownloadMode && l.getSecurityInfo().flashEncryptionEnabled) {
                throw EspException("Detected flash encryption and secure download mode enabled.\nFlashing plaintext binary may brick your device! Use 'force' to override the warning.")
            }
            if (!l.secureDownloadMode && chip.encryptedDownloadDisabled(l) && chip.flashEncryptionEnabled(l)) {
                throw EspException("Detected flash encryption enabled and download manual encrypt disabled.\nData must be encrypted appropriately before flashing. Flashing plaintext data may brick your device! Use 'force' to override the warning.")
            }
        }
        val flashSize = setFlashParameters(options.flashSize)
        val setFlashSize = if (flashSize !in listOf("detect", "keep")) parseSize(flashSize) else null
        var flashEnd: Long?
        if (l.secureDownloadMode) flashEnd = setFlashSize
        else {
            val detected = detectFlashSize()
            flashEnd = detected?.let { parseSize(it) }
            if (setFlashSize != null && flashEnd != null && setFlashSize > flashEnd) log.warn("Set flash size $flashSize is larger than the available flash size of $detected.")
        }
        flashEnd = if (setFlashSize != null && flashEnd != null) minOf(setFlashSize, flashEnd) else flashEnd
        if (flashEnd != null) {
            for (p in parts) if (p.address + p.data.size > flashEnd) {
                val src = if (p.name == null) "Input image" else "File '${p.name}'"
                throw EspException("$src (length ${p.data.size}) at offset 0x%08x will not fit in $flashEnd bytes of flash. Change the flash size argument or flashing address.".format(p.address))
            }
        }
        if (options.eraseAll) eraseFlash(options.force)

        val flashModeCode = if (options.flashMode == "keep") null else FLASH_MODES[options.flashMode] ?: throw EspException("Unknown flash mode '${options.flashMode}'")
        val flashFreqCode = if (options.flashFreq == "keep") null else l.parseFlashFreqArg(options.flashFreq)
        val flashSizeCode = if (flashSize == "keep") null else l.parseFlashSizeArg(flashSize)

        val totalBytes = parts.sumOf { it.data.size.toLong() }
        var doneBefore = 0L
        val securityOn = l.secureDownloadMode || (chip !== Esp8266 && chip.secureBootEnabled(l))
        if (securityOn) log.warn("Security features enabled, so not changing any flash settings.")

        for ((index, part) in parts.withIndex()) {
            cancel.check()
            val source = part.name ?: "input"
            if (parts.size > 1) log.info("Writing '$source' at 0x%08x...".format(part.address))
            if (part.data.isEmpty()) { log.warn("'$source' is empty."); continue }
            var image = padTo(part.data, 4, 0xFF.toByte())
            if (!securityOn) image = patchFlashParams(chip, part.address, image, flashModeCode, flashFreqCode, flashSizeCode, log)
            var address = part.address
            val origAddress = address
            if (!l.isStub) {
                // Writes to unaligned addresses are impossible without the stub: prepend 0xFF (erased) bytes.
                val over = (address % EspLoader.FLASH_SECTOR_SIZE).toInt()
                address -= over
                image = ByteArray(over) { 0xFF.toByte() } + image
            }
            val uncSize = image.size.toLong()
            val imageMd5 = md5Hex(image)
            if (!options.eraseAll) {
                val over = origAddress % EspLoader.FLASH_SECTOR_SIZE
                if (over != 0L) log.note("Flash address 0x%08x is not aligned to a 0x%x byte flash sector. 0x%x bytes before this address will be erased.".format(origAddress, EspLoader.FLASH_SECTOR_SIZE, over))
                val eraseEnd = divRoundUp(address + uncSize, EspLoader.FLASH_SECTOR_SIZE.toLong()) * EspLoader.FLASH_SECTOR_SIZE - 1
                log.info("Flash will be erased from 0x%08x to 0x%08x...".format(origAddress - over, eraseEnd))
            }
            var payload = image
            var useCompress = compress
            if (useCompress) {
                val comp = zlibCompress(image)
                if (comp.size < image.size) payload = comp
                else { log.note("Cannot compress '$source' more than the original size, will flash uncompressed."); useCompress = false }
            }
            val t0 = System.nanoTime()
            if (!l.isStub) log.info("Erasing flash...")
            val inflater = if (useCompress) Inflater() else null
            if (useCompress) l.flashDeflBegin(uncSize, payload.size.toLong(), address)
            else l.flashBegin(uncSize, address)
            var seq = 0
            var bytesSent = 0L
            var bytesWritten = 0L
            var timeout = EspLoader.DEFAULT_TIMEOUT
            val bs = l.flashWriteSize
            val inflateBuf = ByteArray(64 * 1024)
            var pos = 0
            while (pos < payload.size) {
                cancel.check()
                val end = minOf(pos + bs, payload.size)
                val blockLen = end - pos
                if (useCompress) {
                    // Feed each compressed block to the inflater to learn how much flash it will write.
                    inflater!!.setInput(payload, pos, blockLen)
                    var blockUncompressed = 0L
                    while (!inflater.needsInput() && !inflater.finished()) {
                        val n = inflater.inflate(inflateBuf)
                        if (n == 0 && (inflater.needsInput() || inflater.finished())) break
                        blockUncompressed += n
                    }
                    if (!l.isStub) timeout = deflBlockTimeout(blockUncompressed)
                    l.flashDeflBlock(payload.copyOfRange(pos, end), seq, timeout)
                    // The stub ACKs this block immediately and processes it while receiving the next one.
                    if (l.isStub) timeout = deflBlockTimeout(blockUncompressed)
                    bytesWritten += blockUncompressed
                } else {
                    val block = if (blockLen < bs) payload.copyOfRange(pos, end) + ByteArray(bs - blockLen) { 0xFF.toByte() } else payload.copyOfRange(pos, end)
                    l.flashBlock(block, seq)
                    bytesWritten += blockLen
                }
                bytesSent += blockLen
                pos = end
                seq++
                progress.onProgress(doneBefore + minOf(bytesWritten, uncSize), totalBytes, "Writing at 0x%08x".format(address + bytesWritten))
            }
            inflater?.end()
            // The stub only writes each block after ACKing it; a final op is not ACKed until the last block is in flash.
            if (l.isStub) {
                if (useCompress) l.flashDeflFinish(false, timeout) else l.flashFinish(false, timeout)
            }
            val secs = (System.nanoTime() - t0) / 1e9
            val speed = if (secs > 0) " (%.1f kbit/s)".format(uncSize / secs * 8 / 1000) else ""
            if (useCompress) log.info("Wrote $uncSize bytes ($bytesSent compressed) at 0x%08x in %.1f seconds%s.".format(origAddress, secs, speed))
            else log.info("Wrote $bytesWritten bytes at 0x%08x in %.1f seconds%s.".format(origAddress, secs, speed))

            if (options.verify) {
                if (!l.secureDownloadMode) {
                    try {
                        log.info("Verifying written data...")
                        val res = l.flashMd5sum(address, uncSize)
                        if (res != imageMd5) {
                            log.info("Input MD5: $imageMd5"); log.info("Flash MD5: $res")
                            if (res == md5Hex(ByteArray(uncSize.toInt()) { 0xFF.toByte() })) throw EspException("Write failed, the written flash region is empty.")
                            throw EspException("MD5 of file does not match data in flash!")
                        }
                        log.info("Hash of data verified.")
                    } catch (e: NotImplementedInRomException) {
                        log.note("Cannot verify: ${e.message}")
                    }
                } else log.info("Cannot verify written data in secure download mode.")
            }
            doneBefore += part.data.size
            progress.onProgress(doneBefore, totalBytes, if (index == parts.lastIndex) "Done" else "Next file")
        }
    }

    /** Compare each part with flash contents using the chip-side MD5 (no data is read back). */
    fun verifyFlash(parts: List<FlashPart>, flashSize: String = "keep"): List<VerifyResult> {
        setFlashParameters(flashSize)
        return parts.map { p ->
            cancel.check()
            val image = padTo(p.data, 4, 0xFF.toByte())
            val expected = md5Hex(image)
            val actual = loader.flashMd5sum(p.address, image.size.toLong())
            VerifyResult(p.address, image.size, p.name, expected, actual)
        }
    }

    // ------------------------------------------------------------------ erase

    private fun checkEraseAllowed(force: Boolean, address: Long? = null) {
        if (force || chip === Esp8266 || loader.secureDownloadMode) return
        if (address != null && chip.flashEncryptionEnabled(loader) && chip.usesKeyManagerForFlashEncryption(loader) && address < 0x2000) {
            throw EspException("Flash encryption with Key Manager detected, erasing flash region 0x0-0x2000 is disabled to protect key recovery info. Use 'force' to override, please use with caution, otherwise it may brick your device!")
        }
        if (chip.flashEncryptionEnabled(loader) || chip.secureBootEnabled(loader)) {
            throw EspException("Active security features detected, erasing flash is disabled as a safety measure. Use 'force' to override, please use with caution, otherwise it may brick your device!")
        }
    }

    fun eraseFlash(force: Boolean = false) {
        checkEraseAllowed(force)
        log.info("Erasing flash memory (this may take a while)...")
        val t0 = System.nanoTime()
        loader.eraseFlash()
        log.info("Flash memory erased successfully in %.1f seconds.".format((System.nanoTime() - t0) / 1e9))
    }

    fun eraseRegion(address: Long, size: Long, force: Boolean = false) {
        if (address % EspLoader.FLASH_SECTOR_SIZE != 0L) throw EspException("Offset to erase from must be a multiple of ${EspLoader.FLASH_SECTOR_SIZE}.")
        if (size % EspLoader.FLASH_SECTOR_SIZE != 0L) throw EspException("Size of data to erase must be a multiple of ${EspLoader.FLASH_SECTOR_SIZE}.")
        checkEraseAllowed(force, address)
        log.info("Erasing flash memory region (this may take a while depending on size)...")
        val t0 = System.nanoTime()
        if (chip !== Esp8266 && !loader.isStub) {
            // flash_begin triggers an erase in ROM mode. The ROM defaults to 2MB; set the real size first.
            setFlashParameters(if (loader.secureDownloadMode) "16MB" else "detect")
            loader.flashBegin(size, address)
        } else {
            loader.eraseRegion(address, size)
        }
        log.info("Flash memory region erased successfully in %.1f seconds.".format((System.nanoTime() - t0) / 1e9))
    }

    // ------------------------------------------------------------------ read

    /** Read [size] bytes from [address] into [sink]. */
    fun readFlash(address: Long, size: Long, sink: OutputStream, progress: ProgressListener = ProgressListener.None, flashSize: String = "keep") {
        setFlashParameters(flashSize)
        val t0 = System.nanoTime()
        loader.readFlash(address, size, sink, progress)
        val secs = (System.nanoTime() - t0) / 1e9
        val speed = if (secs > 0) " (%.1f kbit/s)".format(size / secs * 8 / 1000) else ""
        log.info("Read $size bytes from 0x%08x in %.1f seconds%s.".format(address, secs, speed))
    }

    // ------------------------------------------------------------------ memory

    fun readMem(address: Long): Long = loader.readReg(address)
    fun writeMem(address: Long, value: Long, mask: Long = 0xFFFFFFFFL) = loader.writeReg(address, value, mask)

    /** Dump a RAM / register region word by word. */
    fun dumpMem(address: Long, size: Long, sink: OutputStream, progress: ProgressListener = ProgressListener.None) {
        var done = 0L
        while (done < size) {
            cancel.check()
            val word = loader.readReg(address + done)
            sink.write(byteArrayOf(word.toByte(), (word shr 8).toByte(), (word shr 16).toByte(), (word shr 24).toByte()))
            done += 4
            if (done % 1024 == 0L || done >= size) progress.onProgress(minOf(done, size), size, "Reading 0x%08x".format(address + done))
        }
    }

    /** Load a firmware image into RAM and run it (esptool `load-ram`). */
    fun loadRam(imageData: ByteArray, progress: ProgressListener = ProgressListener.None) {
        val image = FirmwareImage.parse(imageData, esp8266 = chip === Esp8266)
        log.info("RAM boot...")
        val total = image.segments.sumOf { it.size.toLong() }
        var done = 0L
        for (seg in image.segments) {
            val size = seg.size
            val blockSize = EspLoader.ESP_RAM_BLOCK
            val blocks = (size + blockSize - 1) / blockSize
            log.info("Downloading $size bytes at 0x%08x...".format(seg.addr))
            loader.memBegin(size, blocks, blockSize, seg.addr)
            for (b in 0 until blocks) {
                cancel.check()
                val from = b * blockSize
                loader.memBlock(seg.data.copyOfRange(from, minOf(from + blockSize, size)), b)
                done += minOf(blockSize, size - from)
                progress.onProgress(done, total, "Loading RAM at 0x%08x".format(seg.addr + from))
            }
        }
        log.info("All segments done, executing at 0x%08x.".format(image.entrypoint))
        loader.memFinish(image.entrypoint)
    }

    /** Run user code in flash (esptool `run`). */
    fun run() = loader.run()

    fun readFlashStatus(bytes: Int = 2): Long = loader.readStatus(bytes)
    fun writeFlashStatus(value: Long, bytes: Int = 2, nonVolatile: Boolean = false): Long {
        loader.writeStatus(value, bytes, nonVolatile)
        return loader.readStatus(bytes)
    }

    fun securityInfo(): SecurityInfo = loader.getSecurityInfo()

    /** Human readable security summary (esptool `get-security-info`). */
    fun securityInfoText(): String {
        val si = securityInfo()
        val sb = StringBuilder()
        sb.append("Flags: 0x%08x (0b%s)\n".format(si.flags, java.lang.Long.toBinaryString(si.flags)))
        sb.append("Key purposes: ${si.keyPurposes}\n")
        for ((i, kp) in si.keyPurposes.withIndex()) {
            val name = chip.keyPurposes[kp] ?: "UNKNOWN($kp)"
            if (kp != 0) sb.append("  Key block $i: $name\n")
        }
        if (si.chipId != null) sb.append("Chip ID: ${si.chipId}\nAPI version: ${si.apiVersion}\n")
        sb.append("Secure boot: ${if (si.secureBootEnabled) "enabled" else "disabled"}\n")
        if (si.secureBootEnabled) {
            sb.append("Secure boot aggressive revoke: ${si.secureBootAggressiveRevoke}\n")
            sb.append("Key revocations: ${listOf(si.secureBootKeyRevoke0, si.secureBootKeyRevoke1, si.secureBootKeyRevoke2)}\n")
        }
        sb.append("Flash encryption: ${if (si.flashEncryptionEnabled) "enabled" else "disabled"} (SPI_BOOT_CRYPT_CNT=0x%x)\n".format(si.flashCryptCnt))
        sb.append("JTAG: ${when { si.hardDisJtag -> "permanently disabled"; si.softDisJtag -> "software disabled"; else -> "enabled" }}\n")
        sb.append("USB access: ${if (si.disUsb) "disabled" else "enabled"}\n")
        sb.append("Secure download mode: ${if (si.secureDownloadEnabled) "enabled" else "disabled"}\n")
        if (si.disDownloadDcache || si.disDownloadIcache) sb.append("Download cache disabled: dcache=${si.disDownloadDcache} icache=${si.disDownloadIcache}\n")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ reset

    fun reset(mode: ResetAfter) {
        when (mode) {
            ResetAfter.HARD_RESET -> loader.hardReset()
            ResetAfter.SOFT_RESET -> { log.info("Soft resetting..."); loader.softReset(false) }
            ResetAfter.NO_RESET_STUB -> log.info("Staying in flasher stub.")
            ResetAfter.WATCHDOG_RESET -> {
                if (loader.secureDownloadMode) { log.warn("Watchdog hard reset is not supported in Secure Download Mode, attempting classic hard reset instead."); loader.hardReset() }
                else loader.watchdogReset()
            }
            ResetAfter.NO_RESET -> { log.info("Staying in bootloader."); if (loader.isStub) loader.softReset(true) }
        }
    }

    fun describeFlash(info: FlashInfo): String =
        "Manufacturer: %02x, Device: %04x, Size: %s".format(info.vendorId, info.deviceId, info.sizeName ?: "unknown")

    fun formatSizeBytes(bytes: Long) = formatSize(bytes)
}
