package io.github.xerootg.esptools.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import io.github.xerootg.esptools.session.AppSettings
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.PortMode
import io.github.xerootg.esptools.session.SessionState
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.OptionDropdown

/**
 * Serial console backed by Termux's VT100/xterm terminal emulator, so interactive firmware
 * consoles (ESP-IDF `esp_console`, linenoise history/editing, colours, cursor movement) behave
 * like they do in `idf.py monitor`.
 */
@Composable
fun MonitorScreen(session: DeviceSession, settings: Settings) {
    val state by session.state.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val lines by session.monitorLines.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val open = state as? SessionState.Open
    val monitoring = open?.mode is PortMode.Monitor
    var ctrl by remember { mutableStateOf(false) }
    var textSize by rememberSaveable { mutableStateOf(13) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(lines.joinToString("\n").toByteArray()) }
    }

    fun showKeyboard(v: TerminalView) {
        v.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(v, 0)
    }

    val viewClient = remember {
        object : TerminalViewClient {
            override fun onScale(scale: Float): Float {
                if (scale < 0.9f || scale > 1.1f) {
                    textSize = (textSize * scale).toInt().coerceIn(8, 32)
                    terminalView?.setTextSize(spToPx(context, textSize))
                    return 1.0f
                }
                return scale
            }
            override fun onSingleTapUp(e: MotionEvent) { terminalView?.let { showKeyboard(it) } }
            override fun shouldBackButtonBeMappedToEscape() = false
            override fun shouldEnforceCharBasedInput() = true
            override fun shouldUseCtrlSpaceWorkaround() = false
            override fun isTerminalViewSelected() = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onKeyDown(keyCode: Int, e: KeyEvent, s: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
            override fun onLongPress(event: MotionEvent): Boolean = false
            override fun readControlKey(): Boolean { val c = ctrl; if (c) ctrl = false; return c }
            override fun readAltKey() = false
            override fun readShiftKey() = false
            override fun readFnKey() = false
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession): Boolean = false
            override fun onEmulatorSet() {}
            override fun logError(tag: String?, message: String?) {}
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
            override fun logStackTrace(tag: String?, e: Exception?) {}
        }
    }

    DisposableEffect(Unit) { onDispose { session.terminal.view = null } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionDropdown("Baud", AppSettings.MONITOR_BAUD_RATES, (open?.mode as? PortMode.Monitor)?.baud ?: prefs.monitorBaud,
                { b -> settings.update { it.copy(monitorBaud = b) }; if (monitoring) session.monitorSetBaud(b) }, modifier = Modifier.weight(1f), display = { "$it" })
            if (open == null) {
                Text("Open a port on the Device tab first", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            } else if (!monitoring) {
                Button(enabled = busy == null, onClick = { session.startMonitor(prefs.monitorBaud, resetChip = true) }) { Text("Start") }
                OutlinedButton(enabled = busy == null, onClick = { session.startMonitor(prefs.monitorBaud, resetChip = false) }) { Text("No reset") }
            } else {
                Button(onClick = { session.stopMonitor() }) { Text("Stop") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = monitoring, onClick = { session.monitorReset() }) { Text("Reset") }
            TextButton(enabled = monitoring, onClick = { session.monitorEnterBootloader() }) { Text("Bootloader") }
            TextButton(onClick = { session.monitorClear() }) { Text("Clear") }
            TextButton(enabled = lines.isNotEmpty(), onClick = { saveLauncher.launch("serial-${System.currentTimeMillis()}.log") }) { Text("Save") }
            TextButton(onClick = { terminalView?.let { showKeyboard(it) } }) { Text("Keyboard") }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                TerminalView(ctx, null).apply {
                    setTerminalViewClient(viewClient)
                    setTypeface(Typeface.MONOSPACE)
                    setTextSize(spToPx(ctx, textSize))
                    setBackgroundColor(0xFF0B1220.toInt())
                    isFocusable = true
                    isFocusableInTouchMode = true
                    attachSession(session.terminal.session)
                    session.terminal.view = this
                    terminalView = this
                }
            },
            update = { v -> session.terminal.view = v; v.onScreenUpdated() },
        )
        // Extra keys row: what a phone keyboard lacks for an interactive console.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
            val keys = listOf(
                "Esc" to KeyEvent.KEYCODE_ESCAPE, "Tab" to KeyEvent.KEYCODE_TAB, "↑" to KeyEvent.KEYCODE_DPAD_UP, "↓" to KeyEvent.KEYCODE_DPAD_DOWN,
                "←" to KeyEvent.KEYCODE_DPAD_LEFT, "→" to KeyEvent.KEYCODE_DPAD_RIGHT, "Home" to KeyEvent.KEYCODE_MOVE_HOME, "End" to KeyEvent.KEYCODE_MOVE_END,
                "⏎" to KeyEvent.KEYCODE_ENTER,
            )
            keys.forEach { (label, code) ->
                TextButton(enabled = monitoring, onClick = { terminalView?.handleKeyCode(code, if (ctrl) com.termux.terminal.KeyHandler.KEYMOD_CTRL else 0); ctrl = false }) { Text(label) }
            }
            TextButton(enabled = monitoring, onClick = { terminalView?.inputCodePoint('c'.code, true, false) }) { Text("^C") }
            TextButton(enabled = monitoring, onClick = { terminalView?.inputCodePoint('d'.code, true, false) }) { Text("^D") }
            TextButton(enabled = monitoring, onClick = { terminalView?.inputCodePoint('l'.code, true, false) }) { Text("^L") }
        }
    }
}

private fun spToPx(context: Context, sp: Int): Int = (sp * context.resources.displayMetrics.scaledDensity).toInt()
