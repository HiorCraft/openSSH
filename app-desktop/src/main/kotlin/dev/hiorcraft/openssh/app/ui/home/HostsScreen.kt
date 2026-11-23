package dev.hiorcraft.openssh.app.ui.home

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.hiorcraft.openssh.app.data.AuthType
import dev.hiorcraft.openssh.app.data.HostEntry
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.components.Avatar
import dev.hiorcraft.openssh.app.ui.components.Badge
import dev.hiorcraft.openssh.app.ui.components.Card
import dev.hiorcraft.openssh.app.ui.components.Dropdown
import dev.hiorcraft.openssh.app.ui.components.EmptyState
import dev.hiorcraft.openssh.app.ui.components.FormField
import dev.hiorcraft.openssh.app.ui.components.PasswordField
import dev.hiorcraft.openssh.app.ui.components.SearchField
import dev.hiorcraft.openssh.app.ui.components.SectionHeader
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val UNGROUPED = "Ohne Gruppe"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostsScreen(app: AppState) {
    val hosts by app.hosts.items.collectAsState()
    var query by remember { mutableStateOf("") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<HostEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<HostEntry?>(null) }
    val collapsed = remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()

    val groups = hosts.map { it.group.ifBlank { UNGROUPED } }.distinct().sortedWith(compareBy<String> { it == UNGROUPED }.thenBy { it.lowercase() })
    val filtered = hosts.filter { h ->
        (groupFilter == null || h.group.ifBlank { UNGROUPED } == groupFilter) &&
            (query.isBlank() || listOf(h.label, h.host, h.username, h.group, h.notes).any { it.contains(query, ignoreCase = true) })
    }

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        SectionHeader("Hosts", "${hosts.size} gespeicherte Verbindungen") {
            SearchField(query, { query = it }, "Suchen…", Modifier.width(280.dp))
            Button(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Neuer Host")
            }
        }
        if (groups.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                FilterChip(selected = groupFilter == null, onClick = { groupFilter = null }, label = { Text("Alle") })
                groups.forEach { g ->
                    FilterChip(selected = groupFilter == g, onClick = { groupFilter = if (groupFilter == g) null else g }, label = { Text(g) })
                }
            }
        }

        if (hosts.isEmpty()) {
            EmptyState(Icons.Filled.Dns, "Noch keine Hosts", "Lege deinen ersten Server an, um dich per SSH oder SFTP zu verbinden.") {
                Button(onClick = { creating = true }) { Text("Host anlegen") }
            }
        } else if (filtered.isEmpty()) {
            EmptyState(Icons.Filled.Dns, "Keine Treffer", "Kein Host passt zu „$query“.")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(300.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                filtered.groupBy { it.group.ifBlank { UNGROUPED } }
                    .toSortedMap(compareBy<String> { it == UNGROUPED }.thenBy { it.lowercase() })
                    .forEach { (group, items) ->
                        item(span = { GridItemSpan(maxLineSpan) }, key = "group-$group") {
                            val isCollapsed = group in collapsed.value
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    collapsed.value = if (isCollapsed) collapsed.value - group else collapsed.value + group
                                }.padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (isCollapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore, null, Modifier.size(18.dp), tint = extraColors.subtle)
                                Spacer(Modifier.width(4.dp))
                                Text(group, style = MaterialTheme.typography.titleSmall, color = extraColors.subtle)
                                Spacer(Modifier.width(8.dp))
                                Text("${items.size}", style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
                            }
                        }
                        if (group !in collapsed.value) {
                            items(items.sortedBy { it.label.lowercase() }, key = { it.id }) { host ->
                                HostCard(
                                    host = host,
                                    keyName = app.keys.get(host.keyId)?.name,
                                    onConnect = { app.openTerminal(host) },
                                    onSftp = { app.openSftp(host) },
                                    onSplit = { app.openSplit(host) },
                                    onEdit = { editing = host },
                                    onDuplicate = { scope.launch { app.saveHost(host.copy(id = dev.hiorcraft.openssh.app.data.newId(), label = host.label + " (Kopie)", lastConnected = null), null) } },
                                    onForgetHostKey = { app.forgetHostKey(host) },
                                    onDelete = { deleting = host },
                                )
                            }
                        }
                    }
            }
        }
    }

    if (creating || editing != null) {
        HostEditorDialog(
            app = app,
            initial = editing,
            knownGroups = groups.filter { it != UNGROUPED },
            onDismiss = { creating = false; editing = null },
        )
    }
    deleting?.let { host ->
        ConfirmDialog(
            title = "Host löschen?",
            message = "„${host.label}“ und das gespeicherte Passwort werden entfernt.",
            onConfirm = { scope.launch { app.deleteHost(host) } },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun HostCard(
    host: HostEntry,
    keyName: String?,
    onConnect: () -> Unit,
    onSftp: () -> Unit,
    onSplit: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onForgetHostKey: () -> Unit,
    onDelete: () -> Unit,
) {
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Terminal öffnen", onConnect),
            ContextMenuItem("SFTP öffnen", onSftp),
            ContextMenuItem("Terminal + SFTP", onSplit),
            ContextMenuItem("Bearbeiten", onEdit),
            ContextMenuItem("Duplizieren", onDuplicate),
            ContextMenuItem("Host-Schlüssel vergessen", onForgetHostKey),
            ContextMenuItem("Löschen", onDelete),
        )
    }) {
        Card(onClick = onConnect) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(host.label)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(host.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(host.address, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ToolButton(Icons.Filled.Folder, "SFTP", onClick = onSftp)
                ToolButton(Icons.Filled.VerticalSplit, "Terminal + SFTP", onClick = onSplit)
                ToolButton(Icons.Filled.Edit, "Bearbeiten", onClick = onEdit)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(host.authType.label)
                if (keyName != null && host.authType.usesKey) Badge(keyName)
                Spacer(Modifier.weight(1f))
                host.lastConnected?.let {
                    Text(
                        "Zuletzt: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = extraColors.subtle,
                    )
                }
                Icon(Icons.Filled.Terminal, null, Modifier.size(14.dp), tint = extraColors.subtle)
            }
        }
    }
}

@Composable
private fun HostEditorDialog(app: AppState, initial: HostEntry?, knownGroups: List<String>, onDismiss: () -> Unit) {
    val keys by app.keys.items.collectAsState()
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var group by remember { mutableStateOf(initial?.group ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember { mutableStateOf(initial?.username ?: "root") }
    var authType by remember { mutableStateOf(initial?.authType ?: AuthType.PASSWORD) }
    var keyId by remember { mutableStateOf(initial?.keyId ?: keys.firstOrNull()?.id) }
    var password by remember { mutableStateOf("") }
    var hasStoredPassword by remember { mutableStateOf(false) }
    var startup by remember { mutableStateOf(initial?.startupCommand ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }

    LaunchedEffect(initial) { if (initial != null) hasStoredPassword = app.hasHostPassword(initial) }

    val portNumber = port.toIntOrNull()
    val valid = host.isNotBlank() && user.isNotBlank() && portNumber != null && portNumber in 1..65535 &&
        (!authType.usesKey || authType == AuthType.KEY_THEN_PASSWORD || keyId != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Neuer Host" else "Host bearbeiten") },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormField("Name", label, { label = it }, Modifier.weight(1f), placeholder = host.ifBlank { "Mein Server" })
                    FormField("Gruppe", group, { group = it }, Modifier.weight(1f), placeholder = "z.B. Produktion")
                }
                if (knownGroups.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        knownGroups.take(6).forEach { g -> FilterChip(selected = g == group, onClick = { group = g }, label = { Text(g) }) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FormField("Host / IP", host, { host = it.trim() }, Modifier.weight(3f), placeholder = "server.example.org")
                    FormField("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), isError = portNumber == null || portNumber !in 1..65535)
                }
                FormField("Benutzer", user, { user = it.trim() })
                Dropdown("Anmeldung", AuthType.entries, authType, { authType = it }, display = { it.label })
                if (authType.usesKey) {
                    if (keys.isEmpty()) {
                        Text("Noch keine Schlüssel importiert – unter „Schlüssel“ hinzufügen.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Dropdown("Schlüssel", keys.map { it.id }, keyId ?: keys.first().id, { keyId = it }, display = { id ->
                            keys.firstOrNull { it.id == id }?.let { "${it.name} · ${it.algorithm}" } ?: "–"
                        })
                    }
                }
                if (authType.usesPassword) {
                    PasswordField(
                        "Passwort",
                        password,
                        { password = it },
                        supporting = when {
                            hasStoredPassword && password.isEmpty() -> "Gespeichert – leer lassen, um es zu behalten"
                            else -> "Leer lassen, um beim Verbinden gefragt zu werden. Gespeichert wird im Windows-Tresor."
                        },
                    )
                }
                FormField("Befehl nach dem Login (optional)", startup, { startup = it }, placeholder = "z.B. cd /var/www && ls")
                FormField("Notizen", notes, { notes = it }, singleLine = false, minLines = 2)
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val entry = (initial ?: HostEntry(label = "", host = "", username = "")).copy(
                    label = label.ifBlank { host },
                    group = group.trim(),
                    host = host,
                    port = portNumber ?: 22,
                    username = user,
                    authType = authType,
                    keyId = if (authType.usesKey) keyId else null,
                    startupCommand = startup.trim(),
                    notes = notes,
                )
                scope.launch {
                    app.saveHost(entry, password.takeIf { it.isNotEmpty() })
                    onDismiss()
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
