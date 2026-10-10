package io.github.xerootg.esploader.chips

import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.EspLoader

object Esp8266 : ChipDefinition() {
    override val chipName = "ESP8266"
    override val imageChipId = -1
    override val usesMagicValue = true
    override val magicValue = 0xFFF0C101L

    private const val ESP_OTP_MAC0 = 0x3FF00050L
    private const val ESP_OTP_MAC1 = 0x3FF00054L
    private const val ESP_OTP_MAC3 = 0x3FF0005CL

    override val spiRegBase = 0x60000200L
    override val spiUsrOffs = 0x1CL
    override val spiUsr1Offs = 0x20L
    override val spiUsr2Offs = 0x24L
    override val spiMosiDlenOffs: Long? = null
    override val spiMisoDlenOffs: Long? = null
    override val spiW0Offs = 0x40L
    override val spiAddrRegMsb = true
    override val uartClkdivReg = 0x60000014L
    override val xtalClkDivider = 2

    override val flashSizes = mapOf(
        "512KB" to 0x00, "256KB" to 0x10, "1MB" to 0x20, "2MB" to 0x30, "4MB" to 0x40,
        "2MB-c1" to 0x50, "4MB-c1" to 0x60, "8MB" to 0x80, "16MB" to 0x90,
    )
    override val flashFrequency = STANDARD_FLASH_FREQUENCY
    override val bootloaderFlashOffset = 0L
    override val uf2FamilyId = 0x7EAB61EDL
    override val memoryMap = listOf(
        MemRegion(0x3FF00000, 0x3FF00010, "DPORT"), MemRegion(0x3FFE8000, 0x40000000, "DRAM"),
        MemRegion(0x40100000, 0x40108000, "IRAM"), MemRegion(0x40201010, 0x402E1010, "IROM"),
    )

    override val romSupportsCompressedFlash = false
    override val romSupportsMd5 = false
    override val romSupportsReadFlashSlow = false
    override val romSupportsExtendedFlashParams = false

    private fun efuses(l: EspLoader): java.math.BigInteger {
        var r = java.math.BigInteger.valueOf(l.readReg(0x3FF0005C)).shiftLeft(96)
        r = r.or(java.math.BigInteger.valueOf(l.readReg(0x3FF00058)).shiftLeft(64))
        r = r.or(java.math.BigInteger.valueOf(l.readReg(0x3FF00054)).shiftLeft(32))
        r = r.or(java.math.BigInteger.valueOf(l.readReg(0x3FF00050)))
        return r
    }

    private fun flashSizeFromEfuses(e: java.math.BigInteger): Int {
        val r04 = e.testBit(4); val r325 = e.testBit(121); val r326 = e.testBit(122); val r327 = e.testBit(123)
        if (r04 && !r325) {
            if (!r327 && !r326) return 1
            if (!r327 && r326) return 2
        }
        if (!r04 && r325) {
            if (!r327 && !r326) return 2
            if (!r327 && r326) return 4
        }
        return -1
    }

    override fun chipDescription(l: EspLoader): String {
        val e = efuses(l)
        val is8285 = e.testBit(4) || e.testBit(80)
        if (is8285) {
            val maxTemp = e.testBit(5)
            return when (flashSizeFromEfuses(e)) {
                1 -> if (maxTemp) "ESP8285H08" else "ESP8285N08"
                2 -> if (maxTemp) "ESP8285H16" else "ESP8285N16"
                else -> "ESP8285"
            }
        }
        return "ESP8266EX"
    }

    override fun chipFeatures(l: EspLoader): List<String> {
        val f = mutableListOf("Wi-Fi", "160MHz")
        if (chipDescription(l).contains("ESP8285")) f += "Embedded Flash"
        return f
    }

    override fun flashSpiAttach(l: EspLoader, hspiArg: Long) {
        if (l.isStub) l.flashSpiAttachDefault(hspiArg) else l.flashBegin(0, 0)
    }

    override fun flashSetParameters(l: EspLoader, size: Long) {
        if (l.isStub) l.flashSetParametersDefault(size)
    }

    /** The equivalent of the SDK's system_get_chip_id(). */
    fun chipId(l: EspLoader): Long {
        val id0 = l.readReg(ESP_OTP_MAC0)
        val id1 = l.readReg(ESP_OTP_MAC1)
        return ((id0 shr 24) or ((id1 and 0xFFFFFF) shl 8)) and 0xFFFFFFFFL
    }

    override fun readMac(l: EspLoader): ByteArray {
        val mac0 = l.readReg(ESP_OTP_MAC0)
        val mac1 = l.readReg(ESP_OTP_MAC1)
        val mac3 = l.readReg(ESP_OTP_MAC3)
        val oui = when {
            mac3 != 0L -> byteArrayOf((mac3 shr 16).toByte(), (mac3 shr 8).toByte(), mac3.toByte())
            (mac1 shr 16) and 0xFF == 0L -> byteArrayOf(0x18, 0xFE.toByte(), 0x34)
            (mac1 shr 16) and 0xFF == 1L -> byteArrayOf(0xAC.toByte(), 0xD0.toByte(), 0x74)
            else -> throw EspException("Unknown OUI")
        }
        return oui + byteArrayOf((mac1 shr 8).toByte(), mac1.toByte(), (mac0 shr 24).toByte())
    }

    /** Workaround for the ROM bootloader erase bug; the stub does not need it. */
    override fun eraseSize(l: EspLoader, offset: Long, size: Long): Long {
        if (l.isStub) return size
        val sectorsPerBlock = 16L
        val sectorSize = EspLoader.FLASH_SECTOR_SIZE.toLong()
        val numSectors = (size + sectorSize - 1) / sectorSize
        val startSector = offset / sectorSize
        var headSectors = sectorsPerBlock - (startSector % sectorsPerBlock)
        if (numSectors < headSectors) headSectors = numSectors
        return if (numSectors < 2 * headSectors) (numSectors + 1) / 2 * sectorSize
        else (numSectors - headSectors) * sectorSize
    }
}
