package io.github.xerootg.esptools.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Shows a saved crash report (or any diagnostics text) with copy / share / save-as-file actions. */
@Composable
fun ReportDialog(title: String, text: String, fileName: String, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("Copy it into the chat or save it as a file and attach it.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Copy") }
                TextButton(onClick = { shareText(context, title, text) }) { Text("Share") }
                TextButton(onClick = { save.launch(fileName) }) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, subject))
}

@Composable
fun CrashReportFileDialog(file: File, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val text = remember(file) { runCatching { file.readText() }.getOrElse { "Could not read ${file.name}: $it" } }
    ReportDialog("Crash report ${file.name.removePrefix("crash-").removeSuffix(".txt")}", text, file.name, onDismiss, onDelete)
}
