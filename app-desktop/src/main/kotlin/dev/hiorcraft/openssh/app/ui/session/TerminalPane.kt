package dev.hiorcraft.openssh.app.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.state.TabStatus
import dev.hiorcraft.openssh.app.ui.components.FormField
import dev.hiorcraft.openssh.app.ui.components.StatusDot
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.ssh.PortForward
import dev.hiorcraft.openssh.terminal.TerminalController

/** Bettet das JediTerm-Widget ein. */
@Composable
fun TerminalView(controller: TerminalController, modifier: Modifier = Modifier) {
    val background = remember(controller) {
        val bg = controller.widget.terminalPanel.background
        Color(bg.red, bg.green, bg.blue)
    }
    DisposableEffect(controller) {
        controller.start()
        controller.focus()
        onDispose { }
    }
    Box(modifier.background(background).padding(start = 6.dp, top = 4.dp)) {
        SwingPanel(factory = { controller.widget }, modifier = Modifier.fillMaxSize())
    }
}

/** Kopfleiste über einem Terminal: Status, Snippets, Tunnel, Neu verbinden. */
@Composable
fun TerminalToolbar(
    app: AppState,
    title: String,
    subtitle: String,
    status: TabStatus,
    onSnippet: (String, Boolean) -> Unit,
    onRestart: (() -> Unit)?,
    forwards: List<PortForward>?,
    onAddForward: ((Int, String, Int) -> Unit)?,
    onRemoveForward: ((PortForward) -> Unit)?,
) {
    val snippets by app.snippets.items.collectAsState()
    var snippetMenu by remember { mutableStateOf(false) }
    var forwardDialog by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusDot(
            when (status) {
                TabStatus.Ready -> extraColors.success
                TabStatus.Connecting -> extraColors.warning
                else -> MaterialTheme.colorScheme.error
            },
        )
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (forwards != null && forwards.isNotEmpty()) {
            Text("${forwards.size} Tunnel", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Box {
            ToolButton(Icons.Filled.Code, "Snippet einfügen", enabled = status == TabStatus.Ready) { snippetMenu = true }
            DropdownMenu(snippetMenu, { snippetMenu = false }) {
                if (snippets.isEmpty()) DropdownMenuItem(text = { Text("Keine Snippets gespeichert") }, onClick = { snippetMenu = false }, enabled = false)
                snippets.sortedBy { it.name.lowercase() }.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.name)
                                Text(s.command, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        onClick = { snippetMenu = false; onSnippet(s.command, s.runImmediately) },
                    )
                }
            }
        }
        if (onAddForward != null) ToolButton(Icons.Filled.SettingsEthernet, "Port-Weiterleitungen") { forwardDialog = true }
        if (onRestart != null) ToolButton(Icons.Filled.Refresh, "Neu verbinden", onClick = onRestart)
    }
    HorizontalDivider(color = extraColors.divider)

    if (forwardDialog && onAddForward != null && onRemoveForward != null) {
        PortForwardDialog(forwards.orEmpty(), onAddForward, onRemoveForward) { forwardDialog = false }
    }
}

@Composable
private fun PortForwardDialog(
    forwards: List<PortForward>,
    onAdd: (Int, String, Int) -> Unit,
    onRemove: (PortForward) -> Unit,
    onDismiss: () -> Unit,
) {
    var local by remember { mutableStateOf("8080") }
    var remoteHost by remember { mutableStateOf("127.0.0.1") }
    var remotePort by remember { mutableStateOf("80") }
    val lp = local.toIntOrNull()
    val rp = remotePort.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Local Port Forwarding") },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Verbindungen auf 127.0.0.1:<lokaler Port> werden über SSH an Host:Port auf der Server-Seite weitergeleitet. Port 0 wählt einen freien Port.", style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FormField("Lokaler Port", local, { local = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f))
                    Text("→")
                    FormField("Ziel-Host", remoteHost, { remoteHost = it.trim() }, Modifier.weight(2f))
                    FormField("Ziel-Port", remotePort, { remotePort = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f))
                }
                Button(enabled = lp != null && lp in 0..65535 && rp != null && rp in 1..65535 && remoteHost.isNotBlank(), onClick = { onAdd(lp!!, remoteHost, rp!!) }) {
                    Text("Tunnel starten")
                }
                if (forwards.isNotEmpty()) {
                    HorizontalDivider()
                    forwards.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (f.isRunning) extraColors.success else MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(f.toString(), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            ToolButton(Icons.Filled.Close, "Beenden") { onRemove(f) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

/** Platzhalter-Inhalt während des Verbindens oder bei Fehlern. */
@Composable
fun ConnectionStateView(status: TabStatus, what: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        when (status) {
            TabStatus.Connecting -> {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(12.dp))
                Text("Verbinde mit $what …", color = extraColors.subtle)
            }
            is TabStatus.Failed -> {
                Text("Verbindung fehlgeschlagen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(status.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 48.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Erneut versuchen") }
            }
            TabStatus.Closed -> {
                Text("Sitzung beendet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onRetry) { Text("Neu verbinden") }
            }
            TabStatus.Ready -> Unit
        }
    }
}
