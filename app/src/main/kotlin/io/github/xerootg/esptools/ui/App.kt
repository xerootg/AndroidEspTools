package io.github.xerootg.esptools.ui

import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.xerootg.esptools.crash.CrashReporter
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.LogLevel
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.screens.DeviceScreen
import io.github.xerootg.esptools.ui.screens.FlashScreen
import io.github.xerootg.esptools.ui.screens.MonitorScreen
import io.github.xerootg.esptools.ui.screens.SettingsScreen
import io.github.xerootg.esptools.ui.screens.ToolsScreen

enum class Dest(val route: String, val label: String) {
    Device("device", "Device"), Flash("flash", "Flash"), Tools("tools", "Tools"), Monitor("monitor", "Monitor"), Settings("settings", "Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EspToolsRoot(session: DeviceSession, settings: Settings) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Dest.Device.route
    val snackbar = remember { SnackbarHostState() }
    var showLog by remember { mutableStateOf(false) }
    val busy by session.busy.collectAsStateWithLifecycle()
    val logLines by session.log.lines.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var pendingCrash by remember { mutableStateOf(CrashReporter.pendingReports(context).firstOrNull()) }
    pendingCrash?.let { file ->
        CrashReportFileDialog(file, onDismiss = { pendingCrash = null }, onDelete = { CrashReporter.delete(context, file) })
    }

    LaunchedEffect(Unit) {
        session.messages.collect { m -> snackbar.showSnackbar(m.text, duration = if (m.isError) SnackbarDuration.Long else SnackbarDuration.Short) }
    }
    // Keep the screen on while an operation runs.
    val activity = context as? ComponentActivity
    DisposableEffect(busy != null, prefs.keepScreenOn) {
        if (busy != null && prefs.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Dest.entries.firstOrNull { it.route == current }?.label ?: "ESP Tools") },
                actions = {
                    val warnings = logLines.count { it.level == LogLevel.WARN || it.level == LogLevel.ERROR }
                    IconButton(onClick = { showLog = true }) {
                        BadgedBox(badge = { if (warnings > 0) Badge { Text(warnings.toString()) } }) {
                            Icon(Icons.Outlined.Article, contentDescription = "Log")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Dest.entries.forEach { d ->
                    val icon = when (d) {
                        Dest.Device -> Icons.Default.Memory; Dest.Flash -> Icons.Default.Download; Dest.Tools -> Icons.Default.Build
                        Dest.Monitor -> Icons.Default.Terminal; Dest.Settings -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = current == d.route,
                        onClick = { if (current != d.route) nav.navigate(d.route) { popUpTo(Dest.Device.route) { saveState = true }; launchSingleTop = true; restoreState = true } },
                        icon = { Icon(icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        NavHost(nav, startDestination = Dest.Device.route, modifier = Modifier.padding(padding)) {
            composable(Dest.Device.route) { DeviceScreen(session, settings, onNavigate = { nav.navigate(it.route) { launchSingleTop = true } }) }
            composable(Dest.Flash.route) { FlashScreen(session, settings) }
            composable(Dest.Tools.route) { ToolsScreen(session, settings) }
            composable(Dest.Monitor.route) { MonitorScreen(session, settings) }
            composable(Dest.Settings.route) { SettingsScreen(settings) }
        }
    }

    busy?.let { b ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(b.title) },
            text = {
                Column {
                    if (b.progress != null) {
                        LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("${(b.progress * 100).toInt()}%  ${b.detail}", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Row { CircularProgressIndicator(Modifier.padding(end = 12.dp)); Text(b.detail.ifEmpty { "Working…" }) }
                    }
                    Spacer(Modifier.height(8.dp))
                    val last = logLines.lastOrNull()
                    if (last != null) Text(last.text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 3)
                }
            },
            confirmButton = {},
            dismissButton = { if (b.cancellable) TextButton(onClick = { session.cancelOperation() }) { Text("Cancel") } },
        )
    }

    if (showLog) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val clipboard = LocalClipboardManager.current
        ModalBottomSheet(onDismissRequest = { showLog = false }, sheetState = sheet) {
            Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Log", style = MaterialTheme.typography.titleLarge)
                    Row {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(session.log.dump())) }) { Text("Copy") }
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, session.log.dump())
                            context.startActivity(Intent.createChooser(send, "Share log"))
                        }) { Text("Share") }
                        TextButton(onClick = { session.log.clear() }) { Text("Clear") }
                    }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(logLines.size) { if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1) }
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(logLines) { line ->
                        val color = when (line.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> Color(0xFFF59E0B)
                            LogLevel.NOTE -> MaterialTheme.colorScheme.primary
                            LogLevel.TRACE -> MaterialTheme.colorScheme.onSurfaceVariant
                            LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(line.format(), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = color, lineHeight = 14.sp)
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
