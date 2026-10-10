package io.github.xerootg.esploader

import io.github.xerootg.esploader.chips.ChipDefinition
import io.github.xerootg.esploader.chips.Chips
import io.github.xerootg.esploader.chips.Esp32
import io.github.xerootg.esploader.transport.SerialTransport
import io.github.xerootg.esploader.transport.Slip
import io.github.xerootg.esploader.transport.SlipReader
import io.github.xerootg.esploader.util.hex
import io.github.xerootg.esploader.util.le32
import io.github.xerootg.esploader.util.md5Hex
import io.github.xerootg.esploader.util.padTo
import io.github.xerootg.esploader.util.readLe16
import io.github.xerootg.esploader.util.readLe32
import io.github.xerootg.esploader.util.u8
import java.io.OutputStream
import java.security.MessageDigest

/** How to get the chip into the ROM download mode before talking to it. */
enum class ResetMode {
    /** Toggle DTR/RTS; USB-Serial/JTAG sequence when the bridge is the chip's own USB peripheral. */
    DEFAULT,
    /** Force the USB-Serial/JTAG reset sequence. */
    USB_RESET,
    /** Do not touch DTR/RTS; the user put the chip into download mode by hand. */
    NO_RESET,
    /** Like NO_RESET and also skip SYNC (pass-through setups). */
    NO_RESET_NO_SYNC,
}

/**
 * Low level access to the ESP ROM bootloader and the flasher stub over a [SerialTransport].
 * This is a port of esptool's `ESPLoader`; method names follow the original so the two can be
 * read side by side. All methods block; run them off the UI thread.
 */
class EspLoader(
    val transport: SerialTransport,
    val log: EspLog = EspLog.Silent,
    val cancel: CancelToken = CancelToken.Never,
) {
    companion object {
        // Commands supported by ESP8266 ROM bootloader
        const val ESP_FLASH_BEGIN = 0x02
        const val ESP_FLASH_DATA = 0x03
        const val ESP_FLASH_END = 0x04
        const val ESP_MEM_BEGIN = 0x05
        const val ESP_MEM_END = 0x06
        const val ESP_MEM_DATA = 0x07
        const val ESP_SYNC = 0x08
        const val ESP_WRITE_REG = 0x09
        const val ESP_READ_REG = 0x0A
        // Commands supported by ESP32 and later chips ROM bootloader (or ESP8266 with stub)
        const val ESP_SPI_SET_PARAMS = 0x0B
        const val ESP_SPI_ATTACH = 0x0D
        const val ESP_READ_FLASH_SLOW = 0x0E
        const val ESP_CHANGE_BAUDRATE = 0x0F
        const val ESP_FLASH_DEFL_BEGIN = 0x10
        const val ESP_FLASH_DEFL_DATA = 0x11
        const val ESP_FLASH_DEFL_END = 0x12
        const val ESP_SPI_FLASH_MD5 = 0x13
        const val ESP_GET_SECURITY_INFO = 0x14
        // Stub-only commands
        const val ESP_ERASE_FLASH = 0xD0
        const val ESP_ERASE_REGION = 0xD1
        const val ESP_READ_FLASH = 0xD2
        const val ESP_RUN_USER_CODE = 0xD3

        const val ROM_INVALID_RECV_MSG = 0x05

        const val ESP_RAM_BLOCK = 0x1800
        const val USB_OTG_BLOCK_SIZE = 0x800
        const val FLASH_WRITE_SIZE_ROM = 0x400
        const val FLASH_WRITE_SIZE_STUB = 0x4000
        const val FLASH_SECTOR_SIZE = 0x1000
        const val ESP_ROM_BAUD = 115200
        const val ESP_IMAGE_MAGIC = 0xE9
        const val ESP_CHECKSUM_MAGIC = 0xEF
        const val CHIP_DETECT_MAGIC_REG_ADDR = 0x40001000L
        const val ESPRESSIF_VID = 0x303A
        const val USB_JTAG_SERIAL_PID = 0x1001

        const val DEFAULT_TIMEOUT = 3_000
        const val SYNC_TIMEOUT = 100
        const val CHIP_ERASE_TIMEOUT = 120_000
        const val MAX_TIMEOUT = CHIP_ERASE_TIMEOUT * 2
        const val MEM_END_ROM_TIMEOUT = 200
        const val MD5_TIMEOUT_PER_MB = 8_000
        const val ERASE_REGION_TIMEOUT_PER_MB = 40_000
        const val ERASE_WRITE_TIMEOUT_PER_MB = 40_000
        const val DEFAULT_CONNECT_ATTEMPTS = 7
        const val WRITE_BLOCK_ATTEMPTS = 3
        const val DEFAULT_RESET_DELAY_MS = 50

        private val EMPTY = ByteArray(0)

        fun timeoutPerMb(msPerMb: Int, sizeBytes: Long): Int {
            val result = (msPerMb.toDouble() * sizeBytes / 1e6).toLong()
            return if (result < DEFAULT_TIMEOUT) DEFAULT_TIMEOUT else minOf(result, MAX_TIMEOUT.toLong()).toInt()
        }

        fun checksum(data: ByteArray, state: Int = ESP_CHECKSUM_MAGIC): Int {
            var s = state
            for (b in data) s = s xor (b.toInt() and 0xFF)
            return s
        }

        fun checksum(data: ByteArray, from: Int, to: Int, state: Int = ESP_CHECKSUM_MAGIC): Int {
            var s = state
            for (i in from until to) s = s xor (data[i].toInt() and 0xFF)
            return s
        }
    }

    /** The chip we believe we are talking to. Replaced during [connect] once detected. */
    var chip: ChipDefinition = Esp32
        private set
    val chipName: String get() = chip.chipName

    var isStub: Boolean = false
        private set
    var secureDownloadMode: Boolean = false
        private set
    /** SYNC replied with val=0, i.e. the stub (not the ROM) is already running. */
    var syncStubDetected: Boolean = false
        private set
    /** Set by chip hooks (ESP32-C2 rev 0) when the stub must not be used. */
    var stubDisabledForCompatibility: Boolean = false

    /** The stub that is resident in RAM, when [isStub]. */
    var runningStub: StubImage? = null
        private set

    val flashWriteSize: Int get() = if (isStub) FLASH_WRITE_SIZE_STUB else FLASH_WRITE_SIZE_ROM
    private var ramBlock = ESP_RAM_BLOCK

    private var cachedFlashId: Long? = null
    private var cachedSecurityInfo: SecurityInfo? = null

    private val slip = SlipReader(transport, log)

    val baudRate: Int get() = transport.baudRate

    // ------------------------------------------------------------------ low level

    fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (e: InterruptedException) { throw EspCancelledException() }
    }

    private fun write(packet: ByteArray) {
        val buf = Slip.encode(packet)
        if (log !== EspLog.Silent) log.trace("Write ${buf.size} bytes: ${buf.hex()}")
        transport.write(buf)
    }

    fun read(timeoutMs: Int = DEFAULT_TIMEOUT): ByteArray = slip.readPacket(timeoutMs)

    fun flushInput() = slip.reset()

    /** Response of a command: `val` from the header plus the payload after it. */
    class Response(val value: Long, val data: ByteArray)

    /**
     * Send a request and read the response. With [op] null, only reads a response.
     * Throws [UnsupportedCommandException] if the ROM reports an invalid message for another op.
     */
    fun command(op: Int?, data: ByteArray = EMPTY, chk: Int = 0, waitResponse: Boolean = true, timeoutMs: Int = DEFAULT_TIMEOUT): Response {
        cancel.check()
        val timeout = minOf(timeoutMs, MAX_TIMEOUT)
        if (op != null) {
            if (log !== EspLog.Silent) log.trace("--- Cmd 0x%02x | data_len %d | wait %s | timeout %d ---".format(op, data.size, waitResponse, timeout))
            val pkt = ByteArray(8 + data.size)
            pkt[0] = 0
            pkt[1] = op.toByte()
            pkt[2] = (data.size and 0xFF).toByte()
            pkt[3] = ((data.size shr 8) and 0xFF).toByte()
            pkt[4] = (chk and 0xFF).toByte(); pkt[5] = ((chk shr 8) and 0xFF).toByte()
            pkt[6] = ((chk shr 16) and 0xFF).toByte(); pkt[7] = ((chk shr 24) and 0xFF).toByte()
            System.arraycopy(data, 0, pkt, 8, data.size)
            write(pkt)
        }
        if (!waitResponse) return Response(0, EMPTY)
        // Keep reading until a response for our op shows up (some ESP8266s send extra sync replies).
        repeat(100) {
            val p = read(timeout)
            if (p.size < 8) return@repeat
            val resp = p.u8(0)
            val opRet = p.u8(1)
            val value = p.readLe32(4)
            if (resp != 1) return@repeat
            val body = p.copyOfRange(8, p.size)
            if (op == null || opRet == op) return Response(value, body)
            if (body.isNotEmpty() && body.u8(0) != 0 && body.size > 1 && body.u8(1) == ROM_INVALID_RECV_MSG) {
                // The ROM sends the "unsupported command" response several times; drain them.
                sleep(200)
                val tmp = ByteArray(14 * 8)
                transport.read(tmp, 1)
                flushInput()
                throw UnsupportedCommandException(op, chipName)
            }
        }
        throw EspException("Response doesn't match request.")
    }

    /** Execute a command, check the 2 status bytes and return the response data (or `val`). */
    fun checkCommand(opDescription: String, op: Int, data: ByteArray = EMPTY, chk: Int = 0, respDataLen: Int = 0, timeoutMs: Int = DEFAULT_TIMEOUT): Response {
        val r = command(op, data, chk, timeoutMs = timeoutMs)
        val body = r.data
        if (body.size < respDataLen + 2) {
            if (body.isNotEmpty() && body.u8(0) != 0) throw EspException.withResult("Failed to $opDescription", body.copyOfRange(0, minOf(2, body.size)))
            throw EspException("Failed to $opDescription. Only got ${body.size} byte status response.")
        }
        val status = body.copyOfRange(respDataLen, respDataLen + 2)
        if (status[0].toInt() != 0) throw EspException.withResult("Failed to $opDescription", status)
        return if (respDataLen > 0) Response(r.value, body.copyOfRange(0, respDataLen)) else Response(r.value, EMPTY)
    }

    fun sync() {
        val payload = byteArrayOf(0x07, 0x07, 0x12, 0x20) + ByteArray(32) { 0x55 }
        val r = command(ESP_SYNC, payload, timeoutMs = SYNC_TIMEOUT)
        // ROM bootloaders send some non-zero "val"; the flasher stub sends 0.
        var stub = r.value == 0L
        repeat(7) { stub = stub and (command(null, timeoutMs = SYNC_TIMEOUT).value == 0L) }
        syncStubDetected = stub
    }

    // ------------------------------------------------------------------ reset sequences

    private fun classicReset(resetDelayMs: Long) {
        transport.setDtr(false)      // IO0 = HIGH
        transport.setRts(true)       // EN = LOW, chip in reset
        sleep(100)
        transport.setDtr(true)       // IO0 = LOW
        transport.setRts(false)      // EN = HIGH, chip out of reset
        sleep(resetDelayMs)
        transport.setDtr(false)      // IO0 = HIGH, done
    }

    private fun usbJtagSerialReset() {
        transport.setRts(false)
        transport.setDtr(false)      // idle
        sleep(100)
        transport.setDtr(true)       // set IO0
        transport.setRts(false)
        sleep(100)
        transport.setRts(true)       // reset, traversing (1,1) instead of (0,0)
        transport.setDtr(false)
        transport.setRts(true)       // RTS re-write so Windows-style bridges propagate DTR
        sleep(100)
        transport.setDtr(false)
        transport.setRts(false)      // chip out of reset
    }

    fun hardResetDefault(usesUsb: Boolean) {
        log.info("Hard resetting via RTS pin...")
        transport.setRts(true)       // EN = LOW
        sleep(if (usesUsb) 200 else 100)
        transport.setRts(false)      // EN = HIGH
        if (usesUsb) sleep(200)      // give the internal USB peripheral time to re-enumerate
    }

    fun hardReset() = chip.hardReset(this)
    fun watchdogReset() = chip.watchdogReset(this)

    fun softReset(stayInBootloader: Boolean) {
        if (!isStub) {
            if (stayInBootloader) return // the ROM bootloader is already in the bootloader
            flashBegin(0, 0); flashFinish(false)
        } else if (stayInBootloader) {
            flashBegin(0, 0); flashFinish(true)
        } else if (chip.chipName != "ESP8266") {
            throw EspException("Soft resetting is currently only supported on ESP8266")
        } else {
            command(ESP_RUN_USER_CODE, waitResponse = false)
        }
    }

    // ------------------------------------------------------------------ connect

    private fun resetStrategies(mode: ResetMode): List<() -> Unit> {
        if (mode == ResetMode.USB_RESET || transport.usbProductId == USB_JTAG_SERIAL_PID) return listOf({ usbJtagSerialReset() })
        val delay = DEFAULT_RESET_DELAY_MS.toLong()
        return listOf({ classicReset(delay) }, { classicReset(delay + 500) })
    }

    private class ConnectAttemptResult(val error: EspException?)

    private fun connectAttempt(reset: () -> Unit, mode: ResetMode): EspException? {
        if (mode == ResetMode.NO_RESET_NO_SYNC) return null
        var bootLogDetected = false
        var bootMode: String? = null
        var downloadMode = false
        if (mode != ResetMode.NO_RESET) {
            transport.purgeInput()
            reset()
            sleep(100) // let the ROM print its boot banner
            val bootLog = String(slip.drainBuffered(), Charsets.ISO_8859_1)
            val m = Regex("boot:(0x[0-9a-fA-F]+)(.*waiting for download)?", RegexOption.DOT_MATCHES_ALL).find(bootLog)
            if (m != null) {
                bootLogDetected = true
                bootMode = m.groupValues[1]
                downloadMode = m.groupValues[2].isNotEmpty()
            }
        }
        var lastError: EspException? = null
        repeat(5) {
            try {
                flushInput()
                sync()
                return null
            } catch (e: EspException) {
                if (e is EspCancelledException) throw e
                sleep(50)
                lastError = e
            }
        }
        if (bootLogDetected) {
            lastError = if (downloadMode) EspException("Download mode successfully detected, but getting no sync reply: The serial TX path seems to be down.")
            else EspException("Wrong boot mode detected ($bootMode)! The chip needs to be in download mode.")
        }
        return lastError
    }

    /**
     * Reset into the bootloader, sync, and auto-detect the chip. Afterwards [chip] is the
     * detected target and chip-specific post-connect hooks have run.
     */
    fun connect(mode: ResetMode = ResetMode.DEFAULT, attempts: Int = DEFAULT_CONNECT_ATTEMPTS, detect: Boolean = true) {
        if (mode == ResetMode.NO_RESET || mode == ResetMode.NO_RESET_NO_SYNC) {
            log.note("Pre-connection option \"$mode\" was selected. Connection may fail if the chip is not in bootloader or flasher stub mode.")
        }
        log.info("Connecting...")
        val strategies = resetStrategies(mode)
        var lastError: EspException? = null
        var i = 0
        while (attempts <= 0 || i < attempts) {
            cancel.check()
            lastError = connectAttempt(strategies[i % strategies.size], mode)
            if (lastError == null) break
            log.trace("Connect attempt ${i + 1} failed: ${lastError.message}")
            i++
        }
        if (lastError != null) {
            var extra = ""
            if (transport.baudRate < 115200) extra = "\nNote: ESP32-C2 needs at least 115200 baud to connect."
            throw EspException("Failed to connect to Espressif device: ${lastError.message}$extra\nCheck the wiring, hold BOOT while pressing RESET if the board has no auto-reset circuit, and try again.")
        }
        if (detect) detectChip()
        else chip.postConnect(this)
    }

    private fun detectChip() {
        var chipId: Int? = null
        var magic: Long? = null
        try {
            val si = getSecurityInfo(cache = false)
            chipId = si.chipId ?: throw EspException("Security info command does not contain chip ID (ESP32-S2).")
            secureDownloadMode = si.secureDownloadEnabled
        } catch (e: EspException) {
            if (e is EspCancelledException) throw e
            chipId = null
            try {
                magic = readReg(CHIP_DETECT_MAGIC_REG_ADDR)
            } catch (u: UnsupportedCommandException) {
                // Cannot even read the magic register: ESP32-S2 in secure download mode.
                magic = null
                secureDownloadMode = true
            }
        }
        val detected: ChipDefinition? = when {
            chipId != null -> Chips.byImageChipId(chipId)
            magic != null -> Chips.byMagic(magic)
            else -> Chips.byName("esp32s2")
        }
        if (detected == null) {
            val unsupported = chipId?.let { Chips.unsupportedChipIds[it] }
            val specifier = if (chipId != null) "chip ID $chipId" else "chip magic value 0x%08x".format(magic ?: 0)
            throw EspException(
                if (unsupported != null) "Detected $unsupported ($specifier), which is pre-production silicon no longer supported."
                else "Unsupported chip ($specifier). Probably a chip newer than this version of the loader."
            )
        }
        chip = detected
        log.info("Detected chip: ${chip.chipName}" + if (secureDownloadMode) " (secure download mode)" else "")
        // USB-OTG ROM cannot take the larger RAM block sizes used to upload the stub.
        if (useUsbOtgBlock()) ramBlock = USB_OTG_BLOCK_SIZE
        chip.postConnect(this)
    }

    // ------------------------------------------------------------------ registers

    fun readReg(addr: Long, timeoutMs: Int = DEFAULT_TIMEOUT): Long =
        checkCommand("read target memory", ESP_READ_REG, le32(addr.toInt()), timeoutMs = timeoutMs).value and 0xFFFFFFFFL

    fun writeReg(addr: Long, value: Long, mask: Long = 0xFFFFFFFFL, delayUs: Int = 0, delayAfterUs: Int = 0) {
        var cmd = le32(addr.toInt(), value.toInt(), mask.toInt(), delayUs)
        if (delayAfterUs > 0) {
            // add a dummy write to a date register as an excuse to have a delay
            cmd += le32(chip.uartDateRegAddr.toInt(), 0, 0, delayAfterUs)
        }
        checkCommand("write target memory", ESP_WRITE_REG, cmd)
    }

    fun updateReg(addr: Long, mask: Long, newVal: Long): Long {
        val shift = java.lang.Long.numberOfTrailingZeros(mask)
        var v = readReg(addr)
        v = v and mask.inv() and 0xFFFFFFFFL
        v = v or ((newVal shl shift) and mask)
        writeReg(addr, v)
        return v
    }

    // ------------------------------------------------------------------ RAM download

    fun memBegin(size: Int, blocks: Int, blocksize: Int, offset: Long) {
        if (isStub) {
            val stub = runningStub
            if (stub != null) {
                val loadStart = offset; val loadEnd = offset + size
                for ((start, end) in stub.residentRanges()) {
                    if (loadStart < end && loadEnd > start) throw EspException(
                        "Stub flasher is resident at 0x%08x-0x%08x. Can't load binary at overlapping address range 0x%08x-0x%08x. Either change the load address or disable the stub flasher.".format(start, end, loadStart, loadEnd)
                    )
                }
            }
        }
        checkCommand("enter RAM download mode", ESP_MEM_BEGIN, le32(size, blocks, blocksize, offset.toInt()))
    }

    fun memBlock(data: ByteArray, seq: Int) {
        checkCommand("write to target RAM", ESP_MEM_DATA, le32(data.size, seq, 0, 0) + data, checksum(data))
    }

    fun memFinish(entrypoint: Long = 0) {
        // The ROM may reset the UART before the reply is out; use a short timeout and ignore errors.
        val timeout = if (isStub) DEFAULT_TIMEOUT else MEM_END_ROM_TIMEOUT
        val data = le32(if (entrypoint == 0L) 1 else 0, entrypoint.toInt())
        try {
            checkCommand("leave RAM download mode", ESP_MEM_END, data, timeoutMs = timeout)
        } catch (e: EspException) {
            if (isStub || e is EspCancelledException) throw e
        }
    }

    private fun uploadSegment(data: ByteArray, offs: Long) {
        val blocks = (data.size + ramBlock - 1) / ramBlock
        memBegin(data.size, blocks, ramBlock, offs)
        for (seq in 0 until blocks) {
            cancel.check()
            val from = seq * ramBlock
            memBlock(data.copyOfRange(from, minOf(from + ramBlock, data.size)), seq)
        }
    }

    /** Upload and start the flasher stub. No-op if the stub already answered the SYNC. */
    fun runStub(): StubImage? {
        if (secureDownloadMode) { log.warn("Stub flasher is not supported in Secure Download Mode, it has been disabled."); return null }
        if (!chip.stubSupported) { log.warn("Stub flasher is not yet supported on ${chip.chipName}, it has been disabled."); return null }
        if (chip.chipName == "ESP32-C3" && chip.secureBootEnabled(this)) { log.warn("Stub flasher is not supported on ESP32-C3 with Secure Boot, it has been disabled."); return null }
        if (stubDisabledForCompatibility) { log.warn("Stub flasher has been disabled for compatibility with this chip revision."); return null }
        val stub = StubFlasher.load(chip.stubJsonName(this))
        if (syncStubDetected) {
            log.info("Stub flasher is already running. No upload is necessary.")
            isStub = true
            runningStub = stub
            flushInput()
            return stub
        }
        val secureBootWorkflow = chip.chipName == "ESP32-S3" && chip.secureBootEnabled(this)
        log.info("Uploading stub flasher...")
        uploadSegment(stub.text, stub.textStart)
        if (stub.data != null && stub.dataStart != null) uploadSegment(stub.data, stub.dataStart)
        log.info("Running stub flasher...")
        var storedReadPointer = 0L
        val romSpiflashLegacyFuncsReadPtr = 0x3FCEF688L
        if (!secureBootWorkflow) {
            memFinish(stub.entry)
        } else {
            // Bug in ESP32-S3 ROM prevents stub execution if secure boot is enabled:
            // hijack the ROM `read` function pointer to jump to the stub entry point.
            memFinish(0)
            storedReadPointer = readReg(romSpiflashLegacyFuncsReadPtr)
            writeReg(romSpiflashLegacyFuncsReadPtr, stub.entry)
            command(ESP_READ_FLASH_SLOW, le32(0, 0), waitResponse = false)
        }
        val p = try { read() } catch (e: EspException) {
            throw EspException("Failed to start stub flasher. There was no response.", e)
        }
        if (!p.contentEquals("OHAI".toByteArray())) throw EspException("Failed to start stub flasher. Unexpected response: ${p.hex()}")
        isStub = true
        runningStub = stub
        if (secureBootWorkflow) writeReg(romSpiflashLegacyFuncsReadPtr, storedReadPointer)
        flushInput()
        log.info("Stub flasher running.")
        return stub
    }

    // ------------------------------------------------------------------ flash download

    /** Start downloading to flash (ROM erases up front). Returns number of blocks to write. */
    fun flashBegin(size: Long, offset: Long, encryptedWrite: Boolean = false): Int {
        val numBlocks = ((size + flashWriteSize - 1) / flashWriteSize).toInt()
        val eraseSize = chip.eraseSize(this, offset, size)
        val timeout = if (isStub) DEFAULT_TIMEOUT else timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, size)
        var params = le32(eraseSize.toInt(), numBlocks, flashWriteSize, offset.toInt())
        if (isStub || chip.romSupportsExtendedFlashParams) params += le32(if (encryptedWrite) 1 else 0)
        val t = System.nanoTime()
        checkCommand("enter flash download mode", ESP_FLASH_BEGIN, params, timeoutMs = timeout)
        if (size != 0L && !isStub) log.info("Took %.2fs to erase flash block.".format((System.nanoTime() - t) / 1e9))
        return numBlocks
    }

    fun flashBlock(data: ByteArray, seq: Int, timeoutMs: Int = DEFAULT_TIMEOUT, encrypted: Boolean = false) {
        val op = if (encrypted) "encrypted " else ""
        var attemptsLeft = WRITE_BLOCK_ATTEMPTS - 1
        while (true) {
            try {
                checkCommand("write ${op}to target flash after seq $seq", ESP_FLASH_DATA, le32(data.size, seq, 0, 0) + data, checksum(data), timeoutMs = timeoutMs)
                return
            } catch (e: EspException) {
                if (e is EspCancelledException || attemptsLeft == 0) throw e
                log.trace("Block write failed, retrying with $attemptsLeft attempts left...")
                attemptsLeft--
            }
        }
    }

    fun flashFinish(reboot: Boolean = false, timeoutMs: Int = DEFAULT_TIMEOUT) {
        checkCommand("leave flash download mode", ESP_FLASH_END, le32(if (reboot) 0 else 1), timeoutMs = timeoutMs)
    }

    /** Run application code in flash (fake flash begin immediately followed by flash end). */
    fun run(reboot: Boolean = false) {
        flashBegin(0, 0)
        flashFinish(reboot)
    }

    fun flashDeflBegin(size: Long, compSize: Long, offset: Long, encryptedWrite: Boolean = false): Int {
        if (!isStub && !chip.romSupportsCompressedFlash) throw NotImplementedInRomException("Compressed flashing", chipName)
        val numBlocks = ((compSize + flashWriteSize - 1) / flashWriteSize).toInt()
        val eraseBlocks = ((size + flashWriteSize - 1) / flashWriteSize)
        val writeSize: Long
        val timeout: Int
        if (isStub) {
            writeSize = size // stub expects number of bytes here, manages erasing internally
            timeout = DEFAULT_TIMEOUT
        } else {
            writeSize = eraseBlocks * flashWriteSize // ROM expects rounded up to erase block size
            timeout = timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, writeSize)
        }
        log.info("Compressed $size bytes to $compSize...")
        var params = le32(writeSize.toInt(), numBlocks, flashWriteSize, offset.toInt())
        if (isStub || chip.romSupportsExtendedFlashParams) params += le32(if (encryptedWrite) 1 else 0)
        val t = System.nanoTime()
        checkCommand("enter compressed flash mode", ESP_FLASH_DEFL_BEGIN, params, timeoutMs = timeout)
        if (size != 0L && !isStub) log.info("Took %.2fs to erase flash block.".format((System.nanoTime() - t) / 1e9))
        return numBlocks
    }

    fun flashDeflBlock(data: ByteArray, seq: Int, timeoutMs: Int = DEFAULT_TIMEOUT) {
        var attemptsLeft = WRITE_BLOCK_ATTEMPTS - 1
        while (true) {
            try {
                checkCommand("write compressed data to flash after seq $seq", ESP_FLASH_DEFL_DATA, le32(data.size, seq, 0, 0) + data, checksum(data), timeoutMs = timeoutMs)
                return
            } catch (e: EspException) {
                if (e is EspCancelledException || attemptsLeft == 0) throw e
                log.trace("Compressed block write failed, retrying with $attemptsLeft attempts left")
                attemptsLeft--
            }
        }
    }

    fun flashDeflFinish(reboot: Boolean = false, timeoutMs: Int = DEFAULT_TIMEOUT) {
        // Skip sending flash_finish to the ROM loader, as this exits the bootloader. The stub doesn't.
        if (!reboot && !isStub) return
        checkCommand("leave compressed flash mode", ESP_FLASH_DEFL_END, le32(if (reboot) 0 else 1), timeoutMs = timeoutMs)
    }

    /** MD5 of a flash region as computed on the chip (lowercase hex). */
    fun flashMd5sum(addr: Long, size: Long): String {
        if (!isStub && !chip.romSupportsMd5) throw NotImplementedInRomException("MD5 verification", chipName)
        val timeout = timeoutPerMb(MD5_TIMEOUT_PER_MB, size)
        val respLen = if (isStub) 16 else 32
        val res = checkCommand("calculate md5sum", ESP_SPI_FLASH_MD5, le32(addr.toInt(), size.toInt(), 0, 0), respDataLen = respLen, timeoutMs = timeout).data
        return if (isStub) res.hex() else String(res, Charsets.US_ASCII).lowercase()
    }

    // ------------------------------------------------------------------ baud rate

    fun changeBaud(baud: Int) = chip.changeBaud(this, baud)

    fun changeBaudDefault(baud: Int) = changeBaudRaw(baud, baud.toLong(), if (isStub) transport.baudRate.toLong() else 0L)

    /** Send CHANGE_BAUDRATE with [romBaud]/[secondArg] and switch the port to [portBaud]. */
    fun changeBaudRaw(portBaud: Int, romBaud: Long, secondArg: Long) {
        if (!isStub && !chip.romSupportsCompressedFlash) throw NotImplementedInRomException("Changing the baud rate", chipName)
        log.info("Changing baud rate to $portBaud...")
        command(ESP_CHANGE_BAUDRATE, le32(romBaud.toInt(), secondArg.toInt()))
        log.info("Changed.")
        transport.baudRate = portBaud
        sleep(50) // get rid of garbage sent during baud rate change
        flushInput()
    }

    // ------------------------------------------------------------------ erase / read (stub)

    fun eraseFlash() {
        if (!isStub) throw NotImplementedInRomException("Erasing the whole flash", chipName)
        checkCommand("erase flash", ESP_ERASE_FLASH, timeoutMs = CHIP_ERASE_TIMEOUT)
    }

    fun eraseRegion(offset: Long, size: Long) {
        if (!isStub) throw NotImplementedInRomException("Erasing a flash region", chipName)
        checkCommand("erase region", ESP_ERASE_REGION, le32(offset.toInt(), size.toInt()), timeoutMs = timeoutPerMb(ERASE_REGION_TIMEOUT_PER_MB, size))
    }

    /** Read [length] bytes at [offset] into [sink]. Uses the fast stub path when available. */
    fun readFlash(offset: Long, length: Long, sink: OutputStream, progress: ProgressListener = ProgressListener.None) {
        if (!isStub) { readFlashSlow(offset, length, sink, progress); return }
        checkCommand("read flash", ESP_READ_FLASH, le32(offset.toInt(), length.toInt(), FLASH_SECTOR_SIZE, 64))
        val md5 = MessageDigest.getInstance("MD5")
        var received = 0L
        while (received < length) {
            cancel.check()
            val p = read(3_000)
            if (received + p.size > length) throw EspException("Read more than expected.")
            sink.write(p)
            md5.update(p)
            received += p.size
            if (received < length && p.size < FLASH_SECTOR_SIZE) {
                throw EspException("Corrupt data, expected 0x%x bytes but received 0x%x bytes.".format(FLASH_SECTOR_SIZE, p.size))
            }
            write(le32(received.toInt()))
            progress.onProgress(received, length, "Reading from 0x%08x".format(offset + received))
        }
        val digestFrame = read()
        if (digestFrame.size != 16) throw EspException("Expected digest, got: ${digestFrame.hex()}")
        val expected = digestFrame.hex().uppercase()
        val actual = md5.digest().hex().uppercase()
        if (expected != actual) throw EspException("Digest mismatch: expected $expected, got $actual")
    }

    /** ROM-only read, 64 bytes per command. Very slow, but works without the stub. */
    fun readFlashSlow(offset: Long, length: Long, sink: OutputStream, progress: ProgressListener = ProgressListener.None) {
        if (!chip.romSupportsReadFlashSlow) throw NotImplementedInRomException("Reading flash", chipName)
        val blockLen = 64
        var done = 0L
        while (done < length) {
            cancel.check()
            val n = minOf(blockLen.toLong(), length - done).toInt()
            val r = try {
                checkCommand("read flash block", ESP_READ_FLASH_SLOW, le32((offset + done).toInt(), n), respDataLen = blockLen).data
            } catch (e: EspException) {
                if (e !is EspCancelledException) log.note("Consider specifying the flash size.")
                throw e
            }
            if (r.size < n) throw EspException("Expected $n byte block, got ${r.size} bytes. Serial errors?")
            sink.write(r, 0, n)
            done += n
            if (done % 1024 == 0L || done == length) progress.onProgress(done, length, "Reading from 0x%08x".format(offset + done))
        }
    }

    // ------------------------------------------------------------------ SPI flash plumbing

    fun flashSpiAttach(hspiArg: Long) = chip.flashSpiAttach(this, hspiArg)

    fun flashSpiAttachDefault(hspiArg: Long) {
        var arg = le32(hspiArg.toInt())
        if (!isStub) arg += byteArrayOf(0, 0, 0, 0) // ESP32 ROM takes an additional 'is legacy' arg
        checkCommand("configure SPI flash pins", ESP_SPI_ATTACH, arg)
    }

    fun flashSetParameters(size: Long) = chip.flashSetParameters(this, size)

    /** Tell the bootloader the parameters of the chip ("flashchip" structure in ROM RAM). */
    fun flashSetParametersDefault(size: Long) {
        val flId = 0; val blockSize = 64 * 1024; val sectorSize = 4 * 1024; val pageSize = 256; val statusMask = 0xFFFF
        checkCommand("set SPI params", ESP_SPI_SET_PARAMS, le32(flId, size.toInt(), blockSize, sectorSize, pageSize, statusMask))
    }

    /**
     * Run an arbitrary SPI flash command via the SPI peripheral's "user command" mode.
     * Writes [data] after the command byte, then reads back [readBits] (max 32) of reply.
     */
    fun runSpiflashCommand(spiflashCommand: Int, data: ByteArray = EMPTY, readBits: Int = 0, addr: Long? = null, addrLen: Int = 0, dummyLen: Int = 0): Long {
        val SPI_USR_COMMAND = 1L shl 31
        val SPI_USR_ADDR = 1L shl 30
        val SPI_USR_DUMMY = 1L shl 29
        val SPI_USR_MISO = 1L shl 28
        val SPI_USR_MOSI = 1L shl 27
        val SPI_CMD_USR = 1L shl 18
        val SPI_USR2_COMMAND_LEN_SHIFT = 28
        val SPI_USR_ADDR_LEN_SHIFT = 26

        val base = chip.spiRegBase
        val SPI_CMD_REG = base + 0x00
        val SPI_ADDR_REG = base + 0x04
        val SPI_USR_REG = base + chip.spiUsrOffs
        val SPI_USR1_REG = base + chip.spiUsr1Offs
        val SPI_USR2_REG = base + chip.spiUsr2Offs
        val SPI_W0_REG = base + chip.spiW0Offs

        if (readBits > 32) throw EspException("Reading more than 32 bits back from a SPI flash operation is unsupported")
        if (data.size > 64) throw EspException("Writing more than 64 bytes of data with one SPI command is unsupported")

        fun setDataLengths(mosiBits: Int, misoBits: Int) {
            val mosiDlen = chip.spiMosiDlenOffs
            val misoDlen = chip.spiMisoDlenOffs
            if (mosiDlen != null && misoDlen != null) {
                if (mosiBits > 0) writeReg(base + mosiDlen, (mosiBits - 1).toLong())
                if (misoBits > 0) writeReg(base + misoDlen, (misoBits - 1).toLong())
                var flags = 0L
                if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
                if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl SPI_USR_ADDR_LEN_SHIFT)
                if (flags != 0L) writeReg(SPI_USR1_REG, flags)
            } else {
                val SPI_MOSI_BITLEN_S = 17; val SPI_MISO_BITLEN_S = 8
                val mosiMask = if (mosiBits == 0) 0L else (mosiBits - 1).toLong()
                val misoMask = if (misoBits == 0) 0L else (misoBits - 1).toLong()
                var flags = (misoMask shl SPI_MISO_BITLEN_S) or (mosiMask shl SPI_MOSI_BITLEN_S)
                if (dummyLen > 0) flags = flags or (dummyLen - 1).toLong()
                if (addrLen > 0) flags = flags or ((addrLen - 1).toLong() shl SPI_USR_ADDR_LEN_SHIFT)
                writeReg(SPI_USR1_REG, flags)
            }
        }

        val dataBits = data.size * 8
        val oldSpiUsr = readReg(SPI_USR_REG)
        val oldSpiUsr2 = readReg(SPI_USR2_REG)
        var flags = SPI_USR_COMMAND
        if (readBits > 0) flags = flags or SPI_USR_MISO
        if (dataBits > 0) flags = flags or SPI_USR_MOSI
        if (addrLen > 0) flags = flags or SPI_USR_ADDR
        if (dummyLen > 0) flags = flags or SPI_USR_DUMMY
        setDataLengths(dataBits, readBits)
        writeReg(SPI_USR_REG, flags)
        writeReg(SPI_USR2_REG, (7L shl SPI_USR2_COMMAND_LEN_SHIFT) or spiflashCommand.toLong())
        if (addrLen > 0) {
            var a = addr ?: 0L
            if (chip.spiAddrRegMsb) a = (a shl (32 - addrLen)) and 0xFFFFFFFFL
            writeReg(SPI_ADDR_REG, a)
        }
        if (dataBits == 0) {
            writeReg(SPI_W0_REG, 0) // clear data register before we read it
        } else {
            val padded = padTo(data, 4)
            var nextReg = SPI_W0_REG
            for (i in padded.indices step 4) {
                writeReg(nextReg, padded.readLe32(i))
                nextReg += 4
            }
        }
        writeReg(SPI_CMD_REG, SPI_CMD_USR)
        var done = false
        repeat(10) { if (!done && (readReg(SPI_CMD_REG) and SPI_CMD_USR) == 0L) done = true }
        if (!done) throw EspException("SPI command did not complete in time")
        val status = readReg(SPI_W0_REG)
        writeReg(SPI_USR_REG, oldSpiUsr)
        writeReg(SPI_USR2_REG, oldSpiUsr2)
        return status
    }

    fun readSpiflashSfdp(addr: Long, readBits: Int): Long =
        runSpiflashCommand(0x5A, readBits = readBits, addr = addr, addrLen = 24, dummyLen = 8)

    /** SPI flash manufacturer and device ID (RDID), 24 bits. */
    fun flashId(cache: Boolean = true): Long {
        if (!cache || cachedFlashId == null) cachedFlashId = runSpiflashCommand(0x9F, readBits = 24)
        return cachedFlashId!!
    }

    /** Read up to 3 bytes of SPI flash status registers via RDSR, RDSR2, RDSR3. */
    fun readStatus(numBytes: Int = 2): Long {
        var status = 0L; var shift = 0
        for (cmd in listOf(0x05, 0x35, 0x15).take(numBytes)) {
            status += runSpiflashCommand(cmd, readBits = 8) shl shift
            shift += 8
        }
        return status
    }

    /** Write up to 3 bytes of status register. Uses volatile writes unless [setNonVolatile]. */
    fun writeStatus(newStatus: Long, numBytes: Int = 2, setNonVolatile: Boolean = false) {
        val SPIFLASH_WRSR = 0x01; val SPIFLASH_WRSR2 = 0x31; val SPIFLASH_WRSR3 = 0x11
        val SPIFLASH_WEVSR = 0x50; val SPIFLASH_WREN = 0x06; val SPIFLASH_WRDI = 0x04
        val enableCmd = if (setNonVolatile) SPIFLASH_WREN else SPIFLASH_WEVSR
        var s = newStatus
        if (numBytes == 2) {
            runSpiflashCommand(enableCmd)
            runSpiflashCommand(SPIFLASH_WRSR, byteArrayOf(s.toByte(), (s shr 8).toByte()))
        }
        for (cmd in listOf(SPIFLASH_WRSR, SPIFLASH_WRSR2, SPIFLASH_WRSR3).take(numBytes)) {
            runSpiflashCommand(enableCmd)
            runSpiflashCommand(cmd, byteArrayOf((s and 0xFF).toByte()))
            s = s shr 8
        }
        runSpiflashCommand(SPIFLASH_WRDI)
    }

    // ------------------------------------------------------------------ security info / USB

    fun getSecurityInfo(cache: Boolean = true): SecurityInfo {
        if (cache) cachedSecurityInfo?.let { return it }
        val info = try {
            val r = checkCommand("get security info", ESP_GET_SECURITY_INFO, respDataLen = 20).data
            SecurityInfo(r.readLe32(0), r.u8(4), (5 until 12).map { r.u8(it) }, r.readLe32(12).toInt(), r.readLe32(16).toInt())
        } catch (e: UnsupportedCommandException) {
            throw e
        } catch (e: EspException) {
            if (e is EspCancelledException) throw e
            // ESP32-S2 replies with 12 bytes
            val r = checkCommand("get security info", ESP_GET_SECURITY_INFO, respDataLen = 12).data
            SecurityInfo(r.readLe32(0), r.u8(4), (5 until 12).map { r.u8(it) }, null, null)
        }
        cachedSecurityInfo = info
        return info
    }

    fun usesUsbJtagSerial(): Boolean = transport.usbVendorId == ESPRESSIF_VID && transport.usbProductId == USB_JTAG_SERIAL_PID
    fun usesUsbOtg(): Boolean = transport.usbVendorId == ESPRESSIF_VID && transport.usbProductId == chip.imageChipId

    /** ROM transfers must be capped to [USB_OTG_BLOCK_SIZE] on USB-OTG (or when VID/PID are unknown). */
    private fun useUsbOtgBlock(): Boolean {
        if (!chip.usbOtgSupported || isStub) return false
        val noVidPid = transport.usbVendorId == null && transport.usbProductId == null
        return usesUsbOtg() || noVidPid
    }

    /** "USB-Serial/JTAG", "USB-OTG" or null when an external USB-UART bridge is used. */
    fun usbMode(): String? = when {
        usesUsbJtagSerial() -> "USB-Serial/JTAG"
        usesUsbOtg() -> "USB-OTG"
        else -> null
    }

    /** Estimate the crystal frequency from the UART clock divider (40 or 26 MHz, 48 on some parts). */
    fun estimateCrystalFreq(): Int {
        val uartDiv = readReg(chip.uartClkdivReg) and chip.uartClkdivMask
        val est = transport.baudRate.toDouble() * uartDiv / 1e6 / chip.xtalClkDivider
        val norm = when { est > 45 -> 48; est > 33 -> 40; else -> 26 }
        if (Math.abs(norm - est) > 1) log.warn("Detected crystal freq %.2f MHz is quite different to normalized freq %d MHz. Unsupported crystal in use?".format(est, norm))
        return norm
    }

    /** Verify the chip answers SPI commands (SFDP signature, then RDID). */
    fun verifyFlashConnection(): Boolean {
        if (secureDownloadMode) return true
        val SFDP_SIGNATURE = 0x50444653L
        if (readSpiflashSfdp(0, 32) == SFDP_SIGNATURE) return true
        val fid = flashId(cache = false)
        return fid !in listOf(0xFFFFFFL, 0x000000L, 0xFFFF3FL)
    }

    internal fun parseFlashSizeArg(arg: String): Int =
        chip.flashSizes[arg] ?: throw EspException("Flash size '$arg' is not supported by ${chip.chipName} (choose from ${chip.flashSizes.keys.joinToString()})")

    internal fun parseFlashFreqArg(arg: String): Int =
        chip.flashFrequency[arg] ?: throw EspException("Flash frequency '$arg' is not supported by ${chip.chipName} (choose from ${chip.flashFrequency.keys.joinToString()})")
}
