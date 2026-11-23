package dev.hiorcraft.openssh.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.ui.theme.extraColors
import java.awt.Cursor
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/** Icon-Button mit Tooltip. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ToolButton(icon: ImageVector, tooltip: String, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(shape = RoundedCornerShape(4.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                Text(tooltip, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
            }
        },
        delayMillis = 500,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(34.dp)) {
            Icon(icon, contentDescription = tooltip, modifier = Modifier.size(18.dp), tint = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.9f else 0.35f))
        }
    }
}

@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(48.dp),
        singleLine = true,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(onClick = { onValueChange("") }) { Icon(Icons.Filled.Clear, "Leeren", Modifier.size(16.dp)) }
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** Einzeiliges Formularfeld. */
@Composable
fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supporting: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        modifier = modifier,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = trailing,
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth(), supporting: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (visible) "Verbergen" else "Anzeigen", Modifier.size(18.dp))
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

/** Einfaches Auswahlfeld mit Dropdown. */
@Composable
fun <T> Dropdown(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    display: (T) -> String = { it.toString() },
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = extraColors.subtle, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(display(selected), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(display(option)) }, onClick = { onSelect(option); expanded = false })
                }
            }
        }
    }
}

/** Karte im Fluent-Stil mit Hover-Effekt. */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = extraColors
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(if (hovered && onClick != null) colors.cardHover else colors.card)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else colors.divider, MaterialTheme.shapes.medium)
            .hoverable(interaction)
            .let { if (onClick != null) it.clickable(interactionSource = interaction, indication = null, onClick = onClick) else it }
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = extraColors.subtle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, action: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(48.dp), tint = extraColors.subtle)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = extraColors.subtle)
        Spacer(Modifier.height(16.dp))
        action()
    }
}

/** Runder Avatar mit Initialen, Farbe aus dem Namen abgeleitet. */
@Composable
fun Avatar(text: String, size: Int = 36) {
    val palette = listOf(0xFF4F8EF7, 0xFF8E6CEF, 0xFF2FB67C, 0xFFE58C3A, 0xFFE0567A, 0xFF2AA7C4, 0xFFB0904A)
    val color = Color(palette[(text.hashCode() and 0x7fffffff) % palette.size])
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
        Text(
            text.split(' ', '-', '.', '_').filter { it.isNotEmpty() }.take(2).joinToString("") { it.take(1).uppercase() }.ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Zwei Bereiche mit verschiebbarem Trenner. */
@Composable
fun SplitPane(
    fraction: Float,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val totalPx = with(LocalDensity.current) { maxWidth.toPx() }
        var current by remember { mutableStateOf(fraction) }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(current.coerceIn(0.15f, 0.85f)).fillMaxHeight()) { first() }
            Box(
                Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            current = (current + delta / totalPx).coerceIn(0.15f, 0.85f)
                            onFractionChange(current)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(extraColors.divider))
            }
            Box(Modifier.weight(1f - current.coerceIn(0.15f, 0.85f)).fillMaxHeight()) { second() }
        }
    }
}

@Composable
fun LabeledValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(min = 80.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = extraColors.subtle)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return String.format(java.util.Locale.GERMANY, "%.1f %s", value, units[i])
}


/** Führt [action] bei Enter aus (für einzeilige Eingabefelder). */
fun Modifier.onPreviewKeyEnter(action: () -> Unit): Modifier = this.onPreviewKeyEvent {
    if (it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && it.key == androidx.compose.ui.input.key.Key.Enter) {
        action(); true
    } else {
        false
    }
}
