package dev.hiorcraft.openssh.app.ui.session

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.RedisTab
import dev.hiorcraft.openssh.app.ui.ConfirmDialog
import dev.hiorcraft.openssh.app.ui.TextInputDialog
import dev.hiorcraft.openssh.app.ui.components.Badge
import dev.hiorcraft.openssh.app.ui.components.SplitPane
import dev.hiorcraft.openssh.app.ui.components.ToolButton
import dev.hiorcraft.openssh.app.ui.components.copyToClipboard
import dev.hiorcraft.openssh.app.ui.components.onPreviewKeyEnter
import dev.hiorcraft.openssh.app.ui.theme.MonoFont
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import dev.hiorcraft.openssh.db.ColumnInfo
import dev.hiorcraft.openssh.db.StatementResult
import dev.hiorcraft.openssh.db.redis.RedisKeyDetails
import dev.hiorcraft.openssh.db.redis.RedisValue

@Composable
fun RedisView(tab: RedisTab) {
    var split by remember { mutableStateOf(0.34f) }
    SplitPane(split, { split = it }, Modifier.fillMaxSize(), first = { KeyList(tab) }, second = {
        val details = tab.details
        if (details == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Key auswählen", color = extraColors.subtle) }
        } else {
            KeyDetails(tab, details)
        }
    })
}

@Composable
private fun KeyList(tab: RedisTab) {
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = tab.pattern,
                onValueChange = { tab.pattern = it },
                singleLine = true,
                label = { Text("Muster (z.B. user:*)") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFont),
                modifier = Modifier.weight(1f).onPreviewKeyEnter { tab.search() },
            )
            ToolButton(Icons.Filled.Refresh, "Suchen") { tab.search() }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "${tab.keys.size} Keys angezeigt · ${tab.dbSize} in der Datenbank" + if (tab.serverVersion.isNotBlank()) " · Redis ${tab.serverVersion}" else "",
            style = MaterialTheme.typography.labelSmall, color = extraColors.subtle,
        )
        if (tab.loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(tab.keys, key = { it }) { key ->
                    val selected = tab.details?.key == key
                    Text(
                        key,
                        fontFamily = MonoFont,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable { tab.select(key) }
                            .padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }
                if (!tab.finished) {
                    item {
                        OutlinedButton(onClick = { tab.loadMore() }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Weitere laden") }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun KeyDetails(tab: RedisTab, details: RedisKeyDetails) {
    var ttlDialog by remember(details.key) { mutableStateOf(false) }
    var deleting by remember(details.key) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer(Modifier.weight(1f)) {
                Text(details.key, style = MaterialTheme.typography.titleMedium, fontFamily = MonoFont, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Badge(details.type)
            Badge(
                when (details.ttlSeconds) {
                    -1L -> "kein Ablauf"
                    -2L -> "existiert nicht"
                    else -> "TTL ${details.ttlSeconds}s"
                },
            )
            ToolButton(Icons.Filled.Timer, "Ablaufzeit setzen") { ttlDialog = true }
            ToolButton(Icons.Filled.ContentCopy, "Key kopieren") { copyToClipboard(details.key) }
            ToolButton(Icons.Filled.Refresh, "Neu laden") { tab.select(details.key) }
            ToolButton(Icons.Filled.Delete, "Löschen") { deleting = true }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = extraColors.divider)
        val value = details.value
        if (value.isTruncated) {
            Text("Zeige die ersten Einträge von ${value.total}", style = MaterialTheme.typography.labelSmall, color = extraColors.warning)
        }
        when (value) {
            is RedisValue.Text -> StringEditor(tab, details.key, value.value)
            is RedisValue.ListValue -> ResultTable(table(listOf("Index", "Wert"), value.items.mapIndexed { i, v -> listOf(i.toString(), v) }))
            is RedisValue.SetValue -> ResultTable(table(listOf("Mitglied"), value.members.map { listOf(it) }))
            is RedisValue.SortedSetValue -> ResultTable(table(listOf("Mitglied", "Score"), value.entries.map { listOf(it.first, it.second.toString()) }))
            is RedisValue.HashValue -> ResultTable(table(listOf("Feld", "Wert"), value.fields.map { listOf(it.first, it.second) }))
            is RedisValue.StreamValue -> ResultTable(table(listOf("ID", "Felder"), value.entries.map { (id, body) -> listOf(id, body.entries.joinToString(", ") { "${it.key}=${it.value}" }) }))
            RedisValue.Missing -> Text("Der Key existiert nicht (mehr).", color = extraColors.subtle)
            is RedisValue.Unsupported -> Text("Typ „${value.type}“ wird nicht angezeigt.", color = extraColors.subtle)
        }
    }
    if (ttlDialog) {
        TextInputDialog("Ablaufzeit für ${details.key}", "Sekunden (-1 = nie ablaufen)", details.ttlSeconds.coerceAtLeast(-1).toString(), "Setzen", onConfirm = {
            it.toLongOrNull()?.let { s -> tab.setTtl(details.key, s) }
        }, onDismiss = { ttlDialog = false })
    }
    if (deleting) {
        ConfirmDialog("Key löschen?", "„${details.key}“ wird endgültig aus Redis gelöscht.", onConfirm = { tab.delete(details.key) }, onDismiss = { deleting = false })
    }
}

@Composable
private fun StringEditor(tab: RedisTab, key: String, initial: String) {
    var text by remember(key, initial) { mutableStateOf(prettyJson(initial)) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(enabled = text != prettyJson(initial), onClick = { tab.saveString(key, text) }) {
                androidx.compose.material3.Icon(Icons.Filled.Save, null); Spacer(Modifier.width(6.dp)); Text("Speichern")
            }
            Text("${initial.length} Zeichen", style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
        }
    }
}

private fun table(columns: List<String>, rows: List<List<String>>) =
    StatementResult.Table("", columns.map { ColumnInfo(it, "") }, rows, false, 0)

private val prettyJsonFormat = kotlinx.serialization.json.Json { prettyPrint = true }

/** Formatiert JSON-Werte lesbar; alles andere bleibt unverändert. */
private fun prettyJson(value: String): String {
    val t = value.trim()
    if (!(t.startsWith("{") && t.endsWith("}")) && !(t.startsWith("[") && t.endsWith("]"))) return value
    return runCatching {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(t)
        prettyJsonFormat.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
    }.getOrDefault(value)
}
