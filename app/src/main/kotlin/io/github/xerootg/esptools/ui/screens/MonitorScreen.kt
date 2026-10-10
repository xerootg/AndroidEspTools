package io.github.xerootg.esptools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xerootg.esptools.session.AppSettings
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.PortMode
import io.github.xerootg.esptools.session.SessionState
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.OptionDropdown

@Composable
fun MonitorScreen(session: DeviceSession, settings: Settings) {
    val state by session.state.collectAsStateWithLifecycle()
    val lines by session.monitorLines.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var lineEnding by rememberSaveable { mutableStateOf("\r\n") }
    val listState = rememberLazyListState()
    val open = state as? SessionState.Open
    val monitoring = open?.mode is PortMode.Monitor

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(lines.joinToString("\n").toByteArray()) }
    }

    LaunchedEffect(lines.size, autoScroll) { if (autoScroll && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = monitoring, onClick = { session.monitorReset() }) { Text("Reset") }
            TextButton(enabled = monitoring, onClick = { session.monitorEnterBootloader() }) { Text("Bootloader") }
            TextButton(onClick = { session.monitorClear() }) { Text("Clear") }
            TextButton(enabled = lines.isNotEmpty(), onClick = { saveLauncher.launch("serial-${System.currentTimeMillis()}.log") }) { Text("Save") }
            FilterChip(selected = autoScroll, onClick = { autoScroll = !autoScroll }, label = { Text("Auto-scroll") })
            FilterChip(selected = lineEnding == "\r\n", onClick = { lineEnding = if (lineEnding == "\r\n") "\n" else "\r\n" }, label = { Text(if (lineEnding == "\r\n") "CRLF" else "LF") })
        }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(Color(0xFF0B1220)).padding(6.dp)) {
                itemsIndexed(lines) { _, line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 15.sp, color = Color(0xFFE2E8F0))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), singleLine = true, enabled = monitoring,
                placeholder = { Text("Send to device") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { session.monitorSend(input + lineEnding); input = "" }),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(enabled = monitoring, onClick = { session.monitorSend(input + lineEnding); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
}
