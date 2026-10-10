package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.NotSupportedException

/** Common ground for the RISC-V parts whose registers mirror the ESP32-C3 layout. */
abstract class Esp32C3Like : Esp32Family() {
    override val usbSerialJtagSupported = true
    override val securityInfoSupported = true
    override val spiRegBase: Long = 0x60002000L
    override val uartDateRegAddr: Long = 0x60000000L + 0x7C
    override val uartClkdivReg: Long = 0x60000014L
    override val flashSizes = STANDARD_FLASH_SIZES
    override val flashFrequency: Map<String, Int> = STANDARD_FLASH_FREQUENCY
    override fun crystalFreq(l: EspLoader) = 40
    override fun flashVoltage(l: EspLoader) = throw NotSupportedException("Reading flash voltage", chipName)
    override fun postConnect(l: EspLoader) {
        if (!l.secureDownloadMode && !l.syncStubDetected) disableWatchdogs(l)
    }
}

object Esp32C3 : Esp32C3Like() {
    override val chipName = "ESP32-C3"
    override val imageChipId = 5
    override val watchdogResetSupported = true
    override val efuseBase = 0x60008800L
    private const val RTCCNTL_BASE_REG = 0x60008000L
    override val rtcSwdConfReg = RTCCNTL_BASE_REG + 0x00AC
    override val rtcSwdAutoFeedEn = 1L shl 31
    override val rtcSwdWprotectReg = RTCCNTL_BASE_REG + 0x00B0
    override val rtcSwdWkey = 0x8F1D312AL
    override val rtcWdtConfig0Reg = RTCCNTL_BASE_REG + 0x0090
    override val rtcWdtConfig1Reg = RTCCNTL_BASE_REG + 0x0094
    override val rtcWdtWprotectReg = RTCCNTL_BASE_REG + 0x00A8
    override val uf2FamilyId = 0xD42BA06CL
    override val keyPurposes = KEY_PURPOSES_S2
    override val memoryMap = listOf(
        MemRegion(0x3C000000, 0x3C800000, "DROM"), MemRegion(0x3FC80000, 0x3FCE0000, "DRAM"),
        MemRegion(0x40000000, 0x40060000, "IROM_MASK"), MemRegion(0x4037C000, 0x403E0000, "IRAM"),
        MemRegion(0x42000000, 0x42800000, "IROM"), MemRegion(0x50000000, 0x50002000, "RTC_IRAM"),
    )

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 3) shr 21) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader): Int {
        val hi = ((block1Word(l, 5) shr 23) and 0x01).toInt()
        val low = ((block1Word(l, 3) shr 18) and 0x07).toInt()
        return (hi shl 3) + low
    }
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 5) shr 24) and 0x03).toInt()
    private fun flashCap(l: EspLoader) = ((block1Word(l, 3) shr 27) and 0x07).toInt()
    private fun flashVendor(l: EspLoader) = when ((block1Word(l, 4) and 0x07).toInt()) { 1 -> "XMC"; 2 -> "GD"; 3 -> "FM"; 4 -> "TT"; 5 -> "ZBIT"; else -> "" }

    override fun chipDescription(l: EspLoader): String {
        val name = when (pkgVersion(l)) { 0 -> "ESP32-C3 (QFN32)"; 1 -> "ESP8685 (QFN28)"; 2 -> "ESP32-C3 AZ (QFN32)"; 3 -> "ESP8686 (QFN24)"; else -> "Unknown ESP32-C3" }
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi", "BT 5 (LE)", "Single Core", "160MHz")
        val flash = when (flashCap(l)) { 0 -> null; 1 -> "Embedded Flash 4MB"; 2 -> "Embedded Flash 2MB"; 3 -> "Embedded Flash 1MB"; 4 -> "Embedded Flash 8MB"; else -> "Unknown Embedded Flash" }
        if (flash != null) f += "$flash (${flashVendor(l)})"
        return f
    }

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)
}

object Esp32C2 : Esp32C3Like() {
    override val chipName = "ESP32-C2"
    override val imageChipId = 12
    override val usbSerialJtagSupported = false
    override val watchdogResetSupported = true
    override val efuseBase = 0x60008800L
    private val EFUSE_BLOCK2_ADDR = efuseBase + 0x040
    override val macEfuseReg = efuseBase + 0x040
    override val secureBootEnReg = efuseBase + 0x30
    override val secureBootEnMask = 1L shl 21
    override val spiBootCryptCntReg = efuseBase + 0x30
    override val spiBootCryptCntMask = 0x7L shl 7
    override val disDownloadManualEncryptReg = efuseBase + 0x30
    override val disDownloadManualEncryptMask = 1L shl 6
    private const val RTCCNTL_BASE_REG = 0x60008000L
    override val rtcWdtConfig0Reg = RTCCNTL_BASE_REG + 0x0084
    override val rtcWdtConfig1Reg = RTCCNTL_BASE_REG + 0x0088
    override val rtcWdtWprotectReg = RTCCNTL_BASE_REG + 0x009C
    override val flashFrequency = mapOf("60m" to 0xF, "30m" to 0x0, "20m" to 0x1, "15m" to 0x2)
    override val uf2FamilyId = 0x2B88D29CL
    override val memoryMap = listOf(
        MemRegion(0x3C000000, 0x3C400000, "DROM"), MemRegion(0x3FCA0000, 0x3FCE0000, "DRAM"),
        MemRegion(0x40000000, 0x40090000, "IROM_MASK"), MemRegion(0x4037C000, 0x403C0000, "IRAM"),
        MemRegion(0x42000000, 0x42400000, "IROM"),
    )

    private fun block2Word(l: EspLoader, n: Int) = l.readReg(EFUSE_BLOCK2_ADDR + 4L * n)
    fun pkgVersion(l: EspLoader) = ((block2Word(l, 1) shr 22) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block2Word(l, 1) shr 16) and 0xF).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block2Word(l, 1) shr 20) and 0x3).toInt()
    private fun flashCap(l: EspLoader) = ((block2Word(l, 7) shr 29) and 0x7).toInt()
    private fun flashVendor(l: EspLoader) = when (((block2Word(l, 7) shr 24) and 0x7).toInt()) { 1 -> "XMC"; 2 -> "GD"; 3 -> "FM"; 4 -> "TT"; 5 -> "ZBIT"; else -> "" }

    override fun chipDescription(l: EspLoader): String {
        val name = when (pkgVersion(l)) { 0 -> "ESP32-C2"; 1 -> "ESP8684H"; else -> "Unknown ESP32-C2" }
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi", "BT 5 (LE)", "Single Core", "120MHz")
        val flash = when (flashCap(l)) { 0 -> null; 1 -> "Embedded Flash 4MB"; 2 -> "Embedded Flash 2MB"; 3 -> "Embedded Flash 1MB"; else -> "Unknown Embedded Flash" }
        if (flash != null) f += "$flash (${flashVendor(l)})"
        return f
    }

    override fun crystalFreq(l: EspLoader) = l.estimateCrystalFreq()

    override fun changeBaud(l: EspLoader, baud: Int) {
        if (l.secureDownloadMode) {
            l.log.warn("Baud rate change is not supported in secure download mode. Keeping 115200 baud.")
            return
        }
        val romWith26M = !l.isStub && crystalFreq(l) == 26
        if (romWith26M) l.changeBaudRaw(baud, baud.toLong() * 40 / 26, 0) else l.changeBaudDefault(baud)
    }

    override fun postConnect(l: EspLoader) {
        if (!l.secureDownloadMode && chipRevision(l) == 0) {
            l.stubDisabledForCompatibility = true
        }
    }

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)
}
