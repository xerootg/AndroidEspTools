package io.github.xerootg.esploader.transport

import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.EspLog
import io.github.xerootg.esploader.EspTimeoutException
import io.github.xerootg.esploader.util.hex
import java.io.ByteArrayOutputStream

/** SLIP framing as used by the ESP ROM loader protocol. */
object Slip {
    const val END: Byte = 0xC0.toByte()
    const val ESC: Byte = 0xDB.toByte()
    const val ESC_END: Byte = 0xDC.toByte()
    const val ESC_ESC: Byte = 0xDD.toByte()

    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size + 16)
        out.write(0xC0)
        for (b in payload) {
            when (b) {
                END -> { out.write(0xDB); out.write(0xDC) }
                ESC -> { out.write(0xDB); out.write(0xDD) }
                else -> out.write(b.toInt())
            }
        }
        out.write(0xC0)
        return out.toByteArray()
    }
}

/**
 * Stateful SLIP packet reader over a [SerialTransport]. Behaves like esptool's `slip_reader`
 * generator: bytes read from the port but not yet consumed stay buffered between calls.
 */
class SlipReader(private val transport: SerialTransport, private val log: EspLog) {
    private val chunk = ByteArray(16 * 1024)
    private var chunkPos = 0
    private var chunkLen = 0
    private var successfulSlip = false

    /** Forget any buffered bytes and flush the port's input queue. */
    fun reset() {
        chunkPos = 0
        chunkLen = 0
        transport.purgeInput()
    }

    /** Raw bytes still buffered, consumed. Used to inspect the boot log after a reset. */
    fun drainBuffered(): ByteArray {
        val out = ByteArrayOutputStream()
        if (chunkLen > chunkPos) out.write(chunk, chunkPos, chunkLen - chunkPos)
        chunkPos = 0; chunkLen = 0
        val tmp = ByteArray(4096)
        while (transport.available() > 0) {
            val n = transport.read(tmp, 10)
            if (n <= 0) break
            out.write(tmp, 0, n)
        }
        return out.toByteArray()
    }

    private fun fill(timeoutMs: Int): Boolean {
        val n = transport.read(chunk, timeoutMs)
        if (n <= 0) return false
        chunkPos = 0
        chunkLen = n
        if (log !== EspLog.Silent) log.trace("Read $n bytes: ${chunk.hex(0, n)}")
        return true
    }

    /**
     * Read one complete SLIP packet. [timeoutMs] bounds the wait for the *whole* packet.
     * Throws [EspTimeoutException] when nothing (or only part of a packet) arrives in time.
     */
    fun readPacket(timeoutMs: Int): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        var partial: ByteArrayOutputStream? = null
        var inEscape = false
        while (true) {
            if (chunkPos >= chunkLen) {
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                if (remaining <= 0 || !fill(remaining)) {
                    val msg = when {
                        partial == null && successfulSlip -> "Serial data stream stopped: Possible serial noise or corruption."
                        partial == null -> "No serial data received."
                        else -> "Packet content transfer stopped (received ${partial.size()} bytes)."
                    }
                    throw EspTimeoutException(msg)
                }
            }
            val b = chunk[chunkPos++]
            if (partial == null) {
                if (b == Slip.END) {
                    partial = ByteArrayOutputStream()
                } else {
                    val remainingData = drainBuffered()
                    val all = byteArrayOf(b) + remainingData
                    detectPanicHandler(all)
                    throw EspException("Invalid head of packet (0x%02x): Possible serial noise or corruption.".format(b.toInt() and 0xFF))
                }
            } else if (inEscape) {
                inEscape = false
                when (b) {
                    Slip.ESC_END -> partial.write(0xC0)
                    Slip.ESC_ESC -> partial.write(0xDB)
                    else -> {
                        val remainingData = drainBuffered()
                        detectPanicHandler(byteArrayOf(b) + remainingData)
                        throw EspException("Invalid SLIP escape (0xdb, 0x%02x).".format(b.toInt() and 0xFF))
                    }
                }
            } else when (b) {
                Slip.ESC -> inEscape = true
                Slip.END -> {
                    successfulSlip = true
                    val pkt = partial.toByteArray()
                    if (log !== EspLog.Silent) log.trace("Received full packet: ${pkt.hex()}")
                    return pkt
                }
                else -> partial.write(b.toInt())
            }
        }
    }

    private fun detectPanicHandler(input: ByteArray) {
        val text = String(input, Charsets.ISO_8859_1)
        val guru = Regex("G?uru Meditation Error: (?:Core \\d panic'ed \\(([a-zA-Z ]*)\\))?")
        val fatal = Regex("F?atal exception \\(\\d+\\): (?:([a-zA-Z ]*)?.*epc)?", RegexOption.DOT_MATCHES_ALL)
        val m = guru.find(text) ?: fatal.find(text) ?: return
        val cause = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }
        throw EspException("Guru Meditation Error detected" + (if (cause != null) " ($cause)" else "") + ".")
    }
}
