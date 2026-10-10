package io.github.xerootg.esptools.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xerootg.esploader.EspException
import io.github.xerootg.esploader.FlashPart
import io.github.xerootg.esploader.ResetAfter
import io.github.xerootg.esploader.image.FirmwareImage
import io.github.xerootg.esploader.util.parseAddress
import io.github.xerootg.esploader.util.parseSize
import io.github.xerootg.esploader.util.toHexString
import io.github.xerootg.esptools.firmware.FirmwareSources
import io.github.xerootg.esptools.session.DeviceSession
import io.github.xerootg.esptools.session.Settings
import io.github.xerootg.esptools.ui.HexField
import io.github.xerootg.esptools.ui.KeyValue
import io.github.xerootg.esptools.ui.OptionDropdown
import io.github.xerootg.esptools.ui.SectionCard
import io.github.xerootg.esptools.ui.SizeField
import io.github.xerootg.esptools.ui.SwitchRow
import io.github.xerootg.esptools.ui.hex
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

@Composable
fun ToolsScreen(session: DeviceSession, settings: Settings) {
    val busy by session.busy.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val info = session.chipInfo
    val connected = info != null && busy == null
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var resultText by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Spacer(Modifier.height(4.dp)) }
        if (info == null) item { Text("Connect to a device on the Device tab to use these tools.", color = MaterialTheme.colorScheme.onSurfaceVariant) }

        // ---------------------------------------------------------------- read flash
        item {
            SectionCard("Read / dump flash", collapsible = true, subtitle = "Save a region of the SPI flash to a file") {
                var offset by rememberSaveable { mutableStateOf("0x0") }
                var size by rememberSaveable { mutableStateOf(info?.flash?.sizeName ?: "4MB") }
                val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val off = parseAddress(offset); val len = parseSize(size)
                    session.launchOperation("Reading flash", onDone = { session.post("Flash dump saved") }) { dev, progress ->
                        val out = context.contentResolver.openOutputStream(uri) ?: throw EspException("Cannot open output")
                        val md = MessageDigest.getInstance("MD5")
                        DigestOutputStream(BufferedOutputStream(out, 64 * 1024), md).use { dev.readFlash(off, len, it, progress) }
                        session.log.info("Dump MD5: ${md.digest().toHexString()}")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HexField("Offset", offset, { offset = it }, Modifier.weight(1f))
                    SizeField("Size", size, { size = it }, Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected && runCatching { parseAddress(offset); parseSize(size) }.isSuccess, onClick = {
                        save.launch("${info?.chipName?.lowercase() ?: "esp"}-${offset}-${size}.bin")
                    }) { Text("Dump to file") }
                    info?.flash?.sizeName?.let { s -> OutlinedButton(onClick = { offset = "0x0"; size = s }) { Text("Whole flash ($s)") } }
                }
                if (info?.stubRunning == false) Text("ROM-only reads run at 64 bytes per command; enable the stub for fast dumps.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---------------------------------------------------------------- erase
        item {
            SectionCard("Erase", collapsible = true, initiallyExpanded = false) {
                var force by rememberSaveable { mutableStateOf(false) }
                var offset by rememberSaveable { mutableStateOf("0x10000") }
                var size by rememberSaveable { mutableStateOf("0x100000") }
                var confirmAll by rememberSaveable { mutableStateOf(false) }
                SwitchRow("Force (ignore secure boot / flash encryption safeguards)", force, { force = it })
                Button(enabled = connected, onClick = { confirmAll = true }) { Text("Erase entire flash") }
                if (info?.stubRunning == false) Text("Full-chip erase needs the stub; use a region erase in ROM mode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HexField("Offset", offset, { offset = it }, Modifier.weight(1f))
                    SizeField("Size", size, { size = it }, Modifier.weight(1f))
                }
                OutlinedButton(modifier = Modifier.padding(top = 8.dp), enabled = connected && runCatching { parseAddress(offset); parseSize(size) }.isSuccess, onClick = {
                    val off = parseAddress(offset); val len = parseSize(size)
                    session.launchOperation("Erasing region", onDone = { session.post("Region erased") }) { dev, _ -> dev.eraseRegion(off, len, force) }
                }) { Text("Erase region") }
                if (confirmAll) AlertDialog(
                    onDismissRequest = { confirmAll = false },
                    title = { Text("Erase entire flash?") },
                    text = { Text("All firmware, configuration (NVS) and data on ${info?.description ?: "the chip"} will be destroyed.") },
                    confirmButton = { TextButton(onClick = { confirmAll = false; session.launchOperation("Erasing flash", onDone = { session.post("Flash erased") }) { dev, _ -> dev.eraseFlash(force) } }) { Text("Erase everything") } },
                    dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
                )
            }
        }

        // ---------------------------------------------------------------- verify
        item {
            SectionCard("Verify flash against file", collapsible = true, initiallyExpanded = false, subtitle = "Compares MD5 computed on the chip with the file") {
                var offset by rememberSaveable { mutableStateOf("0x10000") }
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val f = FirmwareSources.readUri(context, uri)
                        val off = parseAddress(offset)
                        session.launchOperation("Verifying", onDone = {}) { dev, _ ->
                            val r = dev.verifyFlash(listOf(FlashPart(off, f.data, f.name))).single()
                            session.log.info("${f.name} @ ${hex(off)}: expected ${r.expectedMd5}, flash ${r.actualMd5}")
                            session.post(if (r.matches) "Verified: ${f.name} matches flash at ${hex(off)}" else "MISMATCH: ${f.name} differs from flash at ${hex(off)}", !r.matches)
                        }
                    }
                }
                HexField("Offset", offset, { offset = it }, Modifier.fillMaxWidth())
                Button(modifier = Modifier.padding(top = 8.dp), enabled = connected && runCatching { parseAddress(offset) }.isSuccess, onClick = { pick.launch(arrayOf("*/*")) }) { Text("Pick file and verify") }
            }
        }

        // ---------------------------------------------------------------- image info
        item {
            SectionCard("Image info", collapsible = true, initiallyExpanded = false, subtitle = "Inspect a .bin header without a device") {
                var image by remember { mutableStateOf<Pair<String, FirmwareImage>?>(null) }
                var err by remember { mutableStateOf<String?>(null) }
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        try { val f = FirmwareSources.readUri(context, uri); image = f.name to FirmwareImage.parse(f.data); err = null }
                        catch (e: Exception) { err = e.message; image = null }
                    }
                }
                Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Pick image") }
                err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                image?.let { (name, img) ->
                    Spacer(Modifier.height(8.dp))
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    KeyValue("Chip", img.chip?.chipName ?: "ESP8266/unknown (id ${img.chipId})")
                    KeyValue("Entry point", hex(img.entrypoint), mono = true)
                    KeyValue("Flash params", "${img.flashModeName} / ${img.flashSizeName()} / ${img.flashFreqName()}")
                    if (!img.esp8266) KeyValue("Min/max rev", "v${img.minRevFull / 100}.${img.minRevFull % 100} – v${img.maxRevFull / 100}.${img.maxRevFull % 100} (legacy min ${img.minRev})")
                    KeyValue("Checksum", if (img.checksumValid) "valid (0x%02x)".format(img.checksum) else "INVALID (stored 0x%02x, calculated 0x%02x)".format(img.checksum, img.calculatedChecksum))
                    img.digestValid?.let { KeyValue("SHA-256", if (it) "valid" else "INVALID") }
                    img.appDescription?.let { a ->
                        KeyValue("Project", "${a.projectName} ${a.version}")
                        KeyValue("Built", "${a.date} ${a.time}")
                        KeyValue("ESP-IDF", a.idfVersion)
                        KeyValue("ELF SHA-256", a.elfSha256.take(16) + "…", mono = true)
                    }
                    img.bootloaderDescription?.let { b -> KeyValue("Bootloader", "ESP-IDF ${b.idfVersion} (${b.dateTime})") }
                    img.segments.forEachIndexed { i, s -> Text("seg $i: ${hex(s.addr)} ${s.size} bytes ${s.memoryType(img.chip)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        // ---------------------------------------------------------------- flash status registers
        item {
            SectionCard("Flash status registers", collapsible = true, initiallyExpanded = false) {
                var bytes by rememberSaveable { mutableStateOf(2) }
                var value by rememberSaveable { mutableStateOf("0x0") }
                var nonVolatile by rememberSaveable { mutableStateOf(false) }
                OptionDropdown("Register bytes", listOf(1, 2, 3), bytes, { bytes = it }, display = { "$it" })
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected, onClick = {
                        session.launchOperation("Reading status", cancellable = false) { dev, _ ->
                            val v = dev.readFlashStatus(bytes)
                            session.post("Flash status: 0x%0${bytes * 2}x".format(v))
                        }
                    }) { Text("Read") }
                }
                Spacer(Modifier.height(8.dp))
                HexField("New value", value, { value = it }, Modifier.fillMaxWidth())
                SwitchRow("Non-volatile write (WREN)", nonVolatile, { nonVolatile = it })
                OutlinedButton(enabled = connected && runCatching { parseAddress(value) }.isSuccess, onClick = {
                    val v = parseAddress(value)
                    session.launchOperation("Writing status", cancellable = false) { dev, _ ->
                        val after = dev.writeFlashStatus(v, bytes, nonVolatile)
                        session.post("Flash status now 0x%0${bytes * 2}x".format(after))
                    }
                }) { Text("Write status") }
            }
        }

        // ---------------------------------------------------------------- memory
        item {
            SectionCard("Memory / registers", collapsible = true, initiallyExpanded = false) {
                var addr by rememberSaveable { mutableStateOf("0x3ff5a000") }
                var wval by rememberSaveable { mutableStateOf("0x0") }
                var mask by rememberSaveable { mutableStateOf("0xffffffff") }
                var dumpSize by rememberSaveable { mutableStateOf("0x1000") }
                val saveDump = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val a = parseAddress(addr); val n = parseSize(dumpSize)
                    session.launchOperation("Dumping memory", onDone = { session.post("Memory dump saved") }) { dev, progress ->
                        val out = context.contentResolver.openOutputStream(uri) ?: throw EspException("Cannot open output")
                        BufferedOutputStream(out).use { dev.dumpMem(a, n, it, progress) }
                    }
                }
                HexField("Address", addr, { addr = it }, Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected && runCatching { parseAddress(addr) }.isSuccess, onClick = {
                        val a = parseAddress(addr)
                        session.launchOperation("Reading register", cancellable = false) { dev, _ -> session.post("${hex(a)} = ${hex(dev.readMem(a))}") }
                    }) { Text("Read 32-bit") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HexField("Value", wval, { wval = it }, Modifier.weight(1f))
                    HexField("Mask", mask, { mask = it }, Modifier.weight(1f))
                }
                OutlinedButton(modifier = Modifier.padding(top = 8.dp), enabled = connected && runCatching { parseAddress(addr); parseAddress(wval); parseAddress(mask) }.isSuccess, onClick = {
                    val a = parseAddress(addr); val v = parseAddress(wval); val m = parseAddress(mask)
                    session.launchOperation("Writing register", cancellable = false) { dev, _ -> dev.writeMem(a, v, m); session.post("Wrote ${hex(v)} to ${hex(a)}") }
                }) { Text("Write 32-bit") }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SizeField("Dump size", dumpSize, { dumpSize = it }, Modifier.weight(1f))
                    OutlinedButton(enabled = connected && runCatching { parseAddress(addr); parseSize(dumpSize) }.isSuccess, onClick = { saveDump.launch("mem-$addr.bin") }) { Text("Dump to file") }
                }
            }
        }

        // ---------------------------------------------------------------- load RAM / run / reset
        item {
            SectionCard("Run & reset", collapsible = true, initiallyExpanded = false) {
                val pickRam = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val f = FirmwareSources.readUri(context, uri)
                        session.launchOperation("Loading to RAM", onDone = { session.markLoaderLost("Image started from RAM; reconnect to use the loader again.") }) { dev, progress -> dev.loadRam(f.data, progress) }
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected, onClick = { pickRam.launch(arrayOf("*/*")) }) { Text("Load image to RAM & run") }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (mode in listOf(ResetAfter.HARD_RESET, ResetAfter.WATCHDOG_RESET, ResetAfter.SOFT_RESET, ResetAfter.NO_RESET)) {
                        OutlinedButton(enabled = connected, onClick = {
                            session.launchOperation(resetAfterLabel(mode), cancellable = false, onDone = { session.markLoaderLost("Reset issued (${resetAfterLabel(mode)}).") }) { dev, _ -> dev.reset(mode) }
                        }) { Text(resetAfterLabel(mode)) }
                    }
                }
            }
        }

        // ---------------------------------------------------------------- security / ids
        item {
            SectionCard("Chip identity & security", collapsible = true, initiallyExpanded = false) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = connected, onClick = {
                        session.launchOperation("Reading security info", cancellable = false) { dev, _ ->
                            val text = try { dev.securityInfoText() } catch (e: EspException) { "Not available: ${e.message}" }
                            resultText = "Security info" to text
                        }
                    }) { Text("Security info") }
                    OutlinedButton(enabled = connected, onClick = {
                        session.launchOperation("Reading flash ID", cancellable = false) { dev, _ ->
                            val f = dev.flashInfo(cache = false)
                            resultText = "Flash ID" to "Flash ID: 0x%06x\n%s\nSFDP: %s".format(f.flashId, dev.describeFlash(f), if (dev.loader.verifyFlashConnection()) "ok" else "not detected")
                        }
                    }) { Text("Flash ID") }
                    OutlinedButton(enabled = connected, onClick = {
                        session.launchOperation("Reading MACs", cancellable = false) { dev, _ ->
                            val macs = dev.chip.readMacs(dev.loader)
                            resultText = "MAC addresses" to macs.entries.joinToString("\n") { "${it.key}: ${io.github.xerootg.esploader.util.formatMac(it.value)}" }
                        }
                    }) { Text("MAC") }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    resultText?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { resultText = null },
            title = { Text(title) },
            text = { Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { resultText = null }) { Text("Close") } },
        )
    }
}
