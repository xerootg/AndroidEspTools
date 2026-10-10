package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.NotImplementedInRomException
import io.github.xerootg.esploader.NotSupportedException

data class MemRegion(val start: Long, val end: Long, val name: String)

/**
 * Everything the loader needs to know about one Espressif SoC: register addresses, feature
 * flags and the handful of behaviours that differ between chips. Instances are stateless;
 * all device access goes through the [EspLoader] passed to each method.
 */
abstract class ChipDefinition {
    abstract val chipName: String
    /** Chip id as stored in image headers / returned by GET_SECURITY_INFO. -1 for ESP8266. */
    abstract val imageChipId: Int
    /** Chip is identified through the CHIP_DETECT_MAGIC register (ESP8266, ESP32, ESP32-S2). */
    open val usesMagicValue: Boolean = false
    open val magicValue: Long = 0

    open val usbOtgSupported = false
    open val usbSerialJtagSupported = false
    open val securityInfoSupported = false
    open val watchdogResetSupported = false
    open val flash32BitAddrSupported = false
    open val stubSupported = true

    open val bootloaderFlashOffset: Long = 0
    open val uartDateRegAddr: Long = 0x60000078
    abstract val spiRegBase: Long
    open val spiUsrOffs: Long = 0x18
    open val spiUsr1Offs: Long = 0x1C
    open val spiUsr2Offs: Long = 0x20
    open val spiMosiDlenOffs: Long? = 0x24
    open val spiMisoDlenOffs: Long? = 0x28
    open val spiW0Offs: Long = 0x58
    open val spiAddrRegMsb: Boolean = false
    abstract val uartClkdivReg: Long
    open val uartClkdivMask: Long = 0xFFFFF
    open val xtalClkDivider: Int = 1
    open val flashEncryptedWriteAlign = 16
    open val memoryMap: List<MemRegion> = emptyList()
    open val keyPurposes: Map<Int, String> = emptyMap()
    open val uf2FamilyId: Long = 0

    abstract val flashSizes: Map<String, Int>
    abstract val flashFrequency: Map<String, Int>

    /** ROM loader quirks (the stub supports everything). */
    open val romSupportsCompressedFlash = true
    open val romSupportsMd5 = true
    open val romSupportsReadFlashSlow = true
    /** FLASH_BEGIN / FLASH_DEFL_BEGIN take a fifth "encrypted" word. */
    open val romSupportsExtendedFlashParams = true

    open fun stubJsonName(loader: EspLoader): String =
        chipName.lowercase().replace("-", "").replace("(", "").replace(")", "") + ".json"

    abstract fun chipDescription(l: EspLoader): String
    abstract fun chipFeatures(l: EspLoader): List<String>
    abstract fun readMac(l: EspLoader): ByteArray
    /** All MAC flavours the chip exposes (BASE_MAC always; EUI64 / MAC_EXT on 802.15.4 parts). */
    open fun readMacs(l: EspLoader): Map<String, ByteArray> = mapOf("BASE_MAC" to readMac(l))

    open fun crystalFreq(l: EspLoader): Int = l.estimateCrystalFreq()
    open fun majorChipVersion(l: EspLoader): Int = throw NotImplementedInRomException("Reading chip revision", chipName)
    open fun minorChipVersion(l: EspLoader): Int = throw NotImplementedInRomException("Reading chip revision", chipName)
    fun chipRevision(l: EspLoader): Int = majorChipVersion(l) * 100 + minorChipVersion(l)

    open fun changeBaud(l: EspLoader, baud: Int) = l.changeBaudDefault(baud)
    /** Hook run right after a successful connect + detection. */
    open fun postConnect(l: EspLoader) {}
    open fun hardReset(l: EspLoader) = l.hardResetDefault(usesUsb = false)
    open fun watchdogReset(l: EspLoader) {
        l.log.note("Watchdog hard reset is not supported on $chipName, attempting classic hard reset instead.")
        hardReset(l)
    }

    open fun eraseSize(l: EspLoader, offset: Long, size: Long): Long = size
    open fun flashSpiAttach(l: EspLoader, hspiArg: Long) = l.flashSpiAttachDefault(hspiArg)
    open fun flashSetParameters(l: EspLoader, size: Long) = l.flashSetParametersDefault(size)

    open fun secureBootEnabled(l: EspLoader): Boolean = false
    open fun secureBootV1Enabled(l: EspLoader): Boolean = false
    open fun flashEncryptionEnabled(l: EspLoader): Boolean = false
    open fun encryptedDownloadDisabled(l: EspLoader): Boolean = false
    open fun usesKeyManagerForFlashEncryption(l: EspLoader): Boolean = false
    open fun flashVoltage(l: EspLoader): String = throw NotSupportedException("Reading flash voltage", chipName)
    /** eFuse-programmed SPI pad configuration (ESP32 only): clk, q, d, hd, cs. */
    open fun chipSpiPads(l: EspLoader): IntArray? = null

    override fun toString() = chipName
}

/** Shared plumbing for ESP32 and every later chip (eFuse-based MAC, revision words, RTC watchdog). */
abstract class Esp32Family : ChipDefinition() {
    abstract val efuseBase: Long
    open val efuseBlock1Addr: Long get() = efuseBase + 0x044
    open val macEfuseReg: Long get() = efuseBase + 0x044
    /** BLOCK0 read base. */
    open val efuseRdRegBase: Long get() = efuseBase + 0x030

    open val spiBootCryptCntReg: Long get() = efuseBase + 0x034
    open val spiBootCryptCntMask: Long = 0x7L shl 18
    open val secureBootEnReg: Long get() = efuseBase + 0x038
    open val secureBootEnMask: Long = 1L shl 20
    open val disDownloadManualEncryptReg: Long get() = efuseRdRegBase
    open val disDownloadManualEncryptMask: Long = 1L shl 20

    // RTC / LP watchdog
    open val rtcWdtConfig0Reg: Long = 0
    open val rtcWdtConfig1Reg: Long = 0
    open val rtcWdtWprotectReg: Long = 0
    open val rtcWdtWkey: Long = 0x50D83AA1
    open val rtcSwdConfReg: Long = 0
    open val rtcSwdAutoFeedEn: Long = 0
    open val rtcSwdWprotectReg: Long = 0
    open val rtcSwdWkey: Long = 0x50D83AA1

    protected fun block1Word(l: EspLoader, n: Int): Long = l.readReg(efuseBlock1Addr + 4L * n)

    override fun readMac(l: EspLoader): ByteArray {
        val mac0 = l.readReg(macEfuseReg)
        val mac1 = l.readReg(macEfuseReg + 4) // only bottom 16 bits are MAC
        return byteArrayOf(
            (mac1 shr 8).toByte(), mac1.toByte(),
            (mac0 shr 24).toByte(), (mac0 shr 16).toByte(), (mac0 shr 8).toByte(), mac0.toByte(),
        )
    }

    override fun secureBootEnabled(l: EspLoader) = l.readReg(secureBootEnReg) and secureBootEnMask != 0L
    override fun flashEncryptionEnabled(l: EspLoader): Boolean {
        val cnt = l.readReg(spiBootCryptCntReg) and spiBootCryptCntMask
        return java.lang.Long.bitCount(cnt) and 1 != 0
    }
    override fun encryptedDownloadDisabled(l: EspLoader) =
        l.readReg(disDownloadManualEncryptReg) and disDownloadManualEncryptMask != 0L

    /** Disable RTC WDT and auto-feed the super watchdog: both keep running when USB-Serial/JTAG is used. */
    open fun disableWatchdogs(l: EspLoader) {
        if (!l.usesUsbJtagSerial()) return
        if (rtcWdtWprotectReg != 0L) {
            l.writeReg(rtcWdtWprotectReg, rtcWdtWkey)
            l.writeReg(rtcWdtConfig0Reg, 0)
            l.writeReg(rtcWdtWprotectReg, 0)
        }
        if (rtcSwdWprotectReg != 0L) {
            l.writeReg(rtcSwdWprotectReg, rtcSwdWkey)
            l.writeReg(rtcSwdConfReg, l.readReg(rtcSwdConfReg) or rtcSwdAutoFeedEn)
            l.writeReg(rtcSwdWprotectReg, 0)
        }
    }

    protected fun rtcWatchdogReset(l: EspLoader) {
        l.log.info("Hard resetting with a watchdog...")
        l.writeReg(rtcWdtWprotectReg, rtcWdtWkey)
        l.writeReg(rtcWdtConfig1Reg, 2000)
        l.writeReg(rtcWdtConfig0Reg, (1L shl 31) or (5L shl 28) or (1L shl 8) or 2L)
        l.writeReg(rtcWdtWprotectReg, 0)
        l.sleep(500)
    }
}

internal val STANDARD_FLASH_SIZES = mapOf(
    "1MB" to 0x00, "2MB" to 0x10, "4MB" to 0x20, "8MB" to 0x30,
    "16MB" to 0x40, "32MB" to 0x50, "64MB" to 0x60, "128MB" to 0x70,
)
internal val STANDARD_FLASH_FREQUENCY = mapOf("80m" to 0xF, "40m" to 0x0, "26m" to 0x1, "20m" to 0x2)
internal val FLASH_FREQUENCY_80_40_20 = mapOf("80m" to 0xF, "40m" to 0x0, "20m" to 0x2)

internal val KEY_PURPOSES_S2 = mapOf(
    0 to "USER/EMPTY", 1 to "RESERVED", 2 to "XTS_AES_256_KEY_1", 3 to "XTS_AES_256_KEY_2",
    4 to "XTS_AES_128_KEY", 5 to "HMAC_DOWN_ALL", 6 to "HMAC_DOWN_JTAG", 7 to "HMAC_DOWN_DIGITAL_SIGNATURE",
    8 to "HMAC_UP", 9 to "SECURE_BOOT_DIGEST0", 10 to "SECURE_BOOT_DIGEST1", 11 to "SECURE_BOOT_DIGEST2",
)
