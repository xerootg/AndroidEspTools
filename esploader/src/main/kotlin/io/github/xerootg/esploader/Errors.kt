package io.github.xerootg.esploader

/** Base class for every failure reported by the loader. Mirrors esptool's FatalError. */
open class EspException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    companion object {
        /** Build an error from a 2-byte ROM/stub status response, the way esptool's FatalError.WithResult does. */
        fun withResult(message: String, status: ByteArray): EspException {
            val code = if (status.size >= 2) status[1].toInt() and 0xFF else -1
            val desc = ERROR_CODES[code]
            val hex = status.joinToString("") { "%02x".format(it) }
            val suffix = if (desc != null) " (result was 0x$hex: $desc)" else " (result was 0x$hex)"
            return EspException(message + suffix)
        }

        private val ERROR_CODES = mapOf(
            // ROM error codes
            0x05 to "Received message is invalid (parameters or length field is invalid)",
            0x06 to "Failed to act on received message",
            0x07 to "Invalid CRC in message",
            0x08 to "Flash write error - after writing a block of data to flash, the ROM loader reads the value back and the 8-bit CRC is compared to the data read from flash. If they don't match, this error is returned.",
            0x09 to "Flash read error - SPI read failed",
            0x0A to "Flash read length error - SPI read request length is too long",
            0x0B to "Deflate error (compressed uploads only)",
            // Stub error codes
            0xC0 to "ESP_BAD_DATA_LEN",
            0xC1 to "ESP_BAD_DATA_CHECKSUM",
            0xC2 to "ESP_BAD_BLOCKSIZE",
            0xC3 to "ESP_INVALID_COMMAND",
            0xC4 to "ESP_FAILED_SPI_OP",
            0xC5 to "ESP_FAILED_SPI_UNLOCK",
            0xC6 to "ESP_NOT_IN_FLASH_MODE",
            0xC7 to "ESP_INFLATE_ERROR",
            0xC8 to "ESP_NOT_ENOUGH_DATA",
            0xC9 to "ESP_TOO_MUCH_DATA",
            0xFF to "ESP_CMD_NOT_IMPLEMENTED",
        )
    }
}

/** No (or truncated) serial data arrived before the deadline. */
class EspTimeoutException(message: String) : EspException(message)

/** The ROM loader replied that it does not know the command (ROM only, stub-only command). */
class UnsupportedCommandException(val op: Int, chipName: String) :
    EspException("Command 0x%02x is not supported by the ROM loader of %s (the flasher stub is required)".format(op, chipName))

/** Feature exists in the stub but not in this chip's ROM loader. */
class NotImplementedInRomException(what: String, chipName: String) :
    EspException("$what is not supported by the $chipName ROM loader. Enable the flasher stub to use it.")

/** Feature is not available on this chip at all. */
class NotSupportedException(what: String, chipName: String) :
    EspException("$what is not supported on $chipName.")

/** Thrown when the host cancelled the running operation. */
class EspCancelledException : EspException("Operation cancelled")
