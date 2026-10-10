package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader

object Esp32H4 : Esp32C3Like() {
    override val chipName = "ESP32-H4"
    override val imageChipId = 28
    override val flash32BitAddrSupported = true
    override val bootloaderFlashOffset = 0x2000L
    override val spiRegBase = 0x60099000L
    override val uartClkdivReg = 0x60012000L + 0x14
    override val uartDateRegAddr = 0x60012000L + 0x8C
    override val efuseBase = 0x600B1800L
    override val disDownloadManualEncryptMask = 1L shl 14
    override val spiBootCryptCntReg get() = efuseBase + 0x030
    override val spiBootCryptCntMask = 0x7L shl 23
    override val secureBootEnMask = 1L shl 5
    private val EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG = efuseBase + 0x038
    private const val EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT = 19
    private const val DR_REG_TIMG_BASE = 0x60090000L
    override val rtcWdtConfig0Reg = DR_REG_TIMG_BASE + 0x48
    override val rtcWdtConfig1Reg = DR_REG_TIMG_BASE + 0x4C
    override val rtcWdtWprotectReg = DR_REG_TIMG_BASE + 0x64
    private const val DR_REG_LP_WDT_BASE = 0x600B5400L
    override val rtcSwdConfReg = DR_REG_LP_WDT_BASE + 0x0020
    override val rtcSwdAutoFeedEn = 1L shl 18
    override val rtcSwdWprotectReg = DR_REG_LP_WDT_BASE + 0x0024
    override val flashFrequency = mapOf("48m" to 0xF, "24m" to 0x0)

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 4) shr 12) and 0x07).toInt()
    private fun flashCap(l: EspLoader): Int {
        val w3 = block1Word(l, 3); val w4 = block1Word(l, 4)
        return (((w3 shr 31) or ((w4 and 0x03) shl 1)) and 0x07).toInt()
    }
    private fun flashVendor(l: EspLoader) = when (((block1Word(l, 4) shr 2) and 0x07).toInt()) { 1 -> "FM"; 2 -> "XMC"; 3 -> "PY"; else -> "" }
    private fun psramCap(l: EspLoader) = ((block1Word(l, 4) shr 5) and 0x07).toInt()
    private fun psramVendor(l: EspLoader) = if (((block1Word(l, 4) shr 8) and 0x03).toInt() == 1) "AP" else ""
    private fun temp(l: EspLoader) = ((block1Word(l, 4) shr 10) and 0x03).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 18) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 22) and 0x03).toInt()

    override fun chipDescription(l: EspLoader): String {
        var name = "ESP32-H4"
        name += if (temp(l) == 1) "H" else "?"
        var flash = when (flashCap(l)) { 0 -> ""; 1 -> "F4"; else -> "F?" }
        if (flash == "F4" && flashVendor(l) == "PY") flash = "FL4"
        name += flash
        name += when (psramCap(l)) { 0 -> ""; 1 -> "R8"; 2 -> "R2"; else -> "R?" }
        if ("?" in name) name = "Unknown $name"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("BT 5 (LE)", "IEEE802.15.4", "Dual Core", "96MHz")
        var flash = when (flashCap(l)) { 0 -> "No Embedded Flash"; 1 -> "Embedded Flash 4MB"; else -> "Unknown Embedded Flash" }
        if (flashCap(l) == 1) flash += " (${flashVendor(l)})"
        f += flash
        var psram = when (psramCap(l)) { 0 -> "No Embedded PSRAM"; 1 -> "Embedded PSRAM 8MB"; 2 -> "Embedded PSRAM 2MB"; else -> "Unknown Embedded PSRAM" }
        if (psramCap(l) in 1..2) psram += " (${psramVendor(l)})"
        f += psram
        return f
    }

    override fun crystalFreq(l: EspLoader) = 32

    override fun readMacs(l: EspLoader): Map<String, ByteArray> {
        val mac1 = l.readReg(macEfuseReg + 4)
        val base = readMac(l)
        val ext = byteArrayOf((mac1 shr 24).toByte(), (mac1 shr 16).toByte())
        val eui64 = base.copyOfRange(0, 3) + ext + base.copyOfRange(3, 6)
        return mapOf("BASE_MAC" to base, "EUI64" to eui64, "MAC_EXT" to ext)
    }

    override fun usesKeyManagerForFlashEncryption(l: EspLoader) =
        ((l.readReg(EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG) shr EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT) and 2L) != 0L
}
