package dev.hiorcraft.openssh.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import dev.hiorcraft.openssh.app.data.DbEntry
import dev.hiorcraft.openssh.app.data.DbKind
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.FilePickers
import dev.hiorcraft.openssh.app.ui.components.Avatar
import dev.hiorcraft.openssh.app.ui.components.Badge
import dev.hiorcraft.openssh.app.ui.components.Card
import dev.hiorcraft.openssh.app.ui.components.Dropdown
import dev.hiorcraft.openssh.app.ui.components.EmptyState
import dev.hiorcraft.openssh.app.ui.components.FormField
import dev.hiorcraft.openssh.app.ui.components.PasswordField
import dev.hiorcraft.openssh.app.ui.components.SectionHeader
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import kotlinx.coroutines.launch

@Composable
fun DatabasesScreen(app: AppState) {
    val dbs by app.databases.items.collectAsState()
    val hosts by app.hosts.items.collectAsState()
    var editing by remember { mutableStateOf<DbEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DbEntry?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(28.dp)) {
        SectionHeader("Datenbanken", "MariaDB, PostgreSQL, SQLite und Redis – optional über SSH-Tunnel") {
            Button(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Neue Verbindung")
            }
        }
        if (dbs.isEmpty()) {
            EmptyState(Icons.Filled.Storage, "Keine Datenbanken", "Verbinde dich über einen SSH-Host mit Datenbanken, die nur lokal auf dem Server lauschen.") {
                Button(onClick = { creating = true }) { Text("Verbindung anlegen") }
            }
        } else {
            LazyVerticalGrid(GridCells.Adaptive(320.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(dbs.sortedBy { it.name.lowercase() }, key = { it.id }) { db ->
                    val tunnel = hosts.firstOrNull { it.id == db.sshHostId }
                    Card(onClick = { app.openDatabase(db) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(db.kind.name.take(2))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(db.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (db.kind == DbKind.SQLITE) db.sqlitePath else "${db.host}:${db.port}" + if (db.database.isNotBlank()) "/${db.database}" else "",
                                    style = MaterialTheme.typography.bodySmall, color = extraColors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            ToolButton(Icons.Filled.Edit, "Bearbeiten") { editing = db }
                            ToolButton(Icons.Filled.Delete, "Löschen") { deleting = db }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(db.kind.label)
                            if (tunnel != null) {
                                Icon(Icons.Filled.Link, null, Modifier.size(14.dp), tint = extraColors.subtle)
                                Text("über ${tunnel.label}", style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        DbEditorDialog(app, editing, onDismiss = { creating = false; editing = null })
    }
    deleting?.let { db ->
        ConfirmDialog("Verbindung löschen?", "„${db.name}“ und das gespeicherte Passwort werden entfernt.", onConfirm = { scope.launch { app.deleteDatabase(db) } }, onDismiss = { deleting = null })
    }
}

@Composable
private fun DbEditorDialog(app: AppState, initial: DbEntry?, onDismiss: () -> Unit) {
    val hosts by app.hosts.items.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: DbKind.MARIADB) }
    var sshHostId by remember { mutableStateOf(initial?.sshHostId) }
    var host by remember { mutableStateOf(initial?.host ?: "127.0.0.1") }
    var port by remember { mutableStateOf((initial?.port ?: kind.defaultPort).toString()) }
    var database by remember { mutableStateOf(initial?.database ?: "") }
    var user by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var sqlitePath by remember { mutableStateOf(initial?.sqlitePath ?: "") }
    var redisDb by remember { mutableStateOf((initial?.redisDb ?: 0).toString()) }
    var tls by remember { mutableStateOf(initial?.useTls ?: false) }

    val portNumber = port.toIntOrNull()
    val valid = name.isNotBlank() && when (kind) {
        DbKind.SQLITE -> sqlitePath.isNotBlank()
        else -> host.isNotBlank() && portNumber != null && portNumber in 1..65535
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Neue Datenbankverbindung" else "Verbindung bearbeiten") },
        text = {
            Column(Modifier.width(560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FormField("Name", name, { name = it }, placeholder = "z.B. Shop-DB Produktion")
                Dropdown("Typ", DbKind.entries, kind, {
                    if (port.toIntOrNull() == kind.defaultPort) port = it.defaultPort.toString()
                    kind = it
                }, display = { it.label })
                if (kind == DbKind.SQLITE) {
                    FormField("Datenbankdatei", sqlitePath, { sqlitePath = it }, trailing = {
                        IconButton(onClick = { FilePickers.openFile("SQLite-Datei wählen")?.let { sqlitePath = it.toString() } }) {
                            Icon(Icons.Filled.FolderOpen, "Durchsuchen")
                        }
                    })
                } else {
                    Dropdown(
                        "SSH-Tunnel",
                        listOf<String?>(null) + hosts.map { it.id },
                        sshHostId,
                        { sshHostId = it },
                        display = { id -> hosts.firstOrNull { it.id == id }?.let { "über ${it.label} (${it.address})" } ?: "Direkt verbinden (kein Tunnel)" },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FormField(
                            if (sshHostId != null) "Host (aus Sicht des Servers)" else "Host",
                            host, { host = it.trim() }, Modifier.weight(3f),
                        )
                        FormField("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), isError = portNumber == null)
                    }
                    if (kind == DbKind.REDIS) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            FormField("Datenbank-Index", redisDb, { redisDb = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f))
                            FormField("Benutzer (ACL, optional)", user, { user = it }, Modifier.weight(2f))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(tls, { tls = it }); Text("TLS verwenden") }
                    } else {
                        FormField("Datenbank", database, { database = it.trim() }, placeholder = if (kind == DbKind.POSTGRESQL) "postgres" else "optional")
                        FormField("Benutzer", user, { user = it.trim() })
                    }
                    PasswordField("Passwort", password, { password = it }, supporting = "Leer lassen, um ein gespeichertes Passwort zu behalten oder beim Verbinden gefragt zu werden")
                }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val entry = (initial ?: DbEntry(name = "", kind = kind)).copy(
                    name = name.trim(),
                    kind = kind,
                    sshHostId = if (kind == DbKind.SQLITE) null else sshHostId,
                    host = host,
                    port = portNumber ?: kind.defaultPort,
                    database = database,
                    username = user,
                    sqlitePath = sqlitePath,
                    redisDb = redisDb.toIntOrNull() ?: 0,
                    useTls = tls,
                )
                scope.launch {
                    app.saveDatabase(entry, password.takeIf { it.isNotEmpty() })
                    onDismiss()
                }
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
