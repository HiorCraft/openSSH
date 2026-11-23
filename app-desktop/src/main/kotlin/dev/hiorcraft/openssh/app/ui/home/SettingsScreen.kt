package dev.hiorcraft.openssh.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.data.ThemeMode
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.FilePickers
import dev.hiorcraft.openssh.app.ui.components.Card
import dev.hiorcraft.openssh.app.ui.components.Dropdown
import dev.hiorcraft.openssh.app.ui.components.SectionHeader
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.terminal.TerminalSettings
import dev.hiorcraft.openssh.terminal.TerminalTheme
import java.awt.Desktop
import java.nio.file.Path
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(app: AppState) {
    val s by app.settings.collectAsState()
    val fonts = remember { TerminalSettings.availableMonospaceFonts() }
    val shells = remember { app.localShells.detect() }

    Column(Modifier.fillMaxSize().padding(28.dp).verticalScroll(rememberScrollState())) {
        SectionHeader("Einstellungen")
        Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group("Darstellung") {
                Dropdown("Farbschema", ThemeMode.entries, s.themeMode, { m -> app.updateSettings { it.copy(themeMode = m) } }, display = { it.label })
                Toggle(
                    "Mica-Hintergrund (Windows 11)",
                    if (s.themeMode == ThemeMode.POWERSHELL) "Im PowerShell-Blau ist der Hintergrund bewusst deckend" else "Durchscheinender Fensterhintergrund wie bei Windows-Apps",
                    s.mica,
                ) { v -> app.updateSettings { it.copy(mica = v) } }
            }
            Group("Terminal", "Änderungen gelten für neu geöffnete Terminals") {
                Dropdown("Terminal-Farben", TerminalTheme.all, TerminalTheme.byId(s.terminalTheme), { t -> app.updateSettings { it.copy(terminalTheme = t.id) } }, display = { it.displayName })
                Dropdown("Schriftart", fonts, s.fontFamily.ifBlank { fonts.first() }, { f -> app.updateSettings { it.copy(fontFamily = f) } })
                Text("Schriftgröße: ${s.fontSize.roundToInt()} pt", style = MaterialTheme.typography.labelMedium)
                Slider(s.fontSize, { v -> app.updateSettings { it.copy(fontSize = v.roundToInt().toFloat()) } }, valueRange = 9f..28f, steps = 18)
                Dropdown("Scrollback-Zeilen", listOf(1_000, 5_000, 10_000, 50_000, 100_000), s.scrollback, { v -> app.updateSettings { it.copy(scrollback = v) } }, display = { "%,d".format(it) })
                Toggle("Kopieren beim Markieren", null, s.copyOnSelect) { v -> app.updateSettings { it.copy(copyOnSelect = v) } }
                Dropdown("Standard-Shell (lokal)", shells.map { it.id }, app.localShells.byId(s.defaultShell).id, { id -> app.updateSettings { it.copy(defaultShell = id) } }, display = { id ->
                    shells.firstOrNull { it.id == id }?.displayName ?: id
                })
            }
            Group("Dateien & Datenbanken") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Download-Ordner", style = MaterialTheme.typography.bodyLarge)
                        Text(s.downloadDir.ifBlank { "Jedes Mal fragen" }, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                    }
                    OutlinedButton(onClick = {
                        FilePickers.chooseDirectory("Download-Ordner wählen", s.downloadDir.takeIf { it.isNotBlank() }?.let(Path::of))
                            ?.let { dir -> app.updateSettings { it.copy(downloadDir = dir.toString()) } }
                    }) { Text("Ändern…") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(enabled = s.downloadDir.isNotBlank(), onClick = { app.updateSettings { it.copy(downloadDir = "") } }) { Text("Immer fragen") }
                }
                Dropdown("Maximale Ergebniszeilen pro Abfrage", listOf(200, 1_000, 5_000, 20_000), s.maxRows, { v -> app.updateSettings { it.copy(maxRows = v) } }, display = { "%,d".format(it) })
            }
            Group("Daten") {
                Text("Hosts, Snippets und Einstellungen liegen in:", style = MaterialTheme.typography.bodyMedium)
                Text(app.paths.root.toString(), style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                Text("Passwörter und private Schlüssel speichert die App in der Windows-Anmeldeinformationsverwaltung (große Schlüssel DPAPI-verschlüsselt im Unterordner „secrets“).", style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { Desktop.getDesktop().open(app.paths.root.toFile()) } }) { Text("Ordner öffnen") }
                    OutlinedButton(onClick = {
                        runCatching {
                            if (!java.nio.file.Files.exists(app.paths.knownHosts)) java.nio.file.Files.createFile(app.paths.knownHosts)
                            Desktop.getDesktop().open(app.paths.knownHosts.toFile())
                        }
                    }) { Text("known_hosts öffnen") }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
        Spacer(Modifier.width(0.dp))
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
        }
        Switch(checked, onChange)
    }
}
