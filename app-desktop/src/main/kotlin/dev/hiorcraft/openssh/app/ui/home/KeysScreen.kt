package dev.hiorcraft.openssh.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.data.KeyEntry
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.FilePickers
import dev.hiorcraft.openssh.app.ui.components.Avatar
import dev.hiorcraft.openssh.app.ui.components.Badge
import dev.hiorcraft.openssh.app.ui.components.Card
import dev.hiorcraft.openssh.app.ui.components.EmptyState
import dev.hiorcraft.openssh.app.ui.components.FormField
import dev.hiorcraft.openssh.app.ui.components.PasswordField
import dev.hiorcraft.openssh.app.ui.components.SectionHeader
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.components.copyToClipboard
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.ssh.keys.PrivateKeyFormat
import kotlinx.coroutines.launch
import java.nio.file.Files
import java.text.DateFormat
import java.util.Date
import kotlin.io.path.name

@Composable
fun KeysScreen(app: AppState) {
    val keys by app.keys.items.collectAsState()
    val hosts by app.hosts.items.collectAsState()
    var importing by remember { mutableStateOf<ImportDraft?>(null) }
    var deleting by remember { mutableStateOf<KeyEntry?>(null) }
    val scope = rememberCoroutineScope()

    fun importFromFile() {
        val file = FilePickers.openFile("Privaten Schlüssel wählen (OpenSSH, PEM oder .ppk)") ?: return
        val text = runCatching { Files.readString(file) }.getOrElse { app.notify("Datei nicht lesbar: ${it.message}"); return }
        importing = ImportDraft(file.name.substringBeforeLast('.').ifBlank { file.name }, text)
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        SectionHeader("SSH-Schlüssel", "Private Schlüssel liegen verschlüsselt im Windows-Tresor") {
            OutlinedButton(onClick = { importing = ImportDraft("", "") }) {
                Icon(Icons.Filled.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Einfügen")
            }
            Button(onClick = ::importFromFile) {
                Icon(Icons.Filled.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Datei importieren")
            }
        }
        if (keys.isEmpty()) {
            EmptyState(Icons.Filled.Key, "Keine Schlüssel", "Importiere OpenSSH-, PEM- oder PuTTY-Schlüssel (.ppk).") {
                Button(onClick = ::importFromFile) { Text("Schlüssel importieren") }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(keys.sortedBy { it.name.lowercase() }, key = { it.id }) { key ->
                    val usedBy = hosts.filter { it.keyId == key.id && it.authType.usesKey }
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(key.name)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(key.name, style = MaterialTheme.typography.titleMedium)
                                    Badge(key.algorithm)
                                    Badge(key.format)
                                    if (key.encrypted) Badge("Passphrase")
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(key.fingerprint, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                                Text(
                                    "Importiert " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(key.importedAt)) +
                                        if (usedBy.isNotEmpty()) " · verwendet von ${usedBy.joinToString { it.label }}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = extraColors.subtle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            ToolButton(Icons.Filled.ContentCopy, "Public Key kopieren (für authorized_keys)") {
                                copyToClipboard(key.publicKey + " " + key.name)
                                app.notify("Public Key kopiert")
                            }
                            if (key.encrypted) {
                                ToolButton(Icons.Filled.LockReset, "Gespeicherte Passphrase vergessen") {
                                    scope.launch { app.forgetPassphrase(key); app.notify("Passphrase entfernt – wird beim nächsten Verbinden abgefragt") }
                                }
                            }
                            ToolButton(Icons.Filled.Delete, "Löschen") { deleting = key }
                        }
                    }
                }
            }
        }
    }

    importing?.let { draft ->
        ImportKeyDialog(app, draft, onDismiss = { importing = null })
    }
    deleting?.let { key ->
        val users = hosts.filter { it.keyId == key.id }
        ConfirmDialog(
            title = "Schlüssel löschen?",
            message = "„${key.name}“ wird aus dem Tresor entfernt." +
                if (users.isNotEmpty()) " Folgende Hosts verwenden ihn: ${users.joinToString { it.label }}." else "",
            onConfirm = { scope.launch { app.deleteKey(key) } },
            onDismiss = { deleting = null },
        )
    }
}

private data class ImportDraft(val name: String, val text: String)

@Composable
private fun ImportKeyDialog(app: AppState, draft: ImportDraft, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(draft.name) }
    var text by remember { mutableStateOf(draft.text) }
    var passphrase by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val format = PrivateKeyFormat.detect(text)
    val encrypted = runCatching { PrivateKeyFormat.isEncrypted(text) }.getOrDefault(false)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schlüssel importieren") },
        text = {
            Column(Modifier.width(600.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField("Name", name, { name = it }, placeholder = "z.B. Laptop ed25519")
                FormField("Privater Schlüssel", text, { text = it; error = null }, singleLine = false, minLines = 8, supporting = if (text.isBlank()) "Inhalt der Schlüsseldatei einfügen" else "Erkannt: ${format.displayName}")
                if (encrypted) {
                    PasswordField("Passphrase", passphrase, { passphrase = it; error = null })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remember, { remember = it }); Text("Passphrase im Tresor speichern")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            Button(enabled = !busy && text.isNotBlank() && format != PrivateKeyFormat.UNKNOWN, onClick = {
                busy = true
                scope.launch {
                    try {
                        val entry = app.importKey(name, text.trim() + "\n", passphrase, remember)
                        app.notify("Schlüssel „${entry.name}“ importiert")
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
