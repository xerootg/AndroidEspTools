package io.github.xerootg.esploader

import io.github.xerootg.esploader.transport.Slip
import io.github.xerootg.esploader.transport.SlipReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProtocolTest {
    @Test
    fun slipEscapesDelimiters() {
        val payload = byteArrayOf(0x01, 0xC0.toByte(), 0x02, 0xDB.toByte(), 0x03)
        val encoded = Slip.encode(payload)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x01, 0xDB.toByte(), 0xDC.toByte(), 0x02, 0xDB.toByte(), 0xDD.toByte(), 0x03, 0xC0.toByte()), encoded)
        val t = FakeEspTransport()
        val reader = SlipReader(t, EspLog.Silent)
        // feed the frame through the transport's inbox
        val inbox = t.javaClass.getDeclaredField("inbox").also { it.isAccessible = true }.get(t) as java.util.ArrayDeque<ByteArray>
        inbox.add(encoded)
        assertArrayEquals(payload, reader.readPacket(100))
    }

    @Test
    fun slipReaderTimesOutWithoutData() {
        val reader = SlipReader(FakeEspTransport(), EspLog.Silent)
        try { reader.readPacket(10); fail("expected timeout") } catch (e: EspTimeoutException) { assertTrue(e.message!!.contains("No serial data")) }
    }

    @Test
    fun checksumMatchesRom() {
        assertEquals(0xEF, EspLoader.checksum(ByteArray(0)))
        assertEquals(0xEF xor 0x12 xor 0x34, EspLoader.checksum(byteArrayOf(0x12, 0x34)))
    }

    @Test
    fun timeoutPerMbHasFloor() {
        assertEquals(EspLoader.DEFAULT_TIMEOUT, EspLoader.timeoutPerMb(EspLoader.MD5_TIMEOUT_PER_MB, 1024))
        assertEquals(32_000, EspLoader.timeoutPerMb(EspLoader.MD5_TIMEOUT_PER_MB, 4_000_000))
    }

    @Test
    fun errorCodesAreDescribed() {
        val e = EspException.withResult("Failed to write", byteArrayOf(1, 0xC1.toByte()))
        assertTrue(e.message!!.contains("ESP_BAD_DATA_CHECKSUM"))
    }
}
