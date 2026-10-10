package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.NotSupportedException

object Esp32S2 : Esp32Family() {
    override val chipName = "ESP32-S2"
    override val imageChipId = 2
    override val usbOtgSupported = true
    override val watchdogResetSupported = true
    override val securityInfoSupported = true
    override val usesMagicValue = true
    override val magicValue = 0x000007C6L

    override val spiRegBase = 0x3F402000L
    override val uartClkdivReg = 0x3F400014L
    override val efuseBase = 0x3F41A000L
    override val macEfuseReg = 0x3F41A044L
    private val EFUSE_BLOCK2_ADDR = efuseBase + 0x05C
    override val disDownloadManualEncryptMask = 1L shl 19
    private val EFUSE_RD_REPEAT_DATA3_REG = efuseBase + 0x3C
    private const val GPIO_STRAP_REG = 0x3F404038L
    private const val GPIO_STRAP_SPI_BOOT_MASK = 1L shl 3
    private const val RTC_CNTL_OPTION1_REG = 0x3F408128L
    private const val RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK = 0x1L
    private const val RTCCNTL_BASE_REG = 0x3F408000L
    override val rtcWdtConfig0Reg = RTCCNTL_BASE_REG + 0x0094
    override val rtcWdtConfig1Reg = RTCCNTL_BASE_REG + 0x0098
    override val rtcWdtWprotectReg = RTCCNTL_BASE_REG + 0x00AC

    override val flashSizes = STANDARD_FLASH_SIZES
    override val flashFrequency = STANDARD_FLASH_FREQUENCY
    override val uf2FamilyId = 0xBFDD4EEEL
    override val keyPurposes = KEY_PURPOSES_S2
    override val memoryMap = listOf(
        MemRegion(0x3F000000, 0x3FF80000, "DROM"), MemRegion(0x3F500000, 0x3FF80000, "EXTRAM_DATA"),
        MemRegion(0x3FF9E000, 0x3FFA0000, "RTC_DRAM"), MemRegion(0x3FFB0000, 0x40000000, "DRAM"),
        MemRegion(0x40000000, 0x4001A100, "IROM_MASK"), MemRegion(0x40020000, 0x40070000, "IRAM"),
        MemRegion(0x40070000, 0x40072000, "RTC_IRAM"), MemRegion(0x40080000, 0x40800000, "IROM"),
        MemRegion(0x50000000, 0x50002000, "RTC_DATA"),
    )

    fun pkgVersion(l: EspLoader) = (block1Word(l, 4) and 0x0F).toInt()
    override fun minorChipVersion(l: EspLoader): Int {
        val hi = ((block1Word(l, 3) shr 20) and 0x01).toInt()
        val low = ((block1Word(l, 4) shr 4) and 0x07).toInt()
        return (hi shl 3) + low
    }
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 18) and 0x03).toInt()
    private fun flashCap(l: EspLoader) = ((block1Word(l, 3) shr 21) and 0x0F).toInt()
    private fun psramCap(l: EspLoader) = ((block1Word(l, 3) shr 28) and 0x0F).toInt()
    private fun block2Version(l: EspLoader) = ((l.readReg(EFUSE_BLOCK2_ADDR + 4L * 4) shr 4) and 0x07).toInt()

    override fun chipDescription(l: EspLoader): String {
        val name = when (flashCap(l) + psramCap(l) * 100) {
            0 -> "ESP32-S2"; 1 -> "ESP32-S2FH2"; 2 -> "ESP32-S2FH4"; 102 -> "ESP32-S2FNR2"; 100 -> "ESP32-S2R2"
            else -> "Unknown ESP32-S2"
        }
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi", "Single Core", "240MHz")
        f += when (flashCap(l)) { 0 -> "No Embedded Flash"; 1 -> "Embedded Flash 2MB"; 2 -> "Embedded Flash 4MB"; else -> "Unknown Embedded Flash" }
        f += when (psramCap(l)) { 0 -> "No Embedded PSRAM"; 1 -> "Embedded PSRAM 2MB"; 2 -> "Embedded PSRAM 4MB"; else -> "Unknown Embedded PSRAM" }
        f += when (block2Version(l)) {
            0 -> "No calibration in BLK2 of efuse"
            1 -> "ADC and temperature sensor calibration in BLK2 of eFuse V1"
            2 -> "ADC and temperature sensor calibration in BLK2 of eFuse V2"
            else -> "Unknown calibration in BLK2"
        }
        return f
    }

    override fun crystalFreq(l: EspLoader) = 40
    override fun flashVoltage(l: EspLoader) = throw NotSupportedException("Reading flash voltage", chipName)

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)

    override fun hardReset(l: EspLoader) {
        val usesOtg = l.usesUsbOtg()
        if (usesOtg) {
            val strap = l.readReg(GPIO_STRAP_REG)
            val forceDl = l.readReg(RTC_CNTL_OPTION1_REG)
            if (strap and GPIO_STRAP_SPI_BOOT_MASK == 0L && forceDl and RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK == 0L) {
                watchdogReset(l)
                return
            }
        }
        l.hardResetDefault(usesOtg)
    }
}

object Esp32S3 : Esp32Family() {
    override val chipName = "ESP32-S3"
    override val imageChipId = 9
    override val usbOtgSupported = true
    override val usbSerialJtagSupported = true
    override val watchdogResetSupported = true
    override val securityInfoSupported = true
    override val flash32BitAddrSupported = true

    override val uartDateRegAddr = 0x60000080L
    override val spiRegBase = 0x60002000L
    override val uartClkdivReg = 0x60000014L
    override val efuseBase = 0x60007000L
    private val EFUSE_BLOCK2_ADDR = efuseBase + 0x5C
    private const val RTCCNTL_BASE_REG = 0x60008000L
    override val rtcSwdConfReg = RTCCNTL_BASE_REG + 0x00B4
    override val rtcSwdAutoFeedEn = 1L shl 31
    override val rtcSwdWprotectReg = RTCCNTL_BASE_REG + 0x00B8
    override val rtcSwdWkey = 0x8F1D312AL
    override val rtcWdtConfig0Reg = RTCCNTL_BASE_REG + 0x0098
    override val rtcWdtConfig1Reg = RTCCNTL_BASE_REG + 0x009C
    override val rtcWdtWprotectReg = RTCCNTL_BASE_REG + 0x00B0
    private const val GPIO_STRAP_REG = 0x60004038L
    private const val GPIO_STRAP_SPI_BOOT_MASK = 1L shl 3
    private const val RTC_CNTL_OPTION1_REG = 0x6000812CL
    private const val RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK = 0x1L

    override val flashSizes = STANDARD_FLASH_SIZES
    override val flashFrequency = STANDARD_FLASH_FREQUENCY
    override val uf2FamilyId = 0xC47E5767L
    override val keyPurposes = KEY_PURPOSES_S2
    override val memoryMap = listOf(
        MemRegion(0x3C000000, 0x3D000000, "DROM"), MemRegion(0x3D000000, 0x3E000000, "EXTRAM_DATA"),
        MemRegion(0x600FE000, 0x60100000, "RTC_DRAM"), MemRegion(0x3FC88000, 0x3FD00000, "DRAM"),
        MemRegion(0x40000000, 0x4001A100, "IROM_MASK"), MemRegion(0x40370000, 0x403E0000, "IRAM"),
        MemRegion(0x600FE000, 0x60100000, "RTC_IRAM"), MemRegion(0x42000000, 0x42800000, "IROM"),
        MemRegion(0x50000000, 0x50002000, "RTC_DATA"),
    )

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 3) shr 21) and 0x07).toInt()
    private fun rawMinor(l: EspLoader): Int {
        val hi = ((block1Word(l, 5) shr 23) and 0x01).toInt()
        val low = ((block1Word(l, 3) shr 18) and 0x07).toInt()
        return (hi shl 3) + low
    }
    private fun blkVersionMajor(l: EspLoader) = (l.readReg(EFUSE_BLOCK2_ADDR + 4L * 4) and 0x03).toInt()
    private fun blkVersionMinor(l: EspLoader) = ((block1Word(l, 3) shr 24) and 0x07).toInt()
    private fun isEco0(l: EspLoader, minorRaw: Int) = (minorRaw and 0x7) == 0 && blkVersionMajor(l) == 1 && blkVersionMinor(l) == 1
    override fun minorChipVersion(l: EspLoader): Int { val r = rawMinor(l); return if (isEco0(l, r)) 0 else r }
    override fun majorChipVersion(l: EspLoader): Int {
        if (isEco0(l, rawMinor(l))) return 0
        return ((block1Word(l, 5) shr 24) and 0x03).toInt()
    }

    override fun chipDescription(l: EspLoader): String {
        val name = when (pkgVersion(l)) { 0 -> "ESP32-S3 (QFN56)"; 1 -> "ESP32-S3-PICO-1 (LGA56)"; else -> "Unknown ESP32-S3" }
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    private fun flashCap(l: EspLoader) = ((block1Word(l, 3) shr 27) and 0x07).toInt()
    private fun flashVendor(l: EspLoader) = when ((block1Word(l, 4) and 0x07).toInt()) { 1 -> "XMC"; 2 -> "GD"; 3 -> "FM"; 4 -> "TT"; 5 -> "BY"; else -> "" }
    private fun psramCap(l: EspLoader): Int {
        val cap = ((block1Word(l, 4) shr 3) and 0x03).toInt()
        val hi = ((block1Word(l, 5) shr 19) and 0x01).toInt()
        return (hi shl 2) or cap
    }
    private fun psramVendor(l: EspLoader) = when (((block1Word(l, 4) shr 7) and 0x03).toInt()) { 1 -> "AP_3v3"; 2 -> "AP_1v8"; else -> "" }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi", "BT 5 (LE)", "Dual Core + LP Core", "240MHz")
        when (flashCap(l)) { 0 -> {}; 1 -> f += "Embedded Flash 8MB (${flashVendor(l)})"; 2 -> f += "Embedded Flash 4MB (${flashVendor(l)})"; else -> f += "Unknown Embedded Flash" }
        when (psramCap(l)) {
            0 -> {}
            1 -> f += "Embedded PSRAM 8MB (${psramVendor(l)})"; 2 -> f += "Embedded PSRAM 2MB (${psramVendor(l)})"
            3 -> f += "Embedded PSRAM 16MB (${psramVendor(l)})"; 4 -> f += "Embedded PSRAM 4MB (${psramVendor(l)})"
            else -> f += "Unknown Embedded PSRAM"
        }
        return f
    }

    override fun crystalFreq(l: EspLoader) = 40
    override fun flashVoltage(l: EspLoader) = throw NotSupportedException("Reading flash voltage", chipName)

    override fun postConnect(l: EspLoader) {
        if (!l.secureDownloadMode && !l.syncStubDetected) disableWatchdogs(l)
    }

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)

    override fun hardReset(l: EspLoader) {
        try { l.writeReg(RTC_CNTL_OPTION1_REG, 0, RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK) } catch (_: Exception) {}
        val usesOtg = l.usesUsbOtg()
        if (usesOtg) {
            val strap = l.readReg(GPIO_STRAP_REG)
            val forceDl = l.readReg(RTC_CNTL_OPTION1_REG)
            if (strap and GPIO_STRAP_SPI_BOOT_MASK == 0L && forceDl and RTC_CNTL_FORCE_DOWNLOAD_BOOT_MASK == 0L) {
                watchdogReset(l)
                return
            }
        }
        l.hardResetDefault(usesOtg)
    }
}
