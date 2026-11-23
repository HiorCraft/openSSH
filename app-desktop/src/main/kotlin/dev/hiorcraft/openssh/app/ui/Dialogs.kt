package dev.hiorcraft.openssh.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.HostKeyRequest
import dev.hiorcraft.openssh.app.state.SecretRequest
import dev.hiorcraft.openssh.app.ui.components.PasswordField
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.ssh.hostkeys.HostKeyCheck
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Path
import javax.swing.JFileChooser

@Composable
fun HostKeyDialog(request: HostKeyRequest) {
    val check = request.check
    val changed = check.kind == HostKeyCheck.Kind.CHANGED
    AlertDialog(
        onDismissRequest = { request.reject() },
        icon = {
            Icon(
                if (changed) Icons.Filled.Warning else Icons.Filled.GppMaybe,
                null,
                tint = if (changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = { Text(if (changed) "Host-Schlüssel hat sich geändert!" else "Unbekannter Host") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changed) {
                    Text(
                        "Der Schlüssel von ${check.host} stimmt nicht mehr mit dem gespeicherten überein. " +
                            "Das kann bedeuten, dass jemand die Verbindung abhört (Man-in-the-Middle) – oder der Server wurde neu installiert.",
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text("Die Echtheit von ${check.host} kann nicht bestätigt werden. Vergleiche den Fingerprint mit dem des Servers (z.B. `ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub`).")
                }
                Text("Schlüsseltyp: ${check.keyType}", style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(check.fingerprint, fontFamily = MonoFont, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { request.accept() },
                colors = if (changed) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(if (changed) "Neuen Schlüssel trotzdem vertrauen" else "Vertrauen und verbinden") }
        },
        dismissButton = { TextButton(onClick = { request.reject() }) { Text("Abbrechen") } },
    )
}

@Composable
fun SecretPromptDialog(request: SecretRequest) {
    var value by remember(request) { mutableStateOf("") }
    var remember by remember(request) { mutableStateOf(true) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = { request.cancel() },
        title = { Text(request.title) },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(request.message, color = extraColors.subtle)
                PasswordField(
                    request.label,
                    value,
                    { value = it },
                    Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { request.submit(value, remember); true } else false
                    },
                )
                if (request.offerRemember) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, { remember = it })
                        Text("Im Windows-Tresor speichern")
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { request.submit(value, remember) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { request.cancel() }) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Löschen",
    destructive: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, modifier = Modifier.width(420.dp)) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Ein einfacher Eingabedialog (z.B. für „Neuer Ordner“ oder „Umbenennen“). */
@Composable
fun TextInputDialog(title: String, label: String, initial: String = "", confirmLabel: String = "OK", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.width(380.dp).focusRequester(focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && value.isNotBlank()) { onConfirm(value.trim()); onDismiss(); true } else false
                },
            )
        },
        confirmButton = { Button(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()); onDismiss() }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Native Dateiauswahl von Windows. */
object FilePickers {
    fun openFiles(title: String, multiple: Boolean = true): List<Path> {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply {
            isMultipleMode = multiple
            isVisible = true
        }
        return dialog.files.orEmpty().map(File::toPath)
    }

    fun openFile(title: String): Path? = openFiles(title, multiple = false).firstOrNull()

    fun chooseDirectory(title: String, start: Path? = null): Path? {
        val chooser = JFileChooser(start?.toFile()).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }
}
