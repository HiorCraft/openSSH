package dev.hiorcraft.openssh.app.ui.session

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.DatabaseTab
import dev.hiorcraft.openssh.app.ui.components.Dropdown
import dev.hiorcraft.openssh.app.ui.components.SplitPane
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.components.copyToClipboard
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.db.StatementResult
import dev.hiorcraft.openssh.db.TableInfo

@Composable
fun DatabaseView(tab: DatabaseTab) {
    var sidebar by remember { mutableStateOf(0.24f) }
    SplitPane(sidebar, { sidebar = it }, Modifier.fillMaxSize(), first = { SchemaSidebar(tab) }, second = {
        Column(Modifier.fillMaxSize()) {
            QueryEditor(tab)
            HorizontalDivider(color = extraColors.divider)
            ResultsArea(tab)
        }
    })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SchemaSidebar(tab: DatabaseTab) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text(tab.serverVersion, style = MaterialTheme.typography.labelSmall, color = extraColors.subtle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        if (tab.schemas.size > 1) {
            Dropdown("Schema / Datenbank", tab.schemas, tab.schema ?: tab.schemas.first(), { tab.selectSchema(it) })
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tabellen (${tab.tables.size})", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            ToolButton(Icons.Filled.Refresh, "Neu laden") { tab.refreshTables() }
        }
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                tab.tables.forEach { table ->
                    item(key = "t-" + table.name) {
                        val expanded = tab.columns.containsKey(table.name)
                        ContextMenuArea(items = {
                            listOf(
                                ContextMenuItem("Erste 200 Zeilen anzeigen") { tab.previewTable(table) },
                                ContextMenuItem("Spalten anzeigen") { tab.toggleColumns(table) },
                                ContextMenuItem("Namen kopieren") { copyToClipboard(table.name) },
                            )
                        }) {
                            TableRow(table, expanded, onToggle = { tab.toggleColumns(table) }, onOpen = { tab.previewTable(table) })
                        }
                    }
                    tab.columns[table.name]?.let { cols ->
                        cols.forEach { c ->
                            item(key = "c-${table.name}-${c.name}") {
                                Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (c.primaryKey) Icon(Icons.Filled.Key, "Primärschlüssel", Modifier.size(12.dp), tint = extraColors.warning) else Spacer(Modifier.width(12.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(c.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        c.typeName.lowercase() + if (!c.nullable) " ·nn" else "",
                                        style = MaterialTheme.typography.labelSmall, color = extraColors.subtle,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TableRow(table: TableInfo, expanded: Boolean, onToggle: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onDoubleClick = onOpen).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, Modifier.size(16.dp), tint = extraColors.subtle)
        Spacer(Modifier.width(2.dp))
        Icon(if (table.isView) Icons.Filled.Visibility else Icons.Filled.TableChart, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(table.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun QueryEditor(tab: DatabaseTab) {
    Column(Modifier.fillMaxWidth().height(230.dp).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab.running) {
                Button(onClick = { tab.cancelQuery() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Abbrechen")
                }
            } else {
                Button(onClick = { tab.runEditor() }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Ausführen")
                }
            }
            Text(
                if (tab.query.selection.collapsed) "Strg+Enter: alles ausführen" else "Strg+Enter: Auswahl ausführen",
                style = MaterialTheme.typography.labelSmall, color = extraColors.subtle,
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = tab.query,
            onValueChange = { tab.query = it },
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.isCtrlPressed && it.key == Key.Enter) { tab.runEditor(); true } else false
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
        )
    }
}

@Composable
private fun ResultsArea(tab: DatabaseTab) {
    Column(Modifier.fillMaxSize()) {
        if (tab.running) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        tab.queryError?.let {
            SelectionContainer {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp), fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall)
            }
        }
        val results = tab.results
        if (results.isEmpty()) {
            if (tab.queryError == null) Text("Noch keine Ergebnisse", Modifier.padding(16.dp), color = extraColors.subtle)
            return
        }
        if (results.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                results.forEachIndexed { i, r ->
                    FilterChip(
                        selected = tab.selectedResult == i,
                        onClick = { tab.selectedResult = i },
                        label = { Text(if (r is StatementResult.Table) "Ergebnis ${i + 1} (${r.rows.size})" else "Anweisung ${i + 1}") },
                    )
                }
            }
        }
        when (val r = results.getOrNull(tab.selectedResult) ?: results.last()) {
            is StatementResult.Update -> Column(Modifier.padding(16.dp)) {
                Text("${r.count} Zeile(n) betroffen · ${r.durationMillis} ms", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(6.dp))
                Text(r.sql, fontFamily = MonoFont, style = MaterialTheme.typography.bodySmall, color = extraColors.subtle, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            is StatementResult.Table -> ResultTable(r)
        }
    }
}

@Composable
fun ResultTable(result: StatementResult.Table) {
    val widths: List<Dp> = remember(result) {
        result.columns.mapIndexed { i, c ->
            val longest = maxOf(c.name.length, result.rows.take(200).maxOfOrNull { (it[i] ?: "NULL").length.coerceAtMost(60) } ?: 0)
            (longest * 7.5f + 24).coerceIn(70f, 420f).dp
        }
    }
    var selectedRow by remember(result) { mutableStateOf(-1) }
    val hScroll = rememberScrollState()
    val listState = rememberLazyListState()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${result.rows.size} Zeilen" + (if (result.truncated) " (gekürzt)" else "") + " · ${result.durationMillis} ms",
                style = MaterialTheme.typography.labelMedium,
                color = if (result.truncated) extraColors.warning else extraColors.subtle,
                modifier = Modifier.weight(1f),
            )
            ToolButton(Icons.Filled.ContentCopy, "Als TSV kopieren (für Excel)") {
                val header = result.columns.joinToString("\t") { it.name }
                val body = result.rows.joinToString("\n") { row -> row.joinToString("\t") { (it ?: "").replace('\t', ' ').replace('\n', ' ') } }
                copyToClipboard(header + "\n" + body)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.horizontalScroll(hScroll)) {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    result.columns.forEachIndexed { i, c ->
                        Column(Modifier.width(widths[i]).padding(horizontal = 8.dp, vertical = 6.dp)) {
                            Text(c.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(c.typeName.lowercase(), style = MaterialTheme.typography.labelSmall, color = extraColors.subtle, maxLines = 1)
                        }
                    }
                }
                LazyColumn(state = listState, modifier = Modifier.width(widths.fold(0.dp) { a, b -> a + b }).fillMaxHeight()) {
                    itemsIndexed(result.rows) { index, row ->
                        ContextMenuArea(items = {
                            listOf(
                                ContextMenuItem("Zeile kopieren") { copyToClipboard(row.joinToString("\t") { it ?: "" }) },
                                ContextMenuItem("Als INSERT-Werte kopieren") {
                                    copyToClipboard(row.joinToString(", ", "(", ")") { v -> v?.let { "'" + it.replace("'", "''") + "'" } ?: "NULL" })
                                },
                            )
                        }) {
                            Row(
                                Modifier
                                    .background(
                                        when {
                                            index == selectedRow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                            index % 2 == 1 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f)
                                            else -> Color.Transparent
                                        },
                                    )
                                    .clickable { selectedRow = index },
                            ) {
                                row.forEachIndexed { i, value ->
                                    Text(
                                        value ?: "NULL",
                                        modifier = Modifier.width(widths[i]).padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = MonoFont,
                                        color = if (value == null) extraColors.subtle else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            HorizontalScrollbar(rememberScrollbarAdapter(hScroll), Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        if (selectedRow in result.rows.indices) {
            HorizontalDivider(color = extraColors.divider)
            SelectionContainer {
                Column(Modifier.fillMaxWidth().height(120.dp).padding(10.dp).horizontalScroll(rememberScrollState())) {
                    result.columns.forEachIndexed { i, c ->
                        Row {
                            Text(c.name + ": ", style = MaterialTheme.typography.bodySmall, color = extraColors.subtle)
                            Text(result.rows[selectedRow][i] ?: "NULL", style = MaterialTheme.typography.bodySmall, fontFamily = MonoFont)
                        }
                    }
                }
            }
        }
    }
}
