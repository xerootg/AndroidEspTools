package io.github.xerootg.esptools.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** A serial port found on the USB bus. */
data class UsbPortInfo(
    val driver: UsbSerialDriver,
    val portIndex: Int,
) {
    val device: UsbDevice get() = driver.device
    val id: String get() = "${device.deviceName}#$portIndex"
    val title: String
        get() {
            val name = try { device.productName } catch (e: SecurityException) { null }
            val base = name?.takeIf { it.isNotBlank() } ?: bridgeName(device.vendorId, device.productId)
            return if (driver.ports.size > 1) "$base (port ${portIndex + 1})" else base
        }
    val subtitle: String
        get() = "%04x:%04x · %s · %s".format(device.vendorId, device.productId, driver.javaClass.simpleName.removeSuffix("SerialDriver"), device.deviceName)
    val isEspressifNative: Boolean get() = device.vendorId == 0x303A

    companion object {
        fun bridgeName(vid: Int, pid: Int): String = when (vid) {
            0x303A -> if (pid == 0x1001) "Espressif USB-Serial/JTAG" else "Espressif USB-OTG"
            0x10C4 -> "Silicon Labs CP210x"
            0x1A86 -> when (pid) { 0x7523 -> "WCH CH340"; 0x55D3 -> "WCH CH343"; 0x55D4 -> "WCH CH9102"; else -> "WCH CH34x" }
            0x0403 -> "FTDI"
            0x067B -> "Prolific PL2303"
            else -> "USB serial"
        }
    }
}

object UsbPorts {
    private const val ACTION_USB_PERMISSION = "io.github.xerootg.esptools.USB_PERMISSION"

    fun manager(context: Context): UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    fun list(context: Context): List<UsbPortInfo> {
        val m = manager(context)
        return UsbSerialProber.getDefaultProber().findAllDrivers(m).flatMap { d -> d.ports.indices.map { UsbPortInfo(d, it) } }
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean = manager(context).hasPermission(device)

    /** Ask the user for permission to access [device]; resumes with the result. */
    suspend fun requestPermission(context: Context, device: UsbDevice): Boolean {
        val m = manager(context)
        if (m.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    try { context.unregisterReceiver(this) } catch (e: Exception) {}
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags)
            m.requestPermission(device, pi)
            cont.invokeOnCancellation { try { context.unregisterReceiver(receiver) } catch (e: Exception) {} }
        }
    }

    /** Open the port. Caller must hold permission. */
    fun open(context: Context, info: UsbPortInfo, baud: Int): UsbSerialTransport {
        val m = manager(context)
        val connection = m.openDevice(info.device) ?: throw IllegalStateException("Could not open USB device (permission denied or device busy)")
        val port = info.driver.ports[info.portIndex]
        try {
            port.open(connection)
        } catch (e: Exception) {
            connection.close()
            throw e
        }
        return UsbSerialTransport(port, connection, baud)
    }
}
