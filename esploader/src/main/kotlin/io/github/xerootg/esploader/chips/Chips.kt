package io.github.xerootg.esploader.chips

/** Registry of all supported targets, in the order esptool's auto-detection tries them. */
object Chips {
    val all: List<ChipDefinition> = listOf(
        Esp8266, Esp32, Esp32S2, Esp32S3, Esp32C3, Esp32C2, Esp32C6, Esp32C61, Esp32C5,
        Esp32H2, Esp32H21, Esp32P4, Esp32H4, Esp32S31,
    )

    /** Chip ids that esptool no longer supports (pre-production silicon). */
    val unsupportedChipIds = mapOf(
        4 to "ESP32-S3(beta2)", 6 to "ESP32-S3(beta3)", 7 to "ESP32-C6(beta)",
        10 to "ESP32-H2(beta1)", 14 to "ESP32-H2(beta2)", 17 to "ESP32-C5(beta3)",
    )

    fun byImageChipId(id: Int): ChipDefinition? = all.firstOrNull { !it.usesMagicValue && it.imageChipId == id }
    fun byMagic(magic: Long): ChipDefinition? = all.firstOrNull { it.usesMagicValue && it.magicValue == magic }

    /** Look up by name, tolerant of case, dashes and the esptool "esp32s3" spelling. */
    fun byName(name: String): ChipDefinition? {
        val n = name.lowercase().replace("-", "").replace("_", "").replace(" ", "")
        return all.firstOrNull { it.chipName.lowercase().replace("-", "") == n }
    }
}
