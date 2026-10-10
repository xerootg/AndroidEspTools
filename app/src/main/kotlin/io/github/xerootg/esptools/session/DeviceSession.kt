package io.github.xerootg.esptools.session

import android.app.Application
import android.hardware.usb.UsbDevice
import io.github.xerootg.esploader.CancelToken
import io.github.xerootg.esploader.ChipInfo
import io.github.xerootg.esploader.EspCancelledException
import io.github.xerootg.esploader.EspDevice
import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.EspLoader
import io.github.xerootg.esploader.ProgressListener
import io.github.xerootg.esploader.ResetMode
import io.github.xerootg.esptools.usb.UsbPortInfo
import io.github.xerootg.esptools.usb.UsbPorts
import io.github.xerootg.esptools.usb.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.Executors

/** What the open port is currently used for. */
sealed interface PortMode {
    data object Idle : PortMode
    /** The ROM loader / stub is synced and ready for commands. */
    data class Loader(val info: ChipInfo) : PortMode
    /** Raw serial console. */
    data class Monitor(val baud: Int) : PortMode
}

sealed interface SessionState {
    data object Disconnected : SessionState
    data class Open(val port: UsbPortInfo, val mode: PortMode) : SessionState
}

/** A long-running operation shown as a progress overlay. */
data class BusyState(val title: String, val detail: String = "", val progress: Float? = null, val cancellable: Boolean = true)

data class ConnectOptions(
    val baud: Int,
    val useStub: Boolean,
    val resetMode: ResetMode,
    val attempts: Int = EspLoader.DEFAULT_CONNECT_ATTEMPTS,
)

/** Snackbar-style one-shot messages for the UI. */
data class UiMessage(val text: String, val isError: Boolean = false)

/**
 * Owns the open USB port, the loader and the serial monitor. There is exactly one per app
 * process; every screen reads its state flows. All chip access is serialised through
 * [runOperation] on a single background thread so the protocol is never interleaved.
 */
class DeviceSession(private val app: Application, val settings: Settings) {
    val log = LogBuffer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val deviceDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "esp-device").apply { isDaemon = true } }.asCoroutineDispatcher()
    private val opMutex = Mutex()

    private val _ports = MutableStateFlow<List<UsbPortInfo>>(emptyList())
    val ports: StateFlow<List<UsbPortInfo>> = _ports
    private val _state = MutableStateFlow<SessionState>(SessionState.Disconnected)
    val state: StateFlow<SessionState> = _state
    private val _busy = MutableStateFlow<BusyState?>(null)
    val busy: StateFlow<BusyState?> = _busy
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages

    /** Serial monitor output as lines (last entry may be a partial line). Capped. */
    private val _monitorLines = MutableStateFlow<List<String>>(emptyList())
    val monitorLines: StateFlow<List<String>> = _monitorLines
    private val monitorLock = Any()
    private val monitorPartial = StringBuilder()
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

    private var transport: UsbSerialTransport? = null
    private var device: EspDevice? = null
    private var currentJob: Job? = null
    @Volatile private var cancelRequested = false

    val currentPort: UsbPortInfo? get() = (_state.value as? SessionState.Open)?.port
    val chipInfo: ChipInfo? get() = ((_state.value as? SessionState.Open)?.mode as? PortMode.Loader)?.info
    val isLoaderReady: Boolean get() = chipInfo != null
    val isBusy: Boolean get() = _busy.value != null

    init {
        log.traceEnabled = settings.value.trace
        scope.launch { settings.state.collect { log.traceEnabled = it.trace } }
        refreshPorts()
    }

    fun refreshPorts() {
        _ports.value = try { UsbPorts.list(app) } catch (e: Exception) { log.warn("USB enumeration failed: ${e.message}"); emptyList() }
    }

    fun post(message: String, isError: Boolean = false) {
        if (isError) log.error(message)
        _messages.tryEmit(UiMessage(message, isError))
    }

    // ------------------------------------------------------------------ port lifecycle

    /** Request permission, open the port, then (optionally) sync with the ROM loader. */
    fun open(port: UsbPortInfo, connect: Boolean, options: ConnectOptions) {
        scope.launch {
            if (isBusy) { post("Another operation is running", true); return@launch }
            try {
                _busy.value = BusyState("Opening ${port.title}", cancellable = false)
                val granted = UsbPorts.requestPermission(app, port.device)
                if (!granted) { post("USB permission denied", true); return@launch }
                closeTransport()
                val t = withContext(Dispatchers.IO) { UsbPorts.open(app, port, EspLoader.ESP_ROM_BAUD) }
                transport = t
                _state.value = SessionState.Open(port, PortMode.Idle)
                log.info("Opened ${port.title} (${port.subtitle})")
            } catch (e: Exception) {
                post("Failed to open port: ${e.message}", true)
                return@launch
            } finally {
                _busy.value = null
            }
            if (connect) connectLoader(options)
        }
    }

    /** Sync with the bootloader on the already-open port and gather chip info. */
    fun connectLoader(options: ConnectOptions) {
        val port = currentPort ?: run { post("No port open", true); return }
        scope.launch {
            stopMonitorInternal()
            try {
                val info = runOperation("Connecting to $port".let { "Connecting" }, cancellable = true) { _, _ ->
                    val t = transport ?: throw EspException("Port closed")
                    t.baudRate = EspLoader.ESP_ROM_BAUD
                    val loader = EspLoader(t, log, CancelToken { cancelRequested })
                    val dev = EspDevice(loader)
                    device = dev
                    dev.connect(options.resetMode, options.useStub, options.baud, options.attempts)
                    val info = dev.chipInfo()
                    log.info("Chip: ${info.description}; features: ${info.features.joinToString()}")
                    info.flash?.let { log.info("Flash: ${dev.describeFlash(it)}") }
                    info
                }
                _state.update { s -> if (s is SessionState.Open) s.copy(mode = PortMode.Loader(info)) else s }
                post("Connected: ${info.description}")
            } catch (e: Exception) {
                device = null
                _state.update { s -> if (s is SessionState.Open) s.copy(mode = PortMode.Idle) else s }
                if (e !is EspCancelledException) post(e.message ?: "Connect failed", true) else post("Connect cancelled")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            cancelOperation()
            stopMonitorInternal()
            closeTransport()
            _state.value = SessionState.Disconnected
            log.info("Port closed")
        }
    }

    private fun closeTransport() {
        device = null
        transport?.close()
        transport = null
    }

    fun onUsbDetached(usbDevice: UsbDevice) {
        val port = currentPort
        if (port != null && port.device.deviceName == usbDevice.deviceName) {
            log.warn("USB device detached")
            cancelOperation()
            scope.launch {
                stopMonitorInternal()
                closeTransport()
                _state.value = SessionState.Disconnected
                post("USB device detached", true)
            }
        }
        refreshPorts()
    }

    // ------------------------------------------------------------------ operations

    /**
     * Run [block] on the device thread with the busy overlay shown. Only one operation runs at a
     * time. The loader must be connected unless [requireLoader] is false.
     */
    suspend fun <T> runOperation(
        title: String,
        cancellable: Boolean = true,
        requireLoader: Boolean = false,
        block: (EspDevice, ProgressListener) -> T,
    ): T {
        if (requireLoader && device == null) throw EspException("Not connected to a chip. Connect first.")
        return opMutex.withLock {
            cancelRequested = false
            _busy.value = BusyState(title, cancellable = cancellable)
            val progress = ProgressListener { done, total, desc ->
                val frac = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
                _busy.value = BusyState(title, desc, frac, cancellable)
            }
            try {
                withContext(deviceDispatcher) {
                    currentJob = coroutineContext[Job]
                    runInterruptible {
                        val dev = device ?: EspDevice(EspLoader(transport ?: throw EspException("No port open"), log, CancelToken { cancelRequested }))
                        block(dev, progress)
                    }
                }
            } catch (e: InterruptedException) {
                throw EspCancelledException()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (cancelRequested) throw EspCancelledException() else throw e
            } catch (e: IOException) {
                log.error("USB I/O error: ${e.message}")
                throw EspException("USB I/O error: ${e.message}", e)
            } catch (e: EspException) {
                if (e !is EspCancelledException) log.error(e.message ?: e.toString())
                throw e
            } finally {
                currentJob = null
                _busy.value = null
            }
        }
    }

    /** Fire-and-forget wrapper used by screens: reports errors as messages. */
    fun launchOperation(title: String, cancellable: Boolean = true, requireLoader: Boolean = true, onDone: (() -> Unit)? = null, block: (EspDevice, ProgressListener) -> Unit) {
        scope.launch {
            try {
                runOperation(title, cancellable, requireLoader, block)
                onDone?.invoke()
            } catch (e: EspCancelledException) {
                post("$title cancelled")
            } catch (e: Exception) {
                post(e.message ?: "$title failed", true)
            }
        }
    }

    fun cancelOperation() {
        cancelRequested = true
        currentJob?.cancel()
    }

    /** After a reset the loader is gone; drop to Idle so the UI asks to reconnect. */
    fun markLoaderLost(reason: String) {
        device = null
        _state.update { s -> if (s is SessionState.Open) s.copy(mode = PortMode.Idle) else s }
        log.info(reason)
    }

    // ------------------------------------------------------------------ serial monitor

    fun startMonitor(baud: Int, resetChip: Boolean) {
        val t = transport ?: run { post("No port open", true); return }
        scope.launch {
            if (isBusy) { post("Another operation is running", true); return@launch }
            device = null
            try {
                withContext(Dispatchers.IO) {
                    t.baudRate = baud
                    t.setRawListener { bytes -> appendMonitor(bytes) }
                    t.purgeInput()
                    if (resetChip) {
                        t.setDtr(false)
                        t.setRts(true); Thread.sleep(100); t.setRts(false)
                    }
                }
                _state.update { s -> if (s is SessionState.Open) s.copy(mode = PortMode.Monitor(baud)) else s }
                log.info("Serial monitor started at $baud baud")
            } catch (e: Exception) {
                post("Monitor failed: ${e.message}", true)
            }
        }
    }

    fun stopMonitor() { scope.launch { stopMonitorInternal() } }

    private suspend fun stopMonitorInternal() {
        val t = transport ?: return
        val s = _state.value
        if (s is SessionState.Open && s.mode is PortMode.Monitor) {
            withContext(Dispatchers.IO) { t.setRawListener(null) }
            _state.value = s.copy(mode = PortMode.Idle)
            log.info("Serial monitor stopped")
        }
    }

    private fun appendMonitor(bytes: ByteArray) {
        val text = String(bytes, Charsets.UTF_8).replace("\r", "")
        synchronized(monitorLock) {
            monitorPartial.append(text)
            if (!text.contains('\n') && monitorPartial.length < 512) {
                // update the trailing partial line in place
                val p = monitorPartial.toString()
                _monitorLines.update { old -> if (old.isEmpty()) listOf(p) else old.dropLast(1) + p }
                return
            }
            val parts = monitorPartial.toString().split('\n')
            monitorPartial.setLength(0)
            monitorPartial.append(parts.last())
            val complete = parts.dropLast(1).map { ansi.replace(it, "") }
            _monitorLines.update { old ->
                val base = if (old.isNotEmpty()) old.dropLast(1) else old
                val merged = base + complete + monitorPartial.toString()
                if (merged.size > 2500) merged.drop(merged.size - 2000) else merged
            }
        }
    }

    fun monitorClear() {
        synchronized(monitorLock) { monitorPartial.setLength(0); _monitorLines.value = emptyList() }
    }

    fun monitorSend(text: String) {
        val t = transport ?: return
        scope.launch(Dispatchers.IO) { try { t.write(text.toByteArray()) } catch (e: Exception) { post("Send failed: ${e.message}", true) } }
    }

    /** Pulse EN (RTS) to restart the application. */
    fun monitorReset() {
        val t = transport ?: return
        scope.launch(Dispatchers.IO) {
            try { t.setDtr(false); t.setRts(true); Thread.sleep(100); t.setRts(false) } catch (e: Exception) { post("Reset failed: ${e.message}", true) }
        }
    }

    /** DTR/RTS dance into the ROM download mode without starting the loader. */
    fun monitorEnterBootloader() {
        val t = transport ?: return
        scope.launch(Dispatchers.IO) {
            try {
                t.setDtr(false); t.setRts(true); Thread.sleep(100)
                t.setDtr(true); t.setRts(false); Thread.sleep(50)
                t.setDtr(false)
            } catch (e: Exception) { post("Reset failed: ${e.message}", true) }
        }
    }

    fun monitorSetBaud(baud: Int) {
        val t = transport ?: return
        scope.launch(Dispatchers.IO) {
            try {
                t.baudRate = baud
                _state.update { s -> if (s is SessionState.Open && s.mode is PortMode.Monitor) s.copy(mode = PortMode.Monitor(baud)) else s }
            } catch (e: Exception) { post("Baud change failed: ${e.message}", true) }
        }
    }
}
