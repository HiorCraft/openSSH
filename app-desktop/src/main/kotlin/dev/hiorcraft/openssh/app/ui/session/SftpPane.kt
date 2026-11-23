package dev.hiorcraft.openssh.app.ui.session

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.state.SftpState
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.FilePickers
import dev.hiorcraft.openssh.app.ui.TextInputDialog
import dev.hiorcraft.openssh.app.ui.components.SearchField
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.components.formatBytes
import dev.hiorcraft.openssh.app.ui.components.onPreviewKeyEnter
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.sftp.EntryType
import dev.hiorcraft.openssh.sftp.RemoteEntry
import dev.hiorcraft.openssh.sftp.RemotePaths
import dev.hiorcraft.openssh.sftp.TransferDirection
import dev.hiorcraft.openssh.sftp.TransferState
import kotlinx.coroutines.launch
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun SftpBrowserView(app: AppState, state: SftpState, modifier: Modifier = Modifier) {
    val settings by app.settings.collectAsState()
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<List<RemoteEntry>?>(null) }
    var chmodding by remember { mutableStateOf<RemoteEntry?>(null) }
    var editing by remember { mutableStateOf<RemoteEntry?>(null) }
    var dragOver by remember { mutableStateOf(false) }
    var pathInput by remember(state.path) { mutableStateOf(state.path) }
    var anchor by remember { mutableStateOf<String?>(null) }

    val visible = state.visibleEntries
    val selected = visible.filter { it.path in state.selection }

    fun downloadTarget(): Path? =
        settings.downloadDir.takeIf { it.isNotBlank() }?.let(Path::of)
            ?: FilePickers.chooseDirectory("Zielordner für den Download wählen")

    fun download(entries: List<RemoteEntry>) {
        if (entries.isEmpty()) return
        val dir = downloadTarget() ?: return
        state.download(entries, dir)
    }

    val dropTarget = remember(state) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dragOver = true }
            override fun onExited(event: DragAndDropEvent) { dragOver = false }
            override fun onEnded(event: DragAndDropEvent) { dragOver = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragOver = false
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                val files = (transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).map(File::toPath)
                state.upload(files)
                app.notify("${files.size} Element(e) werden nach ${state.path} hochgeladen")
                return true
            }
        }
    }

    Column(modifier) {
        // Werkzeugleiste
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            ToolButton(Icons.Filled.ArrowUpward, "Übergeordneter Ordner", enabled = state.path != "/") { state.up() }
            ToolButton(Icons.Filled.Refresh, "Aktualisieren") { state.refresh() }
            ToolButton(Icons.Filled.CreateNewFolder, "Neuer Ordner") { newFolder = true }
            ToolButton(Icons.Filled.Upload, "Dateien hochladen") {
                val files = FilePickers.openFiles("Dateien zum Hochladen wählen")
                state.upload(files)
            }
            ToolButton(Icons.Filled.Download, "Auswahl herunterladen", enabled = selected.isNotEmpty()) { download(selected) }
            ToolButton(Icons.Filled.Delete, "Auswahl löschen", enabled = selected.isNotEmpty()) { deleting = selected }
            ToolButton(if (state.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, if (state.showHidden) "Versteckte ausblenden" else "Versteckte anzeigen") {
                state.showHidden = !state.showHidden
            }
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = pathInput,
                onValueChange = { pathInput = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp).onPreviewKeyEnter { state.open(pathInput) },
            )
            Spacer(Modifier.width(6.dp))
            SearchField(state.filter, { state.filter = it }, "Filter", Modifier.width(180.dp))
        }
        // Breadcrumbs
        LazyRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            items(RemotePaths.breadcrumbs(state.path)) { (name, path) ->
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (path == state.path) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip4().clickable { state.open(path) }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (path != state.path) Icon(Icons.Filled.ChevronRight, null, Modifier.size(14.dp), tint = extraColors.subtle)
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }

        // Kopfzeile
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            HeaderCell("Name", Modifier.weight(1f))
            HeaderCell("Größe", Modifier.width(90.dp))
            HeaderCell("Geändert", Modifier.width(130.dp))
            HeaderCell("Rechte", Modifier.width(100.dp))
        }
        HorizontalDivider(color = extraColors.divider)

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .let { if (dragOver) it.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)) else it },
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(visible, key = { it.path }) { entry ->
                    val isSelected = entry.path in state.selection
                    ContextMenuArea(items = {
                        val targets = if (isSelected && selected.size > 1) selected else listOf(entry)
                        buildList {
                            if (entry.isDirectory) add(ContextMenuItem("Öffnen") { state.open(entry.path) })
                            if (!entry.isDirectory && entry.size < 2_000_000) add(ContextMenuItem("Bearbeiten") { editing = entry })
                            add(ContextMenuItem(if (targets.size > 1) "${targets.size} Elemente herunterladen" else "Herunterladen") { download(targets) })
                            add(ContextMenuItem("Umbenennen") { renaming = entry })
                            add(ContextMenuItem("Rechte ändern") { chmodding = entry })
                            add(ContextMenuItem("Pfad kopieren") { dev.hiorcraft.openssh.app.ui.components.copyToClipboard(entry.path) })
                            add(ContextMenuItem(if (targets.size > 1) "${targets.size} Elemente löschen" else "Löschen") { deleting = targets })
                        }
                    }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
                                .onPointerEvent(PointerEventType.Press) { ev ->
                                    val mods = ev.keyboardModifiers
                                    state.selection = when {
                                        mods.isCtrlPressed -> if (isSelected) state.selection - entry.path else state.selection + entry.path
                                        mods.isShiftPressed && anchor != null -> {
                                            val a = visible.indexOfFirst { it.path == anchor }
                                            val b = visible.indexOf(entry)
                                            if (a < 0) setOf(entry.path) else visible.subList(minOf(a, b), maxOf(a, b) + 1).map { it.path }.toSet()
                                        }
                                        isSelected && ev.buttons.isSecondaryPressed -> state.selection
                                        else -> setOf(entry.path)
                                    }
                                    if (!mods.isShiftPressed) anchor = entry.path
                                }
                                .combinedClickable(onClick = {}, onDoubleClick = {
                                    if (entry.isDirectory) state.open(entry.path) else download(listOf(entry))
                                })
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    when {
                                        entry.isDirectory -> Icons.Filled.Folder
                                        entry.type == EntryType.SYMLINK -> Icons.Filled.Link
                                        else -> Icons.Filled.Description
                                    },
                                    null,
                                    Modifier.size(18.dp),
                                    tint = if (entry.isDirectory) androidx.compose.ui.graphics.Color(0xFFE8B84A) else extraColors.subtle,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (entry.isDirectory) "" else formatBytes(entry.size), Modifier.width(90.dp), style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                            Text(entry.modified?.let(dateFormat::format) ?: "", Modifier.width(130.dp), style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                            Text(entry.permissionString, Modifier.width(100.dp), style = MaterialTheme.typography.bodySmall, fontFamily = MonoFont, color = extraColors.subtle)
                        }
                    }
                }
            }
            if (visible.isEmpty() && !state.loading) {
                Text(
                    if (state.filter.isNotBlank()) "Keine Treffer" else "Leerer Ordner – Dateien hierher ziehen zum Hochladen",
                    Modifier.align(Alignment.Center),
                    color = extraColors.subtle,
                )
            }
        }
        TransferList(state)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "${visible.size} Elemente" + if (selected.isNotEmpty()) " · ${selected.size} ausgewählt (${formatBytes(selected.filterNot { it.isDirectory }.sumOf { it.size })})" else "",
                style = MaterialTheme.typography.labelSmall,
                color = extraColors.subtle,
            )
        }
    }

    if (newFolder) TextInputDialog("Neuer Ordner", "Name", confirmLabel = "Anlegen", onConfirm = { state.mkdir(it) }, onDismiss = { newFolder = false })
    renaming?.let { e -> TextInputDialog("Umbenennen", "Neuer Name", e.name, "Umbenennen", onConfirm = { state.rename(e, it) }, onDismiss = { renaming = null }) }
    chmodding?.let { e ->
        TextInputDialog("Rechte von ${e.name}", "Oktal (z.B. 644 oder 755)", "%o".format(e.mode), "Ändern", onConfirm = { v ->
            v.toIntOrNull(8)?.let { state.chmod(e, it) } ?: app.notify("Ungültiger Oktalwert: $v")
        }, onDismiss = { chmodding = null })
    }
    deleting?.let { list ->
        ConfirmDialog(
            "Löschen?",
            if (list.size == 1) "„${list.first().name}“ wird${if (list.first().isDirectory) " samt Inhalt" else ""} auf dem Server gelöscht." else "${list.size} Elemente werden auf dem Server gelöscht (Ordner samt Inhalt).",
            onConfirm = { state.delete(list) },
            onDismiss = { deleting = null },
        )
    }
    editing?.let { e -> RemoteEditorDialog(app, state, e) { editing = null } }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = extraColors.subtle)
}

@Composable
private fun TransferList(state: SftpState) {
    val transfers by state.queue.transfers.collectAsState()
    if (transfers.isEmpty()) return
    HorizontalDivider(color = extraColors.divider)
    Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Übertragungen", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { state.queue.clearFinished() }) { Text("Erledigte entfernen") }
        }
        LazyColumn {
            items(transfers.reversed(), key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (t.direction == TransferDirection.UPLOAD) Icons.Filled.Upload else Icons.Filled.Download, null, Modifier.size(16.dp), tint = extraColors.subtle)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.displayName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val p = t.progress
                        when (val s = t.state) {
                            TransferState.Queued -> Text("Wartet …", style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
                            TransferState.Running -> {
                                LinearProgressIndicator(progress = { p?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().height(3.dp))
                                if (p != null) Text(
                                    "${formatBytes(p.bytesDone)} / ${formatBytes(p.bytesTotal)}" + if (p.filesTotal > 1) " · Datei ${p.filesDone + 1}/${p.filesTotal}: ${p.currentFile}" else "",
                                    style = MaterialTheme.typography.labelSmall, color = extraColors.subtle,
                                )
                            }
                            TransferState.Done -> Text("Fertig" + (p?.let { " · ${formatBytes(it.bytesTotal)}" } ?: ""), style = MaterialTheme.typography.labelSmall, color = extraColors.success)
                            TransferState.Cancelled -> Text("Abgebrochen", style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
                            is TransferState.Failed -> Text(s.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (!t.isFinished) ToolButton(Icons.Filled.Close, "Abbrechen") { state.queue.cancel(t.id) }
                }
            }
        }
    }
}

@Composable
private fun RemoteEditorDialog(app: AppState, state: SftpState, entry: RemoteEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(entry) {
        runCatching { state.readText(entry) }.onSuccess { text = it }.onFailure { error = it.message }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.path, style = MaterialTheme.typography.titleSmall, fontFamily = MonoFont) },
        text = {
            Box(Modifier.width(820.dp).height(520.dp)) {
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    text == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> OutlinedTextField(
                        value = text!!,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
                    )
                }
            }
        },
        confirmButton = {
            Button(enabled = text != null, onClick = {
                scope.launch { state.writeText(entry, text!!); app.notify("${entry.name} gespeichert") }
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun Modifier.clip4() = this.then(Modifier.background(androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(4.dp)))
