package io.github.xerootg.esploader.transport

import java.io.Closeable

/**
 * Minimal serial port abstraction the loader is written against. Android USB serial, a TCP bridge
 * or an in-memory fake all plug in here.
 *
 * DTR/RTS follow the pyserial / usb-serial-for-android convention: `true` asserts the line
 * (drives it low on the wire), which pulls EN or IO0 low on the standard auto-reset circuit.
 */
interface SerialTransport : Closeable {
    /** USB vendor id of the bridge, or null when unknown (TCP, fake, …). */
    val usbVendorId: Int?
    /** USB product id of the bridge, or null when unknown. */
    val usbProductId: Int?

    /** Current baud rate. Setting it reconfigures the port. */
    var baudRate: Int

    /**
     * Read up to `dest.size` bytes, waiting at most [timeoutMs] milliseconds for the first byte.
     * Returns 0 on timeout. Must return whatever is already buffered immediately.
     */
    fun read(dest: ByteArray, timeoutMs: Int): Int

    /** Write all of [data]. Blocks until handed to the driver. */
    fun write(data: ByteArray)

    fun setDtr(asserted: Boolean)
    fun setRts(asserted: Boolean)

    /** Set both lines; default implementation sets them sequentially (DTR first). */
    fun setDtrRts(dtr: Boolean, rts: Boolean) {
        setDtr(dtr)
        setRts(rts)
    }

    /** Drop any buffered unread input. */
    fun purgeInput()

    /** Bytes that can be read without blocking (best effort, may be 0 when unknown). */
    fun available(): Int

    /** True if the port is still usable (device not detached). */
    val isOpen: Boolean
}
