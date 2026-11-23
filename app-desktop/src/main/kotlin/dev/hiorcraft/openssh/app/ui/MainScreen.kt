package dev.hiorcraft.openssh.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.state.DatabaseTab
import dev.hiorcraft.openssh.app.state.HomeSection
import dev.hiorcraft.openssh.app.state.RedisTab
import dev.hiorcraft.openssh.app.state.SessionTab
import dev.hiorcraft.openssh.app.state.SftpTab
import dev.hiorcraft.openssh.app.state.SplitTab
import dev.hiorcraft.openssh.app.state.TabStatus
import dev.hiorcraft.openssh.app.state.TerminalTab
import dev.hiorcraft.openssh.app.ui.components.SplitPane
import dev.hiorcraft.openssh.app.ui.components.StatusDot
import dev.hiorcraft.openssh.app.ui.home.DatabasesScreen
import dev.hiorcraft.openssh.app.ui.home.HostsScreen
import dev.hiorcraft.openssh.app.ui.home.KeysScreen
import dev.hiorcraft.openssh.app.ui.home.SettingsScreen
import dev.hiorcraft.openssh.app.ui.home.SnippetsScreen
import dev.hiorcraft.openssh.app.ui.session.ConnectionStateView
import dev.hiorcraft.openssh.app.ui.session.DatabaseView
import dev.hiorcraft.openssh.app.ui.session.RedisView
import dev.hiorcraft.openssh.app.ui.session.SftpBrowserView
import dev.hiorcraft.openssh.app.ui.session.TerminalToolbar
import dev.hiorcraft.openssh.app.ui.session.TerminalView
import dev.hiorcraft.openssh.app.ui.theme.extraColors

@Composable
fun MainScreen(app: AppState) {
    val colors = extraColors
    Box(Modifier.fillMaxSize().background(colors.windowBackground)) {
        Column(Modifier.fillMaxSize()) {
            TabStrip(app)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val active = app.activeTab
                if (active != null) SessionContent(app, active, true) else Home(app)
            }
        }
        SnackbarHost(app.snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    app.hostKeyBridge.pending.collectAsState().value?.let { HostKeyDialog(it) }
    app.secretPrompts.pending.collectAsState().value?.let { SecretPromptDialog(it) }
}

@Composable
private fun TabStrip(app: AppState) {
    var newMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(42.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabChip(
            title = "Start",
            icon = Icons.Filled.Home,
            active = app.activeTabId == null,
            status = null,
            onClick = { app.activeTabId = null },
            onClose = null,
        )
        LazyRow(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(app.tabs, key = { it.id }) { tab ->
                TabChip(
                    title = tab.title,
                    icon = iconFor(tab),
                    active = tab.id == app.activeTabId,
                    status = tab.status,
                    onClick = { app.activeTabId = tab.id },
                    onClose = { app.closeTab(tab) },
                )
            }
        }
        Box {
            dev.hiorcraft.openssh.app.ui.components.ToolButton(Icons.Filled.Add, "Neues lokales Terminal (Strg+Umschalt+T)") { newMenu = true }
            DropdownMenu(newMenu, { newMenu = false }) {
                app.localShells.detect().forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.displayName) },
                        leadingIcon = { Icon(Icons.Filled.Computer, null) },
                        onClick = { newMenu = false; app.openLocal(profile) },
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

private fun iconFor(tab: SessionTab): ImageVector = when (tab) {
    is TerminalTab -> if (tab.isLocal) Icons.Filled.Computer else Icons.Filled.Terminal
    is SftpTab -> Icons.Filled.Folder
    is SplitTab -> Icons.Filled.VerticalSplit
    is DatabaseTab -> Icons.Filled.TableChart
    is RedisTab -> Icons.Filled.Storage
}

@Composable
private fun TabChip(title: String, icon: ImageVector, active: Boolean, status: TabStatus?, onClick: () -> Unit, onClose: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val colors = extraColors
    Row(
        Modifier
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    active -> MaterialTheme.colorScheme.surfaceContainerHigh
                    hovered -> colors.cardHover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 10.dp, end = if (onClose != null) 4.dp else 12.dp)
            .height(32.dp)
            .widthIn(max = 220.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (active) MaterialTheme.colorScheme.primary else colors.subtle)
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (status != null && status != TabStatus.Ready) {
            Spacer(Modifier.width(6.dp))
            StatusDot(if (status == TabStatus.Connecting) colors.warning else MaterialTheme.colorScheme.error)
        }
        if (onClose != null) {
            Box(
                Modifier.padding(start = 4.dp).size(22.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, "Schließen", Modifier.size(14.dp), tint = colors.subtle)
            }
        }
    }
}

@Composable
private fun Home(app: AppState) {
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(extraColors.sidebar).padding(10.dp)) {
            Text("OpenSSH", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 16.dp))
            NavItem(HomeSection.HOSTS, Icons.Filled.Dns, app)
            NavItem(HomeSection.KEYS, Icons.Filled.Key, app)
            NavItem(HomeSection.SNIPPETS, Icons.Filled.Code, app)
            NavItem(HomeSection.DATABASES, Icons.Filled.Storage, app)
            Spacer(Modifier.weight(1f))
            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = extraColors.divider)
            SidebarButton("Lokales Terminal", Icons.Filled.Computer, false) { app.openLocal() }
            NavItem(HomeSection.SETTINGS, Icons.Filled.Settings, app)
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 8.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = if (extraColors.windowBackground.alpha < 1f) 0.55f else 1f)),
        ) {
            when (app.section) {
                HomeSection.HOSTS -> HostsScreen(app)
                HomeSection.KEYS -> KeysScreen(app)
                HomeSection.SNIPPETS -> SnippetsScreen(app)
                HomeSection.DATABASES -> DatabasesScreen(app)
                HomeSection.SETTINGS -> SettingsScreen(app)
            }
        }
    }
}

@Composable
private fun NavItem(section: HomeSection, icon: ImageVector, app: AppState) =
    SidebarButton(section.label, icon, app.section == section) { app.section = section }

@Composable
private fun SidebarButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                    hovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .height(38.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fluent-typischer Akzentbalken für den aktiven Eintrag
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent))
        Spacer(Modifier.width(9.dp))
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SessionContent(app: AppState, tab: SessionTab, active: Boolean) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        when (tab) {
            is TerminalTab -> {
                TerminalToolbar(
                    app = app,
                    title = tab.title,
                    subtitle = tab.sshHost?.address ?: "lokal",
                    status = tab.status,
                    onSnippet = tab::send,
                    onRestart = tab::restart,
                    forwards = if (tab.isLocal) null else tab.forwards,
                    onAddForward = if (tab.isLocal) null else tab::addForward,
                    onRemoveForward = if (tab.isLocal) null else tab::removeForward,
                )
                val controller = tab.controller
                if (controller != null && (tab.status == TabStatus.Ready || tab.status == TabStatus.Closed)) {
                    if (tab.status == TabStatus.Closed) ClosedBanner { tab.restart() }
                    if (active) TerminalView(controller, Modifier.weight(1f).fillMaxWidth())
                } else {
                    ConnectionStateView(tab.status, tab.sshHost?.address ?: tab.title) { tab.restart() }
                }
            }
            is SftpTab -> {
                val sftp = tab.sftp
                if (sftp != null && tab.status == TabStatus.Ready) SftpBrowserView(app, sftp, Modifier.fillMaxSize())
                else ConnectionStateView(tab.status, tab.host.address) { tab.close(); app.closeTab(tab); app.openSftp(tab.host) }
            }
            is SplitTab -> {
                val controller = tab.controller
                val sftp = tab.sftp
                if (controller != null && sftp != null && tab.status != TabStatus.Connecting && tab.status !is TabStatus.Failed) {
                    TerminalToolbar(
                        app, tab.host.label, tab.host.address, tab.status, tab::send, null, tab.forwards, tab::addForward, tab::removeForward,
                    )
                    if (tab.status == TabStatus.Closed) ClosedBanner { app.closeTab(tab); app.openSplit(tab.host) }
                    SplitPane(tab.splitFraction, { tab.splitFraction = it }, Modifier.weight(1f).fillMaxWidth(),
                        first = { if (active) TerminalView(controller, Modifier.fillMaxSize()) },
                        second = { SftpBrowserView(app, sftp, Modifier.fillMaxSize()) },
                    )
                } else {
                    ConnectionStateView(tab.status, tab.host.address) { app.closeTab(tab); app.openSplit(tab.host) }
                }
            }
            is DatabaseTab -> {
                if (tab.status == TabStatus.Ready && tab.client != null) DatabaseView(tab)
                else ConnectionStateView(tab.status, tab.entry.name) { tab.restart() }
            }
            is RedisTab -> {
                if (tab.status == TabStatus.Ready && tab.browser != null) RedisView(tab)
                else ConnectionStateView(tab.status, tab.entry.name) { tab.restart() }
            }
        }
    }
}

@Composable
private fun ClosedBanner(onReconnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Die Sitzung wurde beendet.", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(
            "Neu verbinden",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onReconnect).padding(6.dp),
        )
    }
}
