package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspLoader

object Esp32 : Esp32Family() {
    override val chipName = "ESP32"
    override val imageChipId = 0
    override val usesMagicValue = true
    override val magicValue = 0x00F01D83L

    override val spiRegBase = 0x3FF42000L
    override val spiUsrOffs = 0x1CL
    override val spiUsr1Offs = 0x20L
    override val spiUsr2Offs = 0x24L
    override val spiMosiDlenOffs: Long? = 0x28L
    override val spiMisoDlenOffs: Long? = 0x2CL
    override val spiW0Offs = 0x80L
    override val spiAddrRegMsb = true
    override val uartClkdivReg = 0x3FF40014L

    override val efuseBase = 0x3FF5A000L
    override val efuseRdRegBase = 0x3FF5A000L
    private val EFUSE_BLK0_RDATA3_REG = efuseRdRegBase + 0x00C
    private val EFUSE_BLK0_RDATA5_REG = efuseRdRegBase + 0x014
    override val disDownloadManualEncryptReg = efuseRdRegBase + 0x18
    override val disDownloadManualEncryptMask = 1L shl 7
    override val spiBootCryptCntReg = efuseRdRegBase
    override val spiBootCryptCntMask = 0x7FL shl 20
    private val EFUSE_RD_ABS_DONE_REG = efuseRdRegBase + 0x018
    private const val EFUSE_RD_ABS_DONE_0_MASK = 1L shl 4
    private const val EFUSE_RD_ABS_DONE_1_MASK = 1L shl 5
    private val EFUSE_VDD_SPI_REG = efuseRdRegBase + 0x10
    private const val VDD_SPI_XPD = 1L shl 14
    private const val VDD_SPI_TIEH = 1L shl 15
    private const val VDD_SPI_FORCE = 1L shl 16
    private const val APB_CTL_DATE_ADDR = 0x3FF66000L + 0x7C
    private const val RTCCALICFG1 = 0x3FF5F06CL
    private const val GPIO_STRAP_REG = 0x3FF44038L
    private const val GPIO_STRAP_VDDSPI_MASK = 1L shl 5
    private const val RTC_CNTL_SDIO_CONF_REG = 0x3FF48074L
    private const val RTC_CNTL_XPD_SDIO_REG = 1L shl 31
    private const val RTC_CNTL_DREFH_SDIO_M = 3L shl 29
    private const val RTC_CNTL_SDIO_FORCE = 1L shl 22

    override val flashSizes = STANDARD_FLASH_SIZES
    override val flashFrequency = STANDARD_FLASH_FREQUENCY
    override val bootloaderFlashOffset = 0x1000L
    override val flashEncryptedWriteAlign = 32
    override val uf2FamilyId = 0x1C5F21B0L
    override val romSupportsExtendedFlashParams = false
    override val memoryMap = listOf(
        MemRegion(0x3F400000, 0x3F800000, "DROM"), MemRegion(0x3F800000, 0x3FC00000, "EXTRAM_DATA"),
        MemRegion(0x3FF80000, 0x3FF82000, "RTC_DRAM"), MemRegion(0x3FFAE000, 0x40000000, "DRAM"),
        MemRegion(0x40080000, 0x400A0000, "IRAM"), MemRegion(0x400C0000, 0x400C2000, "RTC_IRAM"),
        MemRegion(0x400D0000, 0x40400000, "IROM"), MemRegion(0x50000000, 0x50002000, "RTC_DATA"),
    )

    fun readEfuse(l: EspLoader, n: Int): Long = l.readReg(efuseRdRegBase + 4L * n)

    fun pkgVersion(l: EspLoader): Int {
        val word3 = readEfuse(l, 3)
        var pkg = ((word3 shr 9) and 0x07).toInt()
        pkg += (((word3 shr 2) and 0x1) shl 3).toInt()
        return pkg
    }

    override fun minorChipVersion(l: EspLoader) = ((readEfuse(l, 5) shr 24) and 0x3).toInt()
    override fun majorChipVersion(l: EspLoader): Int {
        val revBit0 = (readEfuse(l, 3) shr 15) and 0x1
        val revBit1 = (readEfuse(l, 5) shr 20) and 0x1
        val revBit2 = (l.readReg(APB_CTL_DATE_ADDR) shr 31) and 0x1
        return when (((revBit2 shl 2) or (revBit1 shl 1) or revBit0).toInt()) {
            0 -> 0; 1 -> 1; 3 -> 2; 7 -> 3; else -> 0
        }
    }

    override fun chipDescription(l: EspLoader): String {
        val pkg = pkgVersion(l)
        val major = majorChipVersion(l); val minor = minorChipVersion(l)
        val rev3 = major == 3
        val sc = readEfuse(l, 3) and 1L != 0L
        val name = when (pkg) {
            0 -> if (sc) "ESP32-S0WDQ6" else if (rev3) "ESP32-D0WDQ6-V3" else "ESP32-D0WDQ6"
            1 -> if (sc) "ESP32-S0WD" else if (rev3) "ESP32-D0WD-V3" else "ESP32-D0WD"
            2 -> "ESP32-D2WD"
            4 -> "ESP32-U4WDH"
            5 -> if (rev3) "ESP32-PICO-V3" else "ESP32-PICO-D4"
            6 -> "ESP32-PICO-V3-02"
            7 -> "ESP32-D0WDR2-V3"
            else -> "Unknown ESP32"
        }
        return "$name (revision v$major.$minor)"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi")
        val word3 = readEfuse(l, 3)
        if (word3 and (1L shl 1) == 0L) f += "BT"
        f += if (word3 and 1L != 0L) "Single Core + LP Core" else "Dual Core + LP Core"
        if (word3 and (1L shl 13) != 0L) f += if (word3 and (1L shl 12) != 0L) "160MHz" else "240MHz"
        val pkg = pkgVersion(l)
        if (pkg in listOf(2, 4, 5, 6)) f += "Embedded Flash"
        if (pkg == 6) f += "Embedded PSRAM"
        if ((readEfuse(l, 4) shr 8) and 0x1F != 0L) f += "Vref calibration in eFuse"
        if ((word3 shr 14) and 0x1 != 0L) f += "BLK3 partially reserved"
        val scheme = (readEfuse(l, 6) and 0x3).toInt()
        f += "Coding Scheme " + when (scheme) {
            0 -> "None"; 1 -> "3/4"; 2 -> "Repeat (UNSUPPORTED)"; else -> "None (may contain encoding data)"
        }
        return f
    }

    override fun chipSpiPads(l: EspLoader): IntArray {
        val r5 = l.readReg(EFUSE_BLK0_RDATA5_REG)
        val clk = (r5 and 0x1F).toInt()
        val q = ((r5 shr 5) and 0x1F).toInt()
        val d = ((r5 shr 10) and 0x1F).toInt()
        val cs = ((r5 shr 15) and 0x1F).toInt()
        val r3 = l.readReg(EFUSE_BLK0_RDATA3_REG)
        val hd = ((r3 shr 4) and 0x1F).toInt()
        return intArrayOf(clk, q, d, hd, cs)
    }

    override fun readMac(l: EspLoader): ByteArray {
        val w2 = readEfuse(l, 2); val w1 = readEfuse(l, 1)
        // big endian w2,w1 then trim the 2 byte CRC
        return byteArrayOf(
            (w2 shr 8).toByte(), w2.toByte(),
            (w1 shr 24).toByte(), (w1 shr 16).toByte(), (w1 shr 8).toByte(), w1.toByte(),
        )
    }

    override fun secureBootEnabled(l: EspLoader): Boolean {
        val efuses = l.readReg(EFUSE_RD_ABS_DONE_REG)
        val rev = chipRevision(l)
        return (efuses and EFUSE_RD_ABS_DONE_0_MASK != 0L) || (rev >= 300 && efuses and EFUSE_RD_ABS_DONE_1_MASK != 0L)
    }

    override fun secureBootV1Enabled(l: EspLoader) = l.readReg(EFUSE_RD_ABS_DONE_REG) and EFUSE_RD_ABS_DONE_0_MASK != 0L

    override fun flashVoltage(l: EspLoader): String {
        val reg = l.readReg(RTC_CNTL_SDIO_CONF_REG)
        if (reg and RTC_CNTL_SDIO_FORCE != 0L) {
            val v = if (reg and RTC_CNTL_DREFH_SDIO_M != 0L) "1.9V" else if (reg and RTC_CNTL_XPD_SDIO_REG != 0L) "1.8V" else "OFF"
            return "$v (set by RTC_CNTL)"
        }
        val efuse = l.readReg(EFUSE_VDD_SPI_REG)
        if (efuse and VDD_SPI_FORCE != 0L) {
            val v = if (efuse and VDD_SPI_XPD == 0L) "OFF" else if (efuse and VDD_SPI_TIEH == 0L) "1.8V" else "3.3V"
            return "$v (set by eFuse)"
        }
        val strap = l.readReg(GPIO_STRAP_REG) and GPIO_STRAP_VDDSPI_MASK
        return (if (strap != 0L) "1.8V" else "3.3V") + " (set by a strapping pin)"
    }

    /** Crystal frequency as calibrated by the ROM, in Hz. */
    fun romCalCrystalFreq(l: EspLoader): Double {
        val caliVal = (l.readReg(RTCCALICFG1) shr 7) and 0x01FFFFFF
        val clk8m = readEfuse(l, 4) and 0xFF
        return caliVal.toDouble() * 15625 * clk8m / 40
    }

    override fun crystalFreq(l: EspLoader): Int =
        if (l.isStub) (if (romCalCrystalFreq(l) / 1e6 > 33) 40 else 26) else l.estimateCrystalFreq()

    override fun changeBaud(l: EspLoader, baud: Int) {
        if (l.isStub) { l.changeBaudDefault(baud); return }
        val romCalc = romCalCrystalFreq(l)
        val validFreq = if (romCalc > 33_000_000) 40_000_000.0 else 26_000_000.0
        val falseRomBaud = (baud * romCalc / validFreq).toLong()
        l.changeBaudRaw(baud, falseRomBaud, 0)
    }
}
