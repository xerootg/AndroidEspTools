package io.github.xerootg.esptools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xerootg.esploader.ChipInfo
import io.github.xerootg.esploader.ResetAfter
import io.github.xerootg.esploader.ResetMode
import io.github.xerootg.esptools.session.AppSettings
import io.github.xerootg.esptools.session.ConnectOptions
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.PortMode
import io.github.xerootg.esptools.session.SessionState
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.Dest
import io.github.xerootg.esptools.ui.KeyValue
import io.github.xerootg.esptools.ui.OptionDropdown
import io.github.xerootg.esptools.ui.SectionCard
import io.github.xerootg.esptools.ui.SwitchRow
import io.github.xerootg.esptools.ui.hex

private fun AppSettings.connectOptions() = ConnectOptions(baud, useStub, resetMode, connectAttempts)

fun resetModeLabel(m: ResetMode) = when (m) {
    ResetMode.DEFAULT -> "Auto reset (DTR/RTS)"
    ResetMode.USB_RESET -> "USB-Serial/JTAG reset"
    ResetMode.NO_RESET -> "No reset (manual BOOT)"
    ResetMode.NO_RESET_NO_SYNC -> "No reset, no sync"
}

fun resetAfterLabel(m: ResetAfter) = when (m) {
    ResetAfter.HARD_RESET -> "Hard reset (run app)"
    ResetAfter.SOFT_RESET -> "Soft reset"
    ResetAfter.NO_RESET -> "Stay in bootloader"
    ResetAfter.NO_RESET_STUB -> "Stay in stub"
    ResetAfter.WATCHDOG_RESET -> "Watchdog reset"
}

@Composable
fun DeviceScreen(session: DeviceSession, settings: Settings, onNavigate: (Dest) -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val ports by session.ports.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            when (val s = state) {
                SessionState.Disconnected -> PortPicker(session, prefs, ports, busy != null)
                is SessionState.Open -> OpenPortCard(session, prefs, s, busy != null)
            }
        }
        item {
            SectionCard("Connection options", collapsible = true, initiallyExpanded = state is SessionState.Disconnected) {
                OptionDropdown("Baud rate", AppSettings.BAUD_RATES, prefs.baud, { b -> settings.update { it.copy(baud = b) } }, display = { "$it" })
                Spacer(Modifier.height(8.dp))
                OptionDropdown("Before connecting", ResetMode.entries, prefs.resetMode, { m -> settings.update { it.copy(resetMode = m) } }, display = ::resetModeLabel)
                SwitchRow("Use flasher stub", prefs.useStub, { v -> settings.update { it.copy(useStub = v) } },
                    description = "Faster transfers, compression, MD5 verify, full-chip erase and flash reads. Disable for ROM-only mode.")
            }
        }
        val info = (state as? SessionState.Open)?.mode?.let { it as? PortMode.Loader }?.info
        if (info != null) {
            item { ChipInfoCard(info) }
        }
        item {
            Text("Actions", style = MaterialTheme.typography.titleMedium)
        }
        item {
            val actions = listOf(
                Triple("Flash firmware", Icons.Default.Upload, Dest.Flash),
                Triple("Read / dump flash", Icons.Default.Download, Dest.Tools),
                Triple("Erase flash", Icons.Default.DeleteSweep, Dest.Tools),
                Triple("Serial monitor", Icons.Default.Terminal, Dest.Monitor),
                Triple("Advanced tools", Icons.Default.Build, Dest.Tools),
            )
            LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.height(((actions.size + 1) / 2 * 92).dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
                items(actions) { (label, icon, dest) -> ActionTile(label, icon) { onNavigate(dest) } }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.height(80.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun PortPicker(session: DeviceSession, prefs: AppSettings, ports: List<io.github.xerootg.esptools.usb.UsbPortInfo>, busy: Boolean) {
    SectionCard("USB serial ports", subtitle = if (ports.isEmpty()) "No supported USB serial device found" else "${ports.size} port(s) found") {
        if (ports.isEmpty()) {
            Text(
                "Connect the board with a USB OTG cable. Supported bridges: Espressif USB-Serial/JTAG and USB-OTG, CP210x, CH340/CH343/CH9102, FTDI, PL2303 and any CDC-ACM device.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
        ports.forEach { p ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge)
                        Text(p.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Button(enabled = !busy, onClick = { session.open(p, connect = true, prefs.connectOptions()) }) { Text("Connect") }
                        OutlinedButton(enabled = !busy, onClick = { session.open(p, connect = false, prefs.connectOptions()) }) { Text("Open only") }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = { session.refreshPorts() }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
        }
    }
}

@Composable
private fun OpenPortCard(session: DeviceSession, prefs: AppSettings, s: SessionState.Open, busy: Boolean) {
    val modeText = when (val m = s.mode) {
        PortMode.Idle -> "Port open, loader not connected"
        is PortMode.Loader -> "Connected" + (if (m.info.stubRunning) " (stub running)" else " (ROM loader)")
        is PortMode.Monitor -> "Serial monitor at ${m.baud} baud"
    }
    SectionCard(s.port.title, subtitle = s.port.subtitle) {
        AssistChip(onClick = {}, label = { Text(modeText) })
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.mode !is PortMode.Loader) {
                Button(enabled = !busy, onClick = { session.connectLoader(prefs.connectOptions()) }) { Text("Connect loader") }
            } else {
                FilledTonalButton(enabled = !busy, onClick = { session.connectLoader(prefs.connectOptions()) }) { Text("Reconnect") }
                OutlinedButton(enabled = !busy, onClick = {
                    session.launchOperation("Resetting", cancellable = false, onDone = { session.markLoaderLost("Chip reset; reconnect to use the loader again.") }) { dev, _ -> dev.reset(ResetAfter.HARD_RESET) }
                }) { Text("Reset chip") }
            }
            OutlinedButton(enabled = !busy, onClick = { session.disconnect() }) { Text("Close port") }
        }
    }
}

@Composable
fun ChipInfoCard(info: ChipInfo) {
    SectionCard(info.description, subtitle = info.features.joinToString(" · "), collapsible = true) {
        info.chipRevision?.let { KeyValue("Revision", "v${it / 100}.${it % 100}") }
        info.macs.forEach { (k, v) -> KeyValue(k.replace('_', ' '), v, mono = true) }
        info.flash?.let { f ->
            KeyValue("Flash", "${f.sizeName ?: "unknown size"} · mfr 0x%02x dev 0x%04x".format(f.vendorId, f.deviceId), mono = true)
        }
        info.crystalMhz?.let { KeyValue("Crystal", "$it MHz") }
        KeyValue("Interface", info.usbMode ?: "USB-UART bridge")
        info.flashVoltage?.let { KeyValue("Flash voltage", it) }
        if (info.secureBoot != null || info.flashEncryption != null) {
            KeyValue("Secure boot", if (info.secureBoot == true) "ENABLED" else "disabled")
            KeyValue("Flash encryption", if (info.flashEncryption == true) "ENABLED" else "disabled")
        }
        if (info.secureDownloadMode) KeyValue("Download mode", "SECURE (restricted)")
        info.securityInfo?.flagNames?.takeIf { it.isNotEmpty() }?.let { KeyValue("Security flags", it.joinToString(", ")) }
        KeyValue("Loader", if (info.stubRunning) "flasher stub" else "ROM bootloader")
    }
}
