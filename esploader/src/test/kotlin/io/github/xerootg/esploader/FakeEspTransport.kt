package io.github.xerootg.esploader

import io.github.xerootg.esploader.transport.SerialTransport
import io.github.xerootg.esploader.transport.Slip
import io.github.xerootg.esploader.util.le32
import io.github.xerootg.esploader.util.readLe32
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.Inflater

/**
 * In-memory emulation of an ESP32 (or ESP32-S3-like chip with security info) ROM loader and
 * the flasher stub, enough to exercise the full connect → stub → flash → verify flow.
 */
class FakeEspTransport(
    val variant: Variant = Variant.ESP32,
    flashSizeBytes: Int = 4 * 1024 * 1024,
    override val usbVendorId: Int? = 0x10C4,
    override val usbProductId: Int? = 0xEA60,
) : SerialTransport {
    enum class Variant(val chipId: Int, val magic: Long) { ESP32(0, 0x00F01D83L), ESP32S3(9, 0L), ESP32P4(18, 0L) }

    val flash = ByteArray(flashSizeBytes) { 0xFF.toByte() }
    val regs = HashMap<Long, Long>()
    val ram = HashMap<Long, ByteArray>()
    var stubRunning = false
    var stubEntry: Long = 0
    var flashParamsSize: Long = -1
    var chipRevisionWord: Long = 0
    val resetLog = mutableListOf<String>()
    var dtrState = false; var rtsState = false
    var inDownloadMode = true
    var lastChangeBaud: Long = -1
    override var baudRate: Int = 115200
    override val isOpen = true

    private val inbox = ArrayDeque<ByteArray>()
    private var pending = ByteArrayOutputStream()
    private var inPacket = false
    private var esc = false

    // flash write state
    private var writeOffset = 0L
    private var writeBlockSize = 0
    private var inflater: Inflater? = null
    private var deflWritten = 0L

    init {
        regs[EspLoader.CHIP_DETECT_MAGIC_REG_ADDR] = variant.magic
        // ESP32 ROM crystal calibration: 12800 * 15625 * 8 / 40 = 40 MHz
        regs[0x3FF5F06CL] = 12800L shl 7
        regs[0x3FF5A000L + 16] = 8 // efuse word 4: CK8M_FREQ
        // UART clock divider: baud * div / 1e6 = 40 → div = 40e6/115200 ≈ 347
        regs[0x3FF40014L] = 347
        regs[0x60000014L] = 347
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        val p = inbox.pollFirst() ?: return 0
        val n = minOf(p.size, dest.size)
        System.arraycopy(p, 0, dest, 0, n)
        if (n < p.size) inbox.addFirst(p.copyOfRange(n, p.size))
        return n
    }

    override fun write(data: ByteArray) {
        for (b in data) {
            if (!inPacket) { if (b == Slip.END) { inPacket = true; pending = ByteArrayOutputStream() }; continue }
            if (esc) { esc = false; pending.write(if (b == Slip.ESC_END) 0xC0 else 0xDB); continue }
            when (b) {
                Slip.ESC -> esc = true
                Slip.END -> { inPacket = false; handlePacket(pending.toByteArray()) }
                else -> pending.write(b.toInt())
            }
        }
    }

    override fun setDtr(asserted: Boolean) { dtrState = asserted; resetLog += "DTR=$asserted"; checkReset() }
    override fun setRts(asserted: Boolean) { rtsState = asserted; resetLog += "RTS=$asserted"; checkReset() }
    private fun checkReset() {
        // EN low (RTS asserted) resets the chip; IO0 (DTR) at release time selects download mode.
        if (rtsState) { stubRunning = false; inDownloadMode = dtrState }
    }
    override fun purgeInput() { inbox.clear() }
    override fun available(): Int = inbox.sumOf { it.size }
    override fun close() {}

    private fun send(raw: ByteArray) = inbox.addLast(Slip.encode(raw))

    private fun respond(op: Int, value: Long, data: ByteArray = ByteArray(0), status: Int = 0, error: Int = 0) {
        val tail = if (stubRunning) byteArrayOf(status.toByte(), error.toByte()) else byteArrayOf(status.toByte(), error.toByte(), 0, 0)
        val body = data + tail
        val pkt = ByteArray(8 + body.size)
        pkt[0] = 1; pkt[1] = op.toByte()
        pkt[2] = (body.size and 0xFF).toByte(); pkt[3] = (body.size shr 8).toByte()
        System.arraycopy(le32(value.toInt()), 0, pkt, 4, 4)
        System.arraycopy(body, 0, pkt, 8, body.size)
        send(pkt)
    }

    private fun handlePacket(p: ByteArray) {
        if (p.size < 8 || p[0].toInt() != 0) return // flash-read ACKs and junk
        if (!inDownloadMode) return // app is running: no ROM loader to answer
        val op = p[1].toInt() and 0xFF
        val len = (p[2].toInt() and 0xFF) or ((p[3].toInt() and 0xFF) shl 8)
        val chk = p.readLe32(4)
        val d = p.copyOfRange(8, 8 + len)
        when (op) {
            EspLoader.ESP_SYNC -> repeat(8) { respond(op, if (stubRunning) 0 else 0x12345678L) }
            EspLoader.ESP_READ_REG -> respond(op, regs[d.readLe32(0)] ?: 0L)
            EspLoader.ESP_WRITE_REG -> {
                var i = 0
                while (i + 16 <= d.size) {
                    val addr = d.readLe32(i); val value = d.readLe32(i + 4); val mask = d.readLe32(i + 8)
                    val old = regs[addr] ?: 0L
                    regs[addr] = (old and mask.inv() and 0xFFFFFFFFL) or (value and mask)
                    spiEmulation(addr)
                    i += 16
                }
                respond(op, 0)
            }
            EspLoader.ESP_GET_SECURITY_INFO -> when (variant) {
                Variant.ESP32 -> respond(op, 0, status = 1, error = 5)
                else -> respond(op, 0, le32(0, 0) + ByteArray(4) + le32(variant.chipId, 1))
            }
            EspLoader.ESP_MEM_BEGIN -> respond(op, 0)
            EspLoader.ESP_MEM_DATA -> {
                val size = d.readLe32(0).toInt()
                val data = d.copyOfRange(16, 16 + size)
                check(EspLoader.checksum(data) == chk.toInt()) { "bad RAM block checksum" }
                ram[ram.size.toLong()] = data
                respond(op, 0)
            }
            EspLoader.ESP_MEM_END -> {
                val entry = d.readLe32(4)
                respond(op, 0)
                if (entry != 0L && entry == stubEntry) { stubRunning = true; send("OHAI".toByteArray()) }
            }
            EspLoader.ESP_FLASH_BEGIN -> {
                val eraseSize = d.readLe32(0); val blockSize = d.readLe32(8).toInt(); val offset = d.readLe32(12)
                writeOffset = offset; writeBlockSize = blockSize
                if (!stubRunning) java.util.Arrays.fill(flash, offset.toInt(), (offset + eraseSize).toInt(), 0xFF.toByte())
                respond(op, 0)
            }
            EspLoader.ESP_FLASH_DATA -> {
                val size = d.readLe32(0).toInt(); val seq = d.readLe32(4).toInt()
                val data = d.copyOfRange(16, 16 + size)
                check(EspLoader.checksum(data) == chk.toInt()) { "bad flash block checksum" }
                val at = (writeOffset + seq.toLong() * writeBlockSize).toInt()
                System.arraycopy(data, 0, flash, at, data.size)
                respond(op, 0)
            }
            EspLoader.ESP_FLASH_END -> respond(op, 0)
            EspLoader.ESP_FLASH_DEFL_BEGIN -> {
                val writeSize = d.readLe32(0); val offset = d.readLe32(12)
                writeOffset = offset; deflWritten = 0; inflater = Inflater()
                java.util.Arrays.fill(flash, offset.toInt(), (offset + writeSize).toInt(), 0xFF.toByte())
                respond(op, 0)
            }
            EspLoader.ESP_FLASH_DEFL_DATA -> {
                val size = d.readLe32(0).toInt()
                val data = d.copyOfRange(16, 16 + size)
                check(EspLoader.checksum(data) == chk.toInt()) { "bad defl block checksum" }
                val inf = inflater!!
                inf.setInput(data)
                val buf = ByteArray(65536)
                while (!inf.needsInput() && !inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) break
                    System.arraycopy(buf, 0, flash, (writeOffset + deflWritten).toInt(), n)
                    deflWritten += n
                }
                respond(op, 0)
            }
            EspLoader.ESP_FLASH_DEFL_END -> respond(op, 0)
            EspLoader.ESP_SPI_FLASH_MD5 -> {
                val addr = d.readLe32(0).toInt(); val size = d.readLe32(4).toInt()
                val digest = MessageDigest.getInstance("MD5").digest(flash.copyOfRange(addr, addr + size))
                if (stubRunning) respond(op, 0, digest)
                else respond(op, 0, digest.joinToString("") { "%02x".format(it) }.toByteArray())
            }
            EspLoader.ESP_SPI_ATTACH -> respond(op, 0)
            EspLoader.ESP_SPI_SET_PARAMS -> { flashParamsSize = d.readLe32(4); respond(op, 0) }
            EspLoader.ESP_CHANGE_BAUDRATE -> { lastChangeBaud = d.readLe32(0); respond(op, 0) }
            EspLoader.ESP_READ_FLASH_SLOW -> {
                if (stubRunning) { respond(op, 0, status = 1, error = 0xC3); return }
                val addr = d.readLe32(0).toInt(); val size = d.readLe32(4).toInt()
                respond(op, 0, flash.copyOfRange(addr, addr + size) + ByteArray(64 - size))
            }
            EspLoader.ESP_ERASE_FLASH -> if (stubRunning) { java.util.Arrays.fill(flash, 0xFF.toByte()); respond(op, 0) } else respond(op, 0, status = 1, error = 5)
            EspLoader.ESP_ERASE_REGION -> if (stubRunning) {
                val off = d.readLe32(0).toInt(); val size = d.readLe32(4).toInt()
                java.util.Arrays.fill(flash, off, off + size, 0xFF.toByte()); respond(op, 0)
            } else respond(op, 0, status = 1, error = 5)
            EspLoader.ESP_READ_FLASH -> {
                if (!stubRunning) { respond(op, 0, status = 1, error = 5); return }
                val off = d.readLe32(0).toInt(); val size = d.readLe32(4).toInt(); val block = d.readLe32(8).toInt()
                respond(op, 0)
                var sent = 0
                while (sent < size) {
                    val n = minOf(block, size - sent)
                    send(flash.copyOfRange(off + sent, off + sent + n))
                    sent += n
                }
                send(MessageDigest.getInstance("MD5").digest(flash.copyOfRange(off, off + size)))
            }
            EspLoader.ESP_RUN_USER_CODE -> { inDownloadMode = false }
            else -> respond(op, 0, status = 1, error = 5)
        }
    }

    /** Emulate the SPI user-command register interface for RDID / SFDP reads. */
    private fun spiEmulation(addr: Long) {
        val bases = listOf(0x3FF42000L, 0x60002000L, 0x5008D000L)
        for (base in bases) {
            if (addr != base) continue
            val cmdReg = regs[base] ?: 0L
            if (cmdReg and (1L shl 18) == 0L) return
            val usr2Offs = if (base == 0x3FF42000L) 0x24L else 0x20L
            val w0Offs = if (base == 0x3FF42000L) 0x80L else 0x58L
            val cmd = (regs[base + usr2Offs] ?: 0L) and 0xFF
            regs[base + w0Offs] = when (cmd.toInt()) {
                0x9F -> 0x1640C8L   // GD25Q32: vendor C8, 0x40 0x16 → 4MB
                0x5A -> 0x50444653L // "SFDP"
                0x05 -> 0x00L
                0x35 -> 0x02L
                else -> 0L
            }
            regs[base] = cmdReg and (1L shl 18).inv() // command completes immediately
        }
    }
}
