package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader

/** ESP32-C6 layout; also the base for C5, C61, H2 and H21. */
abstract class Esp32C6Like : Esp32C3Like() {
    override val spiRegBase = 0x60003000L
    override val efuseBase: Long = 0x600B0800L
    open val lpWdtBase: Long = 0x600B1C00L
    override val rtcWdtConfig0Reg get() = lpWdtBase + 0x0
    override val rtcWdtConfig1Reg get() = lpWdtBase + 0x0004
    override val rtcWdtWprotectReg get() = lpWdtBase + 0x0018
    override val rtcSwdConfReg get() = lpWdtBase + 0x001C
    override val rtcSwdAutoFeedEn = 1L shl 18
    override val rtcSwdWprotectReg get() = lpWdtBase + 0x0020
    override val flashFrequency: Map<String, Int> = mapOf("80m" to 0x0, "40m" to 0x0, "20m" to 0x2)

    /** 802.15.4 capable parts also expose an EUI64 and MAC extension. */
    override fun readMacs(l: EspLoader): Map<String, ByteArray> {
        val mac1 = l.readReg(macEfuseReg + 4)
        val base = readMac(l)
        val ext = byteArrayOf((mac1 shr 24).toByte(), (mac1 shr 16).toByte())
        val eui64 = base.copyOfRange(0, 3) + ext + base.copyOfRange(3, 6)
        return mapOf("BASE_MAC" to base, "EUI64" to eui64, "MAC_EXT" to ext)
    }
}

object Esp32C6 : Esp32C6Like() {
    override val chipName = "ESP32-C6"
    override val imageChipId = 13
    override val uf2FamilyId = 0x540DDF62L
    override val keyPurposes = KEY_PURPOSES_S2
    override val memoryMap = listOf(
        MemRegion(0x42000000, 0x42800000, "DROM"), MemRegion(0x40800000, 0x40880000, "DRAM"),
        MemRegion(0x40000000, 0x42000000, "IROM_MASK"), MemRegion(0x40800000, 0x40880000, "IRAM"),
        MemRegion(0x42000000, 0x42800000, "IROM"), MemRegion(0x50000000, 0x50004000, "RTC_IRAM"),
    )

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 3) shr 24) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 18) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 22) and 0x03).toInt()
    private fun flashCap(l: EspLoader) = (block1Word(l, 4) and 0x07).toInt()

    override fun chipDescription(l: EspLoader): String {
        val pkg = pkgVersion(l)
        val name = when {
            pkg == 0 -> "ESP32-C6 (QFN40)"
            pkg == 1 && flashCap(l) == 1 -> "ESP32-C6FH4 (QFN32)"
            pkg == 1 && flashCap(l) == 2 -> "ESP32-C6FH8 (QFN32)"
            else -> "Unknown ESP32-C6"
        }
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val flash = when (flashCap(l)) { 1 -> "Embedded Flash 4MB"; 2 -> "Embedded Flash 8MB"; else -> "Unknown Embedded Flash" }
        return listOf("Wi-Fi 6", "BT 5 (LE)", "IEEE802.15.4", "Single Core + LP Core", "160MHz", flash)
    }
}

object Esp32C5 : Esp32C6Like() {
    override val chipName = "ESP32-C5"
    override val imageChipId = 23
    override val watchdogResetSupported = true
    override val flash32BitAddrSupported = true
    override val bootloaderFlashOffset = 0x2000L
    override val efuseBase = 0x600B4800L
    override val spiBootCryptCntMask = 0x7L shl 16
    override val secureBootEnMask = 1L shl 25
    private val EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG = efuseBase + 0x34
    private const val EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT = 10
    private const val PCR_SYSCLK_CONF_REG = 0x60096110L
    override val flashFrequency = FLASH_FREQUENCY_80_40_20
    override val uf2FamilyId = 0xF71C0343L

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 2) shr 26) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader) = (block1Word(l, 2) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 2) shr 4) and 0x03).toInt()
    private fun flashCap(l: EspLoader) = ((block1Word(l, 2) shr 13) and 0x07).toInt()
    private fun flashVendor(l: EspLoader) = if (((block1Word(l, 2) shr 16) and 0x07).toInt() == 1) "XMC" else ""
    private fun psramCap(l: EspLoader) = ((block1Word(l, 2) shr 19) and 0x07).toInt()
    private fun psramVendor(l: EspLoader) = if (((block1Word(l, 2) shr 22) and 0x03).toInt() == 1) "AP_3v3" else ""
    private fun temp(l: EspLoader) = ((block1Word(l, 2) shr 24) and 0x03).toInt()

    override fun chipDescription(l: EspLoader): String {
        var name = "ESP32-C5"
        name += when (temp(l)) { 0 -> "N"; 1 -> "H"; else -> "?" }
        name += when (flashCap(l)) { 0 -> ""; 1 -> "F4"; else -> "F?" }
        name += when (psramCap(l)) { 0 -> ""; 1 -> "R4"; 2 -> "R8"; else -> "R?" }
        if ("?" in name) name = "Unknown $name"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi 6 (dual-band)", "BT 5 (LE)", "IEEE802.15.4", "Single Core + LP Core", "240MHz")
        var flash = when (flashCap(l)) { 0 -> "No Embedded Flash"; 1 -> "Embedded Flash 4MB"; else -> "Unknown Embedded Flash" }
        if (flashCap(l) == 1) flash += " (${flashVendor(l)})"
        f += flash
        var psram = when (psramCap(l)) { 0 -> "No Embedded PSRAM"; 1 -> "Embedded PSRAM 4MB"; 2 -> "Embedded PSRAM 8MB"; else -> "Unknown Embedded PSRAM" }
        if (psramCap(l) in 1..2) psram += " (${psramVendor(l)})"
        f += psram
        return f
    }

    override fun crystalFreq(l: EspLoader) = l.estimateCrystalFreq()
    private fun crystalFreqRomExpect(l: EspLoader) = ((l.readReg(PCR_SYSCLK_CONF_REG) and (0x7FL shl 24)) shr 24).toInt()

    override fun hardReset(l: EspLoader) = l.hardResetDefault(l.usesUsbJtagSerial())

    override fun changeBaud(l: EspLoader, baud: Int) {
        if (l.secureDownloadMode || l.isStub) { l.changeBaudDefault(baud); return }
        val romExpect = crystalFreqRomExpect(l)
        val detect = crystalFreq(l)
        l.log.info("ROM expects crystal freq: $romExpect MHz, detected $detect MHz.")
        val rate = when {
            detect == 48 && romExpect == 40 -> baud.toLong() * 40 / 48
            detect == 40 && romExpect == 48 -> baud.toLong() * 48 / 40
            else -> { l.changeBaudDefault(baud); return }
        }
        l.changeBaudRaw(baud, rate, 0)
    }

    override fun usesKeyManagerForFlashEncryption(l: EspLoader) =
        ((l.readReg(EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG) shr EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT) and 2L) != 0L

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)
    override fun readMacs(l: EspLoader) = super.readMacs(l) // C5 has 802.15.4
}

object Esp32C61 : Esp32C6Like() {
    override val chipName = "ESP32-C61"
    override val imageChipId = 20
    override val watchdogResetSupported = true
    override val flash32BitAddrSupported = true
    override val efuseBase = 0x600B4800L
    override val disDownloadManualEncryptMask = 1L shl 14
    override val spiBootCryptCntReg get() = efuseBase + 0x030
    override val spiBootCryptCntMask = 0x7L shl 23
    override val secureBootEnReg get() = efuseBase + 0x034
    override val secureBootEnMask = 1L shl 26
    override val flashFrequency = FLASH_FREQUENCY_80_40_20
    override val uf2FamilyId = 0x77D850C4L

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 2) shr 26) and 0x07).toInt()
    private fun flashCap(l: EspLoader) = ((block1Word(l, 2) shr 13) and 0x07).toInt()
    private fun psramCap(l: EspLoader) = ((block1Word(l, 2) shr 19) and 0x07).toInt()
    private fun temp(l: EspLoader) = ((block1Word(l, 2) shr 24) and 0x03).toInt()
    override fun minorChipVersion(l: EspLoader) = (block1Word(l, 2) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 2) shr 4) and 0x03).toInt()

    override fun chipDescription(l: EspLoader): String {
        var name = "ESP32-C61"
        name += when (temp(l)) { 0 -> "N"; 1 -> "H"; else -> "?" }
        name += when (flashCap(l)) { 0 -> ""; 1 -> "F4"; 2 -> "F8"; else -> "F?" }
        name += when (psramCap(l)) { 0 -> ""; 1 -> "R2"; 2 -> "R8"; else -> "R?" }
        name += when (pkgVersion(l)) { 0, 1, 2 -> ""; 3 -> "LA"; else -> "?" }
        if ("?" in name) name = "Unknown $name"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi 6", "BT 5 (LE)", "Single Core", "160MHz")
        f += when (flashCap(l)) { 0 -> "No Embedded Flash"; 1 -> "Embedded Flash 4MB"; 2 -> "Embedded Flash 8MB"; else -> "Unknown Embedded Flash" }
        f += when (psramCap(l)) { 0 -> "No Embedded PSRAM"; 1 -> "Embedded PSRAM 2MB"; 2 -> "Embedded PSRAM 8MB"; else -> "Unknown Embedded PSRAM" }
        return f
    }

    override fun readMacs(l: EspLoader) = mapOf("BASE_MAC" to readMac(l))
    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)
}

open class Esp32H2Base : Esp32C6Like() {
    override val chipName = "ESP32-H2"
    override val imageChipId = 16
    override val rtcWdtWprotectReg get() = lpWdtBase + 0x001C
    override val rtcSwdConfReg get() = lpWdtBase + 0x0020
    override val rtcSwdWprotectReg get() = lpWdtBase + 0x0024
    override val flashFrequency: Map<String, Int> = mapOf("48m" to 0xF, "24m" to 0x0, "16m" to 0x1, "12m" to 0x2)
    override val uf2FamilyId = 0x332726F6L

    fun pkgVersion(l: EspLoader) = (block1Word(l, 4) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 18) and 0x07).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 21) and 0x03).toInt()
    override fun chipDescription(l: EspLoader): String {
        val name = if (pkgVersion(l) == 0) "ESP32-H2" else "Unknown ESP32-H2"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }
    override fun chipFeatures(l: EspLoader) = listOf("BT 5 (LE)", "IEEE802.15.4", "Single Core", "96MHz")
    override fun crystalFreq(l: EspLoader) = 32
}

object Esp32H2 : Esp32H2Base()

object Esp32H21 : Esp32H2Base() {
    override val chipName = "ESP32-H21"
    override val imageChipId = 25
    override val uartDateRegAddr = 0x60000000L + 0x8C
    override val flashFrequency = mapOf("48m" to 0xF, "24m" to 0x0)
    override val uf2FamilyId = 0L

    private fun flashCap(l: EspLoader) = ((block1Word(l, 3) shr 24) and 0x07).toInt()
    private fun flashVendor(l: EspLoader) = when (((block1Word(l, 3) shr 27) and 0x07).toInt()) { 1 -> "FM"; 2 -> "XMC"; else -> "" }
    private fun temp(l: EspLoader) = ((block1Word(l, 3) shr 30) and 0x03).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block1Word(l, 5) shr 4) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 5) shr 8) and 0x03).toInt()

    override fun chipDescription(l: EspLoader): String {
        var name = "ESP32-H21"
        name += when (temp(l)) { 0 -> "N"; 1 -> "H"; else -> "?" }
        name += when (flashCap(l)) { 0 -> ""; 1 -> "F4"; 2 -> "F2"; else -> "F?" }
        if ("?" in name) name = "Unknown $name"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("BT 5 (LE)", "IEEE802.15.4", "Single Core", "96MHz")
        val flash = when (flashCap(l)) { 0 -> null; 1 -> "Embedded Flash 4MB"; 2 -> "Embedded Flash 2MB"; else -> "Unknown Embedded Flash" }
        if (flash != null) f += "$flash (${flashVendor(l)})"
        return f
    }
}
