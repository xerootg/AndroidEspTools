package io.github.xerootg.esptools.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xerootg.esploader.ResetAfter
import io.github.xerootg.esploader.ResetMode
import io.github.xerootg.esptools.BuildConfig
import io.github.xerootg.esptools.session.AppSettings
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.OptionDropdown
import io.github.xerootg.esptools.ui.SectionCard
import io.github.xerootg.esptools.ui.SwitchRow
import io.github.xerootg.esptools.crash.CrashReporter
import io.github.xerootg.esptools.ui.CrashReportFileDialog
import io.github.xerootg.esptools.ui.ReportDialog
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement as RowArrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

@Composable
fun SettingsScreen(settings: Settings) {
    val s by settings.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Connection") {
                OptionDropdown("Baud rate after sync", AppSettings.BAUD_RATES, s.baud, { v -> settings.update { it.copy(baud = v) } }, display = { "$it" })
                Spacer(Modifier.height(8.dp))
                OptionDropdown("Reset before connecting", ResetMode.entries, s.resetMode, { v -> settings.update { it.copy(resetMode = v) } }, display = ::resetModeLabel)
                Spacer(Modifier.height(8.dp))
                OptionDropdown("Connect attempts", listOf(3, 5, 7, 10, 20), s.connectAttempts, { v -> settings.update { it.copy(connectAttempts = v) } }, display = { "$it" })
                SwitchRow("Use flasher stub", s.useStub, { v -> settings.update { it.copy(useStub = v) } })
                SwitchRow("Auto-connect when a board is plugged in", s.autoConnectOnAttach, { v -> settings.update { it.copy(autoConnectOnAttach = v) } })
            }
        }
        item {
            SectionCard("Flashing") {
                OptionDropdown("After flashing", ResetAfter.entries, s.resetAfter, { v -> settings.update { it.copy(resetAfter = v) } }, display = ::resetAfterLabel)
                SwitchRow("Compress uploads (stub only)", s.compress, { v -> settings.update { it.copy(compress = v) } })
                SwitchRow("Verify with MD5 after writing", s.verify, { v -> settings.update { it.copy(verify = v) } })
            }
        }
        item {
            SectionCard("Monitor & app") {
                OptionDropdown("Monitor baud rate", AppSettings.MONITOR_BAUD_RATES, s.monitorBaud, { v -> settings.update { it.copy(monitorBaud = v) } }, display = { "$it" })
                SwitchRow("Keep screen on during operations", s.keepScreenOn, { v -> settings.update { it.copy(keepScreenOn = v) } })
                SwitchRow("Protocol trace in log", s.trace, { v -> settings.update { it.copy(trace = v) } }, description = "Logs every packet. Very verbose.")
            }
        }
        item {
            val context = LocalContext.current
            var reports by remember { mutableStateOf(CrashReporter.pendingReports(context)) }
            var openReport by remember { mutableStateOf<File?>(null) }
            var diagnostics by remember { mutableStateOf<String?>(null) }
            SectionCard("Diagnostics", subtitle = if (reports.isEmpty()) "No crash reports" else "${reports.size} crash report(s) saved") {
                Row(horizontalArrangement = RowArrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { diagnostics = CrashReporter.diagnostics(context) }) { Text("Export diagnostics") }
                    if (reports.isNotEmpty()) TextButton(onClick = { CrashReporter.deleteAll(context); reports = emptyList() }) { Text("Delete all") }
                }
                reports.forEach { f ->
                    TextButton(onClick = { openReport = f }) { Text(f.name) }
                }
            }
            openReport?.let { f -> CrashReportFileDialog(f, onDismiss = { openReport = null }, onDelete = { CrashReporter.delete(context, f); reports = CrashReporter.pendingReports(context) }) }
            diagnostics?.let { d -> ReportDialog("ESP Tools diagnostics", d, "esptools-diagnostics.txt", onDismiss = { diagnostics = null }) }
        }
        item {
            SectionCard("About") {
                Text("ESP Tools ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                Text("Flasher for Espressif chips (ESP8266, ESP32, S2, S3, C2, C3, C5, C6, C61, H2, H21, H4, P4, S31). Protocol implementation ported from esptool; flasher stubs from esp-flasher-stub (Apache-2.0/MIT).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("https://github.com/xerootg/AndroidEspTools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}
