package io.github.xerootg.esploader

import io.github.xerootg.esploader.chips.Esp32P4
import io.github.xerootg.esploader.image.FirmwareImage
import io.github.xerootg.esploader.image.patchFlashParams
import io.github.xerootg.esploader.manifest.FlashManifest
import io.github.xerootg.esploader.manifest.FlashManifests
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareImageTest {
    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/fixtures/$name")!!.readBytes()

    @Test
    fun parsesEsp32P4Bootloader() {
        val img = FirmwareImage.parse(fixture("bootloader.bin"))
        assertFalse(img.esp8266)
        assertEquals(18, img.chipId)
        assertEquals("ESP32-P4", img.chip!!.chipName)
        assertEquals(3, img.segments.size)
        assertEquals("dio", img.flashModeName)
        assertEquals("32MB", img.flashSizeName())
        assertEquals("40m", img.flashFreqName())
        assertEquals(0x4ff29edaL, img.entrypoint)
        assertTrue(img.checksumValid)
        assertEquals(true, img.digestValid)
        assertEquals(199, img.maxRevFull)
        val bl = img.bootloaderDescription
        assertNotNull(bl)
        assertTrue(bl!!.idfVersion, bl.idfVersion.startsWith("v5.5"))
    }

    @Test
    fun patchFlashParamsRewritesHeaderAndDigest() {
        val data = fixture("bootloader.bin")
        val log = EspLog.Silent
        assertSame(data, patchFlashParams(Esp32P4, 0x2000, data, null, null, null, log))
        assertSame(data, patchFlashParams(Esp32P4, 0x10000, data, 0, null, null, log))
        val patched = patchFlashParams(Esp32P4, 0x2000, data, FirmwareImage.FLASH_MODES["qio"], Esp32P4.flashFrequency["80m"], Esp32P4.flashSizes["16MB"], log)
        val img = FirmwareImage.parse(patched)
        assertEquals("qio", img.flashModeName)
        assertEquals("16MB", img.flashSizeName())
        assertEquals("80m", img.flashFreqName())
        assertEquals(true, img.digestValid)
        assertTrue(img.checksumValid)
        // the rest of the image is untouched
        assertArrayEquals(data.copyOfRange(4, img.dataLength), patched.copyOfRange(4, img.dataLength))
    }

    @Test
    fun rejectsGarbage() {
        try { FirmwareImage.parse(ByteArray(64) { 0x55 }); throw AssertionError("expected failure") } catch (e: EspException) {}
        assertFalse(FirmwareImage.looksLikeImage(fixture("partition-table.bin")))
    }

    @Test
    fun parsesIdfFlasherArgs() {
        val m = FlashManifests.parse(String(fixture("flasher_args.json")), "firmware").single()
        assertEquals(FlashManifest.Source.ESP_IDF_FLASHER_ARGS, m.source)
        assertEquals("ESP32-P4", m.chipFamily)
        assertEquals(listOf(0x2000L, 0x8000L, 0x19000L, 0x20000L), m.parts.map { it.offset })
        assertEquals("bootloader/bootloader.bin", m.parts[0].path)
        assertEquals("dio", m.flashMode); assertEquals("32MB", m.flashSize); assertEquals("40m", m.flashFreq)
    }

    @Test
    fun parsesWebToolsManifest() {
        val text = """{"name":"Demo","version":"1.2","new_install_prompt_erase":true,"builds":[
            {"chipFamily":"ESP32-C3","parts":[{"path":"app.bin","offset":"0x10000"},{"path":"boot.bin","offset":0}]},
            {"chipFamily":"ESP32","parts":[{"path":"merged.bin","offset":4096}]}]}"""
        val ms = FlashManifests.parse(text)
        assertEquals(2, ms.size)
        assertEquals("ESP32-C3", ms[0].chipFamily)
        assertEquals(listOf(0L, 0x10000L), ms[0].parts.map { it.offset })
        assertTrue(ms[0].eraseFirst)
        assertEquals("1.2", ms[1].version)
        assertEquals("ESP32-P4", FlashManifests.normaliseChipFamily("esp32p4"))
        assertEquals("ESP32-S3", FlashManifests.normaliseChipFamily("ESP32_S3"))
    }
}
