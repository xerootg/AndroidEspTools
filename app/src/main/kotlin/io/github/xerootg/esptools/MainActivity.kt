package io.github.xerootg.esptools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xerootg.esptools.session.ConnectOptions
import io.github.xerootg.esptools.ui.EspToolsRoot
import io.github.xerootg.esptools.ui.theme.EspToolsTheme

class MainActivity : ComponentActivity() {
    private val app get() = application as EspToolsApp

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.let { app.session.onUsbDetached(it) }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { app.session.refreshPorts() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(usbReceiver, filter)
        setContent {
            EspToolsTheme {
                EspToolsRoot(app.session, app.settings)
            }
        }
        handleAttachIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAttachIntent(intent)
    }

    /** Launched (or brought to front) because a known USB bridge was plugged in. */
    private fun handleAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)) ?: return
        val session = app.session
        session.refreshPorts()
        val port = session.ports.value.firstOrNull { it.device.deviceName == device.deviceName } ?: return
        val s = app.settings.value
        if (s.autoConnectOnAttach && session.currentPort == null) {
            session.open(port, connect = true, ConnectOptions(s.baud, s.useStub, s.resetMode, s.connectAttempts))
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) {}
        super.onDestroy()
    }
}
