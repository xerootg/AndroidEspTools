package io.github.xerootg.esptools.usb

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import io.github.xerootg.esploader.transport.SerialTransport
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

/**
 * [SerialTransport] over usb-serial-for-android.
 *
 * Android's `bulkTransfer` loses data when polled with short timeouts at high baud rates, so a
 * dedicated thread blocks on the endpoint and appends to a ring buffer; [read] serves from that
 * buffer with its own timeout. A raw listener can be installed (serial monitor) to receive bytes
 * directly instead of buffering them.
 */
class UsbSerialTransport(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    initialBaud: Int,
) : SerialTransport {
    override val usbVendorId: Int? = port.device.vendorId
    override val usbProductId: Int? = port.device.productId
    val deviceName: String = port.device.deviceName

    @Volatile private var open = true
    override val isOpen: Boolean get() = open && readerAlive

    private val lock = ReentrantLock()
    private val dataAvailable = lock.newCondition()
    private var buffer = ByteArray(256 * 1024)
    private var head = 0 // read position
    private var size = 0
    @Volatile private var readerAlive = true
    @Volatile var readerError: Throwable? = null
        private set
    @Volatile private var rawListener: ((ByteArray) -> Unit)? = null

    private val reader = Thread({ readLoop() }, "usb-serial-reader").apply { isDaemon = true }

    override var baudRate: Int = initialBaud
        set(value) {
            port.setParameters(value, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            field = value
        }

    init {
        port.setParameters(initialBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        reader.start()
    }

    private fun readLoop() {
        val buf = ByteArray(16 * 1024)
        while (open) {
            val n = try {
                port.read(buf, 0)
            } catch (e: IOException) {
                if (open) readerError = e
                break
            } catch (e: Exception) {
                if (open) readerError = e
                break
            }
            if (n <= 0) continue
            val listener = rawListener
            if (listener != null) {
                listener(buf.copyOf(n))
                continue
            }
            lock.withLock {
                if (size + n > buffer.size) {
                    // grow (bounded) or drop the oldest bytes
                    if (buffer.size < 4 * 1024 * 1024) {
                        val nb = ByteArray(maxOf(buffer.size * 2, size + n))
                        copyOut(nb, 0, size)
                        buffer = nb; head = 0
                    } else {
                        val drop = size + n - buffer.size
                        head = (head + drop) % buffer.size; size -= drop
                    }
                }
                for (i in 0 until n) {
                    buffer[(head + size + i) % buffer.size] = buf[i]
                }
                size += n
                dataAvailable.signalAll()
            }
        }
        readerAlive = false
        lock.withLock { dataAvailable.signalAll() }
    }

    private fun copyOut(dest: ByteArray, offset: Int, n: Int) {
        for (i in 0 until n) dest[offset + i] = buffer[(head + i) % buffer.size]
    }

    override fun read(dest: ByteArray, timeoutMs: Int): Int {
        lock.withLock {
            if (size == 0) {
                if (!readerAlive) throw IOException("USB device disconnected", readerError)
                var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
                while (size == 0 && nanos > 0 && readerAlive) nanos = dataAvailable.awaitNanos(nanos)
                if (size == 0) {
                    if (!readerAlive) throw IOException("USB device disconnected", readerError)
                    return 0
                }
            }
            val n = minOf(size, dest.size)
            copyOut(dest, 0, n)
            head = (head + n) % buffer.size
            size -= n
            return n
        }
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(16 * 1024, data.size - off)
            port.write(data.copyOfRange(off, off + chunk), 5_000)
            off += chunk
        }
    }

    override fun setDtr(asserted: Boolean) { try { port.dtr = asserted } catch (e: UnsupportedOperationException) {} }
    override fun setRts(asserted: Boolean) { try { port.rts = asserted } catch (e: UnsupportedOperationException) {} }

    override fun purgeInput() {
        lock.withLock { head = 0; size = 0 }
        try { port.purgeHwBuffers(false, true) } catch (e: Exception) {}
    }

    override fun available(): Int = lock.withLock { size }

    /** Route incoming bytes to [listener] (serial monitor) instead of the loader buffer. */
    fun setRawListener(listener: ((ByteArray) -> Unit)?) {
        rawListener = listener
    }

    override fun close() {
        if (!open) return
        open = false
        try { port.close() } catch (e: Exception) {}
        try { connection.close() } catch (e: Exception) {}
        lock.withLock { dataAvailable.signalAll() }
    }
}
