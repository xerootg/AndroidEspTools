package io.github.xerootg.esptools.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.xerootg.esploader.ResetAfter
import io.github.xerootg.esploader.WriteFlashOptions
import io.github.xerootg.esploader.chips.Chips
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.HexField
import io.github.xerootg.esptools.ui.OptionDropdown
import io.github.xerootg.esptools.ui.SectionCard
import io.github.xerootg.esptools.ui.SwitchRow

private const val DEFAULT_RELEASE_URL = "https://github.com/"

@Composable
fun FlashScreen(session: DeviceSession, settings: Settings, vm: FlashViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val pending by vm.pendingBundle.collectAsStateWithLifecycle()
    val importStatus by vm.importStatus.collectAsStateWithLifecycle()
    val release by vm.releaseAssets.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val busy by session.busy.collectAsStateWithLifecycle()
    val prefs by settings.state.collectAsStateWithLifecycle()
    val info = session.chipInfo
    val chip = info?.let { Chips.byName(it.chipName) }
    LaunchedEffect(chip) { chip?.let { vm.bootloaderOffset = it.bootloaderFlashOffset } }

    var urlText by rememberSaveable { mutableStateOf("") }
    var releaseText by rememberSaveable { mutableStateOf(DEFAULT_RELEASE_URL) }
    var showUrl by rememberSaveable { mutableStateOf(false) }
    var showRelease by rememberSaveable { mutableStateOf(false) }
    var confirmFlash by rememberSaveable { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.addFiles(uris) }

    LaunchedEffect(error) { error?.let { session.post(it, true); vm.clearError() } }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        item {
            SectionCard("Images", subtitle = if (info != null) "Target: ${info.description}" else "Not connected - connect on the Device tab before flashing") {
                if (entries.isEmpty()) Text("Add .bin files, a build zip (ESP-IDF flasher_args.json or ESP Web Tools manifest), a URL, or pick a GitHub release.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { e ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            HexField("Offset", e.offsetText, { vm.setOffset(e.id, it) }, modifier = Modifier.width(130.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.file.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                                Text(e.file.sizeText + (e.note?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.remove(e.id) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add files / zip") }
                    OutlinedButton(onClick = { showUrl = true }) { Text("From URL") }
                    OutlinedButton(onClick = { showRelease = true }) { Text("GitHub release") }
                    if (entries.isNotEmpty()) TextButton(onClick = { vm.clear() }) { Text("Clear") }
                }
                importStatus?.let { Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp)); Spacer(Modifier.width(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) } }
            }
        }
        item {
            SectionCard("Flash settings", collapsible = true, initiallyExpanded = false, subtitle = "Header patching for the bootloader image; 'keep' leaves the image untouched") {
                val modes = listOf("keep", "qio", "qout", "dio", "dout")
                val sizes = listOf("keep", "detect") + (chip?.flashSizes?.keys?.toList() ?: listOf("1MB", "2MB", "4MB", "8MB", "16MB", "32MB"))
                val freqs = listOf("keep") + (chip?.flashFrequency?.keys?.toList() ?: listOf("80m", "40m", "26m", "20m"))
                OptionDropdown("Flash mode", modes, options.flashMode, { v -> vm.updateOptions { it.copy(flashMode = v) } })
                Spacer(Modifier.height(8.dp))
                OptionDropdown("Flash size", sizes, if (options.flashSize in sizes) options.flashSize else "keep", { v -> vm.updateOptions { it.copy(flashSize = v) } })
                Spacer(Modifier.height(8.dp))
                OptionDropdown("Flash frequency", freqs, if (options.flashFreq in freqs) options.flashFreq else "keep", { v -> vm.updateOptions { it.copy(flashFreq = v) } })
                SwitchRow("Erase entire flash first", options.eraseAll, { v -> vm.updateOptions { it.copy(eraseAll = v) } }, description = "Wipes NVS, data partitions and any OTA state")
                SwitchRow("Force", options.force, { v -> vm.updateOptions { it.copy(force = v) } }, description = "Skip chip/revision checks and security safeguards. Can brick a device with secure boot.")
                SwitchRow("Compress", prefs.compress, { v -> settings.update { it.copy(compress = v) } })
                SwitchRow("Verify (MD5)", prefs.verify, { v -> settings.update { it.copy(verify = v) } })
                Spacer(Modifier.height(8.dp))
                OptionDropdown("After flashing", ResetAfter.entries, prefs.resetAfter, { v -> settings.update { it.copy(resetAfter = v) } }, display = ::resetAfterLabel)
            }
        }
        item {
            val total = entries.sumOf { it.file.data.size.toLong() }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = entries.isNotEmpty() && info != null && busy == null,
                onClick = { confirmFlash = true },
            ) { Text(if (total > 0) "Flash ${entries.size} image(s) · ${"%.1f".format(total / 1024.0)} KB" else "Flash") }
            if (info == null) Text("Connect to a device first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (confirmFlash) {
        val parts = runCatching { vm.buildParts() }
        AlertDialog(
            onDismissRequest = { confirmFlash = false },
            title = { Text("Flash ${info?.chipName ?: "device"}?") },
            text = {
                Column {
                    parts.onSuccess { ps -> ps.forEach { Text("0x%08x  %s (%d bytes)".format(it.address, it.name, it.data.size), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                        .onFailure { Text(it.message ?: "Invalid", color = MaterialTheme.colorScheme.error) }
                    if (options.eraseAll) Text("\nThe whole flash will be erased first.", color = MaterialTheme.colorScheme.error)
                    vm.manifest?.chipFamily?.let { fam -> if (info != null && !fam.equals(info.chipName, true)) Text("\nManifest targets $fam but the connected chip is ${info.chipName}.", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = parts.isSuccess, onClick = {
                    confirmFlash = false
                    val ps = parts.getOrThrow()
                    val wo = WriteFlashOptions(options.flashFreq, options.flashMode, options.flashSize, compress = prefs.compress, eraseAll = options.eraseAll, force = options.force, verify = prefs.verify)
                    val after = prefs.resetAfter
                    session.launchOperation("Flashing", onDone = {
                        session.post("Flashed ${ps.size} image(s) successfully")
                        if (after != ResetAfter.NO_RESET_STUB) session.markLoaderLost("Flashing done; chip was reset. Reconnect to run more operations.")
                    }) { dev, progress ->
                        dev.writeFlash(ps, wo, progress)
                        dev.reset(after)
                    }
                }) { Text("Flash") }
            },
            dismissButton = { TextButton(onClick = { confirmFlash = false }) { Text("Cancel") } },
        )
    }

    if (showUrl) {
        AlertDialog(
            onDismissRequest = { showUrl = false },
            title = { Text("Import from URL") },
            text = {
                Column {
                    Text("A .bin, a build .zip, or an ESP Web Tools manifest.json (its parts are downloaded relative to the manifest).", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = urlText, onValueChange = { urlText = it }, label = { Text("https://…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = urlText.startsWith("http"), onClick = { showUrl = false; vm.importUrl(urlText) }) { Text("Download") } },
            dismissButton = { TextButton(onClick = { showUrl = false }) { Text("Cancel") } },
        )
    }

    if (showRelease) {
        AlertDialog(
            onDismissRequest = { showRelease = false },
            title = { Text("GitHub release") },
            text = {
                Column {
                    Text("Release page URL (…/releases/tag/X or …/releases/latest). Pre-releases are included when no tag is given.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = releaseText, onValueChange = { releaseText = it }, label = { Text("https://github.com/owner/repo/releases/latest") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showRelease = false; vm.fetchRelease(releaseText) }) { Text("Fetch assets") } },
            dismissButton = { TextButton(onClick = { showRelease = false }) { Text("Cancel") } },
        )
    }

    release?.let { (title, assets) ->
        AlertDialog(
            onDismissRequest = { vm.dismissRelease() },
            title = { Text(title) },
            text = {
                Column {
                    if (assets.isEmpty()) Text("No assets in this release.")
                    assets.filter { it.name.endsWith(".zip") || it.name.endsWith(".bin") || it.name.endsWith(".json") }.forEach { a ->
                        ListItem(
                            headlineContent = { Text(a.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
                            supportingContent = { Text("%.1f KB".format(a.size / 1024.0)) },
                            modifier = Modifier.fillMaxWidth(),
                            trailingContent = { TextButton(onClick = { vm.dismissRelease(); vm.importUrl(a.url) }) { Text("Get") } },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissRelease() }) { Text("Close") } },
        )
    }

    pending?.let { bundle ->
        val available = BundleMode.entries.filter { m ->
            when (m) {
                BundleMode.COMPONENTS, BundleMode.APP_ONLY -> bundle.manifests.isNotEmpty()
                BundleMode.MERGED -> bundle.mergedImage != null
                BundleMode.ALL_BINS -> bundle.binFiles.isNotEmpty()
            }
        }
        var mode by rememberSaveable(bundle.name) { mutableStateOf(available.firstOrNull() ?: BundleMode.ALL_BINS) }
        AlertDialog(
            onDismissRequest = { vm.dismissBundle() },
            title = { Text(bundle.name) },
            text = {
                Column {
                    val m = bundle.manifests.firstOrNull()
                    if (m != null) Text("Manifest: ${m.source.name.lowercase().replace('_', ' ')} · chip ${m.chipFamily ?: "?"} · ${m.flashMode ?: "-"}/${m.flashSize ?: "-"}/${m.flashFreq ?: "-"}", style = MaterialTheme.typography.bodySmall)
                    Text("${bundle.files.size} files", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    available.forEach { opt ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == opt, onClick = { mode = opt })
                            Column { Text(opt.label); Text(opt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.applyBundle(bundle, mode, info?.chipName) }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { vm.dismissBundle() }) { Text("Cancel") } },
        )
    }
}
