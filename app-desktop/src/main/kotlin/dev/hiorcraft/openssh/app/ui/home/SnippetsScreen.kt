package dev.hiorcraft.openssh.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.data.Snippet
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.components.Card
import dev.hiorcraft.openssh.app.ui.components.EmptyState
import dev.hiorcraft.openssh.app.ui.components.FormField
import dev.hiorcraft.openssh.app.ui.components.SearchField
import dev.hiorcraft.openssh.app.ui.components.SectionHeader
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.components.copyToClipboard
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors

@Composable
fun SnippetsScreen(app: AppState) {
    val snippets by app.snippets.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Snippet?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Snippet?>(null) }
    val filtered = snippets.filter { query.isBlank() || it.name.contains(query, true) || it.command.contains(query, true) || it.description.contains(query, true) }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        SectionHeader("Snippets", "Gespeicherte Befehle für offene Terminals") {
            SearchField(query, { query = it }, "Suchen…", Modifier.width(260.dp))
            Button(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Neues Snippet")
            }
        }
        if (snippets.isEmpty()) {
            EmptyState(Icons.Filled.Code, "Keine Snippets", "Speichere häufig genutzte Befehle wie `docker ps` oder `tail -f /var/log/syslog`.") {
                Button(onClick = { creating = true }) { Text("Snippet anlegen") }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(filtered.sortedBy { it.name.lowercase() }, key = { it.id }) { snippet ->
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snippet.name, style = MaterialTheme.typography.titleMedium)
                                if (snippet.description.isNotBlank()) Text(snippet.description, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                                Spacer(Modifier.height(6.dp))
                                Text(snippet.command, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                            RunSnippetButton(app, snippet)
                            ToolButton(Icons.Filled.ContentCopy, "Kopieren") { copyToClipboard(snippet.command); app.notify("Befehl kopiert") }
                            ToolButton(Icons.Filled.Edit, "Bearbeiten") { editing = snippet }
                            ToolButton(Icons.Filled.Delete, "Löschen") { deleting = snippet }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        SnippetEditor(initial = editing, onSave = { app.snippets.upsert(it) }, onDismiss = { creating = false; editing = null })
    }
    deleting?.let { s ->
        ConfirmDialog("Snippet löschen?", "„${s.name}“ wird entfernt.", onConfirm = { app.snippets.delete(s.id) }, onDismiss = { deleting = null })
    }
}

/** Startet ein Snippet in einem wählbaren offenen Terminal. */
@Composable
fun RunSnippetButton(app: AppState, snippet: Snippet) {
    var menu by remember { mutableStateOf(false) }
    val targets = app.terminalTargets()
    Box {
        ToolButton(Icons.Filled.PlayArrow, "In Terminal ausführen", tint = MaterialTheme.colorScheme.primary) {
            when (targets.size) {
                0 -> app.notify("Öffne zuerst ein Terminal")
                1 -> app.sendSnippet(snippet, targets.first())
                else -> menu = true
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            targets.forEach { tab ->
                DropdownMenuItem(text = { Text(tab.title) }, onClick = { menu = false; app.sendSnippet(snippet, tab) })
            }
        }
    }
}

@Composable
private fun SnippetEditor(initial: Snippet?, onSave: (Snippet) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var command by remember { mutableStateOf(initial?.command ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var run by remember { mutableStateOf(initial?.runImmediately ?: true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Neues Snippet" else "Snippet bearbeiten") },
        text = {
            Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField("Name", name, { name = it })
                FormField("Befehl", command, { command = it }, singleLine = false, minLines = 4)
                FormField("Beschreibung", description, { description = it })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(run, { run = it })
                    Spacer(Modifier.width(10.dp))
                    Text(if (run) "Sofort ausführen (mit Enter)" else "Nur einfügen")
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && command.isNotBlank(), onClick = {
                onSave((initial ?: Snippet(name = "", command = "")).copy(name = name.trim(), command = command.trimEnd(), description = description.trim(), runImmediately = run))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
