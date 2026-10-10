package io.github.xerootg.esploader

/** Parsed GET_SECURITY_INFO response. [chipId] / [apiVersion] are null on ESP32-S2. */
data class SecurityInfo(
    val flags: Long,
    val flashCryptCnt: Int,
    val keyPurposes: List<Int>,
    val chipId: Int?,
    val apiVersion: Int?,
) {
    val secureBootEnabled get() = flags and (1L shl 0) != 0L
    val secureBootAggressiveRevoke get() = flags and (1L shl 1) != 0L
    val secureDownloadEnabled get() = flags and (1L shl 2) != 0L
    val secureBootKeyRevoke0 get() = flags and (1L shl 3) != 0L
    val secureBootKeyRevoke1 get() = flags and (1L shl 4) != 0L
    val secureBootKeyRevoke2 get() = flags and (1L shl 5) != 0L
    val softDisJtag get() = flags and (1L shl 6) != 0L
    val hardDisJtag get() = flags and (1L shl 7) != 0L
    val disUsb get() = flags and (1L shl 8) != 0L
    val disDownloadDcache get() = flags and (1L shl 9) != 0L
    val disDownloadIcache get() = flags and (1L shl 10) != 0L

    /** Flash encryption is enabled when the crypt count has an odd number of set bits. */
    val flashEncryptionEnabled get() = Integer.bitCount(flashCryptCnt) and 1 != 0

    val flagNames: List<String>
        get() = buildList {
            if (secureBootEnabled) add("SECURE_BOOT_EN")
            if (secureBootAggressiveRevoke) add("SECURE_BOOT_AGGRESSIVE_REVOKE")
            if (secureDownloadEnabled) add("SECURE_DOWNLOAD_ENABLE")
            if (secureBootKeyRevoke0) add("SECURE_BOOT_KEY_REVOKE0")
            if (secureBootKeyRevoke1) add("SECURE_BOOT_KEY_REVOKE1")
            if (secureBootKeyRevoke2) add("SECURE_BOOT_KEY_REVOKE2")
            if (softDisJtag) add("SOFT_DIS_JTAG")
            if (hardDisJtag) add("HARD_DIS_JTAG")
            if (disUsb) add("DIS_USB")
            if (disDownloadDcache) add("DIS_DOWNLOAD_DCACHE")
            if (disDownloadIcache) add("DIS_DOWNLOAD_ICACHE")
        }
}
