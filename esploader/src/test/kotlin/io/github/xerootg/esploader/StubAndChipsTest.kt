package io.github.xerootg.esploader

import io.github.xerootg.esploader.chips.Chips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StubAndChipsTest {
    @Test
    fun everyChipHasALoadableStub() {
        val loader = EspLoader(FakeEspTransport())
        for (chip in Chips.all) {
            val names = if (chip.chipName == "ESP32-P4") listOf("esp32p4.json", "esp32p4-rev1.json") else listOf(chip.stubJsonName(loader))
            for (name in names) {
                val stub = StubFlasher.load(name)
                assertTrue("$name text", stub.text.isNotEmpty())
                assertTrue("$name entry in text", stub.entry >= stub.textStart && stub.entry < stub.textStart + stub.text.size)
                assertNotNull("$name data", stub.data)
            }
        }
    }

    @Test
    fun registryLookups() {
        assertEquals("ESP32-P4", Chips.byImageChipId(18)!!.chipName)
        assertEquals("ESP32", Chips.byMagic(0x00F01D83)!!.chipName)
        assertEquals("ESP8266", Chips.byMagic(0xFFF0C101)!!.chipName)
        assertEquals("ESP32-C6", Chips.byName("esp32c6")!!.chipName)
        assertEquals("ESP32-S3", Chips.byName("ESP32-S3")!!.chipName)
        assertEquals(0x2000L, Chips.byName("esp32p4")!!.bootloaderFlashOffset)
        assertEquals(0x1000L, Chips.byName("esp32")!!.bootloaderFlashOffset)
        assertEquals(0x0L, Chips.byName("esp32s3")!!.bootloaderFlashOffset)
    }

    @Test
    fun chipConstantsAreDistinct() {
        val ids = Chips.all.filter { !it.usesMagicValue }.map { it.imageChipId }
        assertEquals(ids.size, ids.toSet().size)
        for (c in Chips.all) assertTrue(c.chipName, c.flashSizes.isNotEmpty() && c.flashFrequency.isNotEmpty())
    }
}
