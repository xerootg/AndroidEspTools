package io.github.xerootg.esploader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class EspDeviceTest {
    private val log = object : EspLog {
        val lines = mutableListOf<String>()
        override fun info(message: String) { lines += message }
        override fun warn(message: String) { lines += "WARN: $message" }
    }

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    private fun newDevice(variant: FakeEspTransport.Variant = FakeEspTransport.Variant.ESP32): Pair<FakeEspTransport, EspDevice> {
        val t = FakeEspTransport(variant)
        val loader = EspLoader(t, log)
        if (variant == FakeEspTransport.Variant.ESP32) t.stubEntry = StubFlasher.load("esp32.json").entry
        if (variant == FakeEspTransport.Variant.ESP32S3) t.stubEntry = StubFlasher.load("esp32s3.json").entry
        if (variant == FakeEspTransport.Variant.ESP32P4) t.stubEntry = StubFlasher.load("esp32p4-rev1.json").entry
        return t to EspDevice(loader)
    }

    @Test
    fun connectDetectsEsp32ByMagicAndRunsStub() {
        val (t, dev) = newDevice()
        dev.connect(useStub = true, baud = 921600)
        assertEquals("ESP32", dev.chip.chipName)
        assertTrue(dev.isStub)
        assertTrue(t.stubRunning)
        assertEquals(921600, t.baudRate)
        assertEquals(921600L, t.lastChangeBaud)
        // the classic reset sequence was used (RTS = EN, DTR = IO0)
        assertEquals(listOf("DTR=false", "RTS=true", "DTR=true", "RTS=false", "DTR=false"), t.resetLog.take(5))
        val info = dev.chipInfo()
        assertEquals("4MB", info.flash!!.sizeName)
        assertEquals(0xC8, info.flash!!.vendorId)
        assertEquals(40, info.crystalMhz)
        assertTrue(info.description.startsWith("ESP32-D0WDQ6"))
    }

    @Test
    fun connectDetectsChipIdViaSecurityInfo() {
        val (_, dev) = newDevice(FakeEspTransport.Variant.ESP32P4)
        dev.connect(useStub = false, baud = 115200)
        assertEquals("ESP32-P4", dev.chip.chipName)
        assertFalse(dev.isStub)
    }

    @Test
    fun writeFlashCompressedThenVerifyAndReadBack() {
        val (t, dev) = newDevice()
        dev.connect(useStub = true, baud = 460800)
        val app = ByteArray(300_000).also { Random(7).nextBytes(it) }
        val bootloader = fixture("bootloader.bin") // P4 image on an ESP32: chip id mismatch must be reported, not fatal unless at the bootloader offset
        val parts = listOf(FlashPart(0x10000, app, "app.bin"), FlashPart(0x8000, fixture("partition-table.bin"), "pt.bin"))
        val progress = mutableListOf<Long>()
        dev.writeFlash(parts, WriteFlashOptions(flashSize = "detect"), progress = { done, _, _ -> progress += done })
        assertArrayEquals(app, t.flash.copyOfRange(0x10000, 0x10000 + app.size))
        assertArrayEquals(fixture("partition-table.bin"), t.flash.copyOfRange(0x8000, 0x8000 + 3072))
        assertEquals(4L * 1024 * 1024, t.flashParamsSize)
        assertTrue(progress.last() >= app.size)
        assertTrue(log.lines.joinToString("\n"), log.lines.any { it.startsWith("Hash of data verified") })
        // bootloader with wrong chip id at the bootloader offset is rejected
        try {
            dev.writeFlash(listOf(FlashPart(0x1000, bootloader, "bootloader.bin")))
            throw AssertionError("expected chip mismatch")
        } catch (e: EspException) { assertTrue(e.message!!.contains("not an ESP32 image")) }
        // ... but allowed with force
        dev.writeFlash(listOf(FlashPart(0x1000, bootloader, "bootloader.bin")), WriteFlashOptions(force = true))
        assertArrayEquals(bootloader, t.flash.copyOfRange(0x1000, 0x1000 + bootloader.size))

        val out = ByteArrayOutputStream()
        dev.readFlash(0x10000, app.size.toLong(), out)
        assertArrayEquals(app, out.toByteArray())

        val results = dev.verifyFlash(listOf(FlashPart(0x10000, app, "app.bin")))
        assertTrue(results.single().matches)

        dev.eraseRegion(0x10000, 0x2000)
        assertTrue(t.flash.copyOfRange(0x10000, 0x12000).all { it == 0xFF.toByte() })
        assertFalse(t.flash[0x12000].toInt() == 0xFF.toByte().toInt() && app[0x2000].toInt() != 0xFF.toByte().toInt())
        dev.eraseFlash()
        assertTrue(t.flash.all { it == 0xFF.toByte() })
    }

    @Test
    fun romOnlyWriteAlignsAndReadsSlowly() {
        val (t, dev) = newDevice()
        dev.connect(useStub = false, baud = 115200)
        assertFalse(dev.isStub)
        val data = ByteArray(5000) { (it * 7).toByte() }
        dev.writeFlash(listOf(FlashPart(0x10100, data, "odd.bin")), WriteFlashOptions(compress = false))
        assertArrayEquals(data, t.flash.copyOfRange(0x10100, 0x10100 + data.size))
        val out = ByteArrayOutputStream()
        dev.readFlash(0x10100, 200, out)
        assertArrayEquals(data.copyOfRange(0, 200), out.toByteArray())
        try { dev.eraseFlash(); throw AssertionError() } catch (e: NotImplementedInRomException) {}
    }

    @Test
    fun loadRamRunsImage() {
        val (t, dev) = newDevice()
        dev.connect(useStub = false, baud = 115200)
        t.stubEntry = 0 // make MEM_END with the image entry a plain run
        dev.loadRam(fixture("bootloader.bin"))
        assertEquals(5, t.ram.size) // 3 segments split into 0x1800-byte blocks
        assertEquals(5672 + 4028 + 13844, t.ram.values.sumOf { it.size })
    }

    @Test
    fun cancellationStopsLongOperations() {
        val t = FakeEspTransport()
        t.stubEntry = StubFlasher.load("esp32.json").entry
        var cancelled = false
        val dev = EspDevice(EspLoader(t, log, CancelToken { cancelled }))
        dev.connect(useStub = true, baud = 115200)
        cancelled = true
        try { dev.writeFlash(listOf(FlashPart(0, ByteArray(100_000)))); throw AssertionError() } catch (e: EspCancelledException) {}
    }
}
