package io.github.xerootg.esploader.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal fun ByteArray.hex(from: Int = 0, to: Int = size, upper: Boolean = false): String {
    val sb = StringBuilder((to - from) * 2)
    for (i in from until to) sb.append((if (upper) "%02X" else "%02x").format(this[i].toInt() and 0xFF))
    return sb.toString()
}

fun ByteArray.toHexString(upper: Boolean = false): String = hex(upper = upper)

internal fun le32(vararg values: Int): ByteArray {
    val bb = ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) bb.putInt(v)
    return bb.array()
}

internal fun le32(values: LongArray): ByteArray {
    val bb = ByteBuffer.allocate(4 * values.size).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) bb.putInt(v.toInt())
    return bb.array()
}

internal fun ByteArray.readLe32(offset: Int): Long =
    ((this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24))

internal fun ByteArray.readLe16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun md5Hex(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).hex()

internal fun sha256(data: ByteArray, from: Int = 0, to: Int = data.size): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data, from, to - from)
    return md.digest()
}

internal fun padTo(data: ByteArray, alignment: Int, padByte: Byte = 0): ByteArray {
    val rem = data.size % alignment
    if (rem == 0) return data
    return data + ByteArray(alignment - rem) { padByte }
}

internal fun divRoundUp(a: Long, b: Long): Long = (a + b - 1) / b
internal fun divRoundUp(a: Int, b: Int): Int = (a + b - 1) / b

fun formatMac(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it.toInt() and 0xFF) }

/** Human readable size, e.g. 4MB, 512KB. */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 && bytes % (1024L * 1024) == 0L -> "${bytes / (1024L * 1024)}MB"
    bytes >= 1024 && bytes % 1024 == 0L -> "${bytes / 1024}KB"
    else -> "$bytes bytes"
}

/** Parse "4MB", "512KB", "0x400000" or a plain decimal number into bytes. */
fun parseSize(text: String): Long {
    val t = text.trim().uppercase()
    return when {
        t.endsWith("MB") -> t.dropLast(2).trim().toLong() * 1024 * 1024
        t.endsWith("KB") -> t.dropLast(2).trim().toLong() * 1024
        t.startsWith("0X") -> t.drop(2).toLong(16)
        else -> t.toLong()
    }
}

/** Parse "0x1000" or "4096". */
fun parseAddress(text: String): Long {
    val t = text.trim().lowercase().replace("_", "")
    return if (t.startsWith("0x")) t.drop(2).toLong(16) else t.toLong()
}
