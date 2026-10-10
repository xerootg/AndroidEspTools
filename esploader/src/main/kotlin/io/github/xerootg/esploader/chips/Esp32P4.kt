package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.NotSupportedException

object Esp32P4 : Esp32Family() {
    override val chipName = "ESP32-P4"
    override val imageChipId = 18
    override val usbOtgSupported = true
    override val usbSerialJtagSupported = true
    override val watchdogResetSupported = true
    override val securityInfoSupported = true
    override val flash32BitAddrSupported = true

    /** First 2 sectors are reserved for flash-encryption purposes. */
    override val bootloaderFlashOffset = 0x2000L
    override val uartDateRegAddr = 0x500CA000L + 0x8C
    override val efuseBase = 0x5012D000L
    override val spiRegBase = 0x5008D000L // SPIMEM1
    override val uartClkdivReg = 0x3FF40014L // unused: crystal is fixed at 40MHz
    private val EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG = efuseBase + 0x34
    private const val EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT = 9
    private val EFUSE_RD_REPEAT_DATA1_REG = efuseBase + 0x034
    private const val EFUSE_DOWNLOAD_MODE_XPD_ON_MASK = 0x1L shl 16

    private const val DR_REG_LPAON_BASE = 0x50110000L
    private const val DR_REG_PMU_BASE = DR_REG_LPAON_BASE + 0x5000
    private const val LP_SYSTEM_REG_ANA_XPD_PAD_GROUP_REG = DR_REG_LPAON_BASE + 0x10C
    private const val PMU_EXT_LDO_P0_0P1A_ANA_REG = DR_REG_PMU_BASE + 0x1BC
    private const val PMU_ANA_0P1A_EN_CUR_LIM_0 = 1L shl 27
    private const val PMU_EXT_LDO_P0_0P1A_REG = DR_REG_PMU_BASE + 0x1B8
    private const val PMU_0P1A_TARGET0_0 = 0xFFL shl 23
    private const val PMU_0P1A_FORCE_TIEH_SEL_0 = 1L shl 7
    private const val PMU_DATE_REG = DR_REG_PMU_BASE + 0x3FC
    private const val PMU_DATE_FLASH_FORCE_ON = 0x3L

    private const val DR_REG_LP_WDT_BASE = 0x50116000L
    override val rtcWdtConfig0Reg = DR_REG_LP_WDT_BASE + 0x0
    override val rtcWdtConfig1Reg = DR_REG_LP_WDT_BASE + 0x0004
    override val rtcWdtWprotectReg = DR_REG_LP_WDT_BASE + 0x0018
    override val rtcSwdConfReg = DR_REG_LP_WDT_BASE + 0x001C
    override val rtcSwdAutoFeedEn = 1L shl 18
    override val rtcSwdWprotectReg = DR_REG_LP_WDT_BASE + 0x0020

    override val flashSizes = STANDARD_FLASH_SIZES
    override val flashFrequency = STANDARD_FLASH_FREQUENCY
    override val uf2FamilyId = 0x3D308E94L
    override val keyPurposes = mapOf(
        0 to "USER/EMPTY", 1 to "ECDSA_KEY", 2 to "XTS_AES_256_KEY_1", 3 to "XTS_AES_256_KEY_2",
        4 to "XTS_AES_128_KEY", 5 to "HMAC_DOWN_ALL", 6 to "HMAC_DOWN_JTAG", 7 to "HMAC_DOWN_DIGITAL_SIGNATURE",
        8 to "HMAC_UP", 9 to "SECURE_BOOT_DIGEST0", 10 to "SECURE_BOOT_DIGEST1", 11 to "SECURE_BOOT_DIGEST2", 12 to "KM_INIT_KEY",
    )
    override val memoryMap = listOf(
        MemRegion(0x40000000, 0x4C000000, "DROM"), MemRegion(0x4FF00000, 0x4FFA0000, "DRAM"),
        MemRegion(0x4FC00000, 0x4FC20000, "IROM_MASK"), MemRegion(0x40000000, 0x4C000000, "IROM"),
        MemRegion(0x4FF00000, 0x4FFA0000, "IRAM"), MemRegion(0x50108000, 0x50110000, "RTC_IRAM"),
        MemRegion(0x600FE000, 0x60100000, "MEM_INTERNAL2"),
    )

    override fun stubJsonName(loader: EspLoader) = if (chipRevision(loader) < 300) "esp32p4-rev1.json" else "esp32p4.json"

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 2) shr 20) and 0x07).toInt()
    override fun minorChipVersion(l: EspLoader) = (block1Word(l, 2) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader): Int {
        val w = block1Word(l, 2)
        return ((((w shr 23) and 1) shl 2) or ((w shr 4) and 0x03)).toInt()
    }

    override fun chipDescription(l: EspLoader): String {
        val name = if (pkgVersion(l) == 0) "ESP32-P4" else "Unknown ESP32-P4"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }

    override fun chipFeatures(l: EspLoader) = listOf("Dual Core + LP Core", "400MHz")
    override fun crystalFreq(l: EspLoader) = 40
    override fun flashVoltage(l: EspLoader) = throw NotSupportedException("Reading flash voltage", chipName)

    override fun usesKeyManagerForFlashEncryption(l: EspLoader) =
        ((l.readReg(EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG) shr EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT) and 2L) != 0L

    override fun postConnect(l: EspLoader) {
        if (l.secureDownloadMode) return
        if (!l.syncStubDetected) disableWatchdogs(l)
        powerOnFlash(l) // needs to be powered on before attaching flash
    }

    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)

    override fun hardReset(l: EspLoader) {
        if (l.usesUsbOtg()) watchdogReset(l) else l.hardResetDefault(false)
    }

    /**
     * Power on the flash chip. ECO6/ECO7 silicon defaults the flash supply off after the
     * 1.8 V → 3.3 V default change; other revisions do not need this sequence.
     */
    private fun powerOnFlash(l: EspLoader) {
        val revision = chipRevision(l)
        if (revision != 301 && revision != 302) return
        if (revision == 302 && (l.readReg(EFUSE_RD_REPEAT_DATA1_REG) and EFUSE_DOWNLOAD_MODE_XPD_ON_MASK) != 0L) {
            // ECO7 ROM already asserts flash XPD in download mode; release the "force on" bits
            // left over from power-up so a second download session can attach the flash.
            val date = l.readReg(PMU_DATE_REG)
            if (date and PMU_DATE_FLASH_FORCE_ON == PMU_DATE_FLASH_FORCE_ON) {
                l.writeReg(PMU_DATE_REG, date and PMU_DATE_FLASH_FORCE_ON.inv())
            }
            return
        }
        l.writeReg(LP_SYSTEM_REG_ANA_XPD_PAD_GROUP_REG, 1)
        l.sleep(10)
        l.writeReg(PMU_EXT_LDO_P0_0P1A_ANA_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_ANA_REG) or PMU_ANA_0P1A_EN_CUR_LIM_0)
        l.writeReg(PMU_EXT_LDO_P0_0P1A_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_REG) or PMU_0P1A_FORCE_TIEH_SEL_0)
        l.writeReg(PMU_DATE_REG, l.readReg(PMU_DATE_REG) or PMU_DATE_FLASH_FORCE_ON)
        l.sleep(1)
        l.writeReg(PMU_EXT_LDO_P0_0P1A_ANA_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_ANA_REG) and PMU_ANA_0P1A_EN_CUR_LIM_0.inv())
        l.writeReg(PMU_EXT_LDO_P0_0P1A_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_REG) and PMU_0P1A_TARGET0_0.inv())
        l.writeReg(PMU_EXT_LDO_P0_0P1A_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_REG) or 0x80)
        l.writeReg(PMU_EXT_LDO_P0_0P1A_REG, l.readReg(PMU_EXT_LDO_P0_0P1A_REG) and PMU_0P1A_FORCE_TIEH_SEL_0.inv())
        l.sleep(2)
    }
}

object Esp32S31 : Esp32C6Like() {
    override val chipName = "ESP32-S31"
    override val imageChipId = 32
    override val usbOtgSupported = true
    override val watchdogResetSupported = true
    override val flash32BitAddrSupported = true
    override val bootloaderFlashOffset = 0x2000L
    override val uartDateRegAddr = 0x2038A000L + 0x8C
    override val efuseBase = 0x20715000L
    override val efuseBlock1Addr get() = efuseBase + 0x050
    override val macEfuseReg get() = efuseBase + 0x050
    override val spiRegBase = 0x20501000L
    override val lpWdtBase = 0x20801000L
    override val spiBootCryptCntMask = 0x7L shl 21
    override val secureBootEnReg get() = efuseBase + 0x03C
    override val secureBootEnMask = 1L shl 2
    private val EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG = efuseBase + 0x034
    private const val EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT = 12
    override val flashFrequency = FLASH_FREQUENCY_80_40_20

    fun pkgVersion(l: EspLoader) = ((block1Word(l, 4) shr 6) and 0x03).toInt()
    override fun minorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 18) and 0x0F).toInt()
    override fun majorChipVersion(l: EspLoader) = ((block1Word(l, 3) shr 22) and 0x03).toInt()
    override fun chipDescription(l: EspLoader): String {
        val name = if (pkgVersion(l) == 0) "ESP32-S31" else "Unknown ESP32-S31"
        return "$name (revision v${majorChipVersion(l)}.${minorChipVersion(l)})"
    }
    override fun chipFeatures(l: EspLoader) = listOf("Wi-Fi 6", "BT 5.4 (LE)", "IEEE802.15.4", "Dual Core + LP Core", "300MHz")
    override fun crystalFreq(l: EspLoader) = 40
    override fun readMacs(l: EspLoader) = mapOf("BASE_MAC" to readMac(l))
    override fun usesKeyManagerForFlashEncryption(l: EspLoader) =
        ((l.readReg(EFUSE_FORCE_USE_KEY_MANAGER_KEY_REG) shr EFUSE_FORCE_USE_KEY_MANAGER_KEY_SHIFT) and 2L) != 0L
    override fun watchdogReset(l: EspLoader) = rtcWatchdogReset(l)
    override fun hardReset(l: EspLoader) {
        if (!l.secureDownloadMode && l.usesUsbOtg()) watchdogReset(l) else l.hardResetDefault(false)
    }
}
