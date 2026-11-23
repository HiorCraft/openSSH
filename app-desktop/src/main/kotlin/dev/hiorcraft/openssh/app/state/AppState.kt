package dev.hiorcraft.openssh.app.state

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hiorcraft.openssh.app.data.AppPaths
import dev.hiorcraft.openssh.app.data.AppSettings
import dev.hiorcraft.openssh.app.data.DbEntry
import dev.hiorcraft.openssh.app.data.DbKind
import dev.hiorcraft.openssh.app.data.HostEntry
import dev.hiorcraft.openssh.app.data.JsonListStore
import dev.hiorcraft.openssh.app.data.KeyEntry
import dev.hiorcraft.openssh.app.data.SecretNames
import dev.hiorcraft.openssh.app.data.SettingsStore
import dev.hiorcraft.openssh.app.data.Snippet
import dev.hiorcraft.openssh.ssh.keys.KeyLoader
import dev.hiorcraft.openssh.terminal.LocalShells
import dev.hiorcraft.openssh.terminal.ShellProfile
import dev.hiorcraft.openssh.terminal.TerminalSettings
import dev.hiorcraft.openssh.terminal.TerminalTheme
import dev.hiorcraft.openssh.vault.SecretVault
import dev.hiorcraft.openssh.vault.Vaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class HomeSection(val label: String) {
    HOSTS("Hosts"),
    KEYS("Schlüssel"),
    SNIPPETS("Snippets"),
    DATABASES("Datenbanken"),
    SETTINGS("Einstellungen"),
}

/** Zentraler Zustand der App: Daten, Tresor, offene Tabs und Rückfragen an den Benutzer. */
class AppState(
    val paths: AppPaths = AppPaths.default(),
    val vault: SecretVault = Vaults.platformDefault(paths.root, "OpenSSH"),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val hosts = JsonListStore(paths.hosts, HostEntry.serializer()) { it.id }
    val keys = JsonListStore(paths.keys, KeyEntry.serializer()) { it.id }
    val snippets = JsonListStore(paths.snippets, Snippet.serializer()) { it.id }
    val databases = JsonListStore(paths.databases, DbEntry.serializer()) { it.id }
    private val settingsStore = SettingsStore(paths.settings)
    val settings: StateFlow<AppSettings> = settingsStore.settings

    val hostKeyBridge = HostKeyBridge()
    val secretPrompts = SecretPromptBridge()
    val connections = ConnectionService(hosts, keys, vault, paths.knownHosts, hostKeyBridge, secretPrompts)
    val localShells = LocalShells()

    val snackbar = SnackbarHostState()

    val tabs = mutableStateListOf<SessionTab>()
    /** `null` = Startseite. */
    var activeTabId by mutableStateOf<String?>(null)
    var section by mutableStateOf(HomeSection.HOSTS)

    val activeTab: SessionTab? get() = tabs.firstOrNull { it.id == activeTabId }

    fun notify(message: String) {
        scope.launch { snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = settingsStore.update(transform)

    fun terminalSettings(): TerminalSettings {
        val s = settings.value
        return TerminalSettings(
            theme = TerminalTheme.byId(s.terminalTheme),
            fontFamily = s.fontFamily.ifBlank { TerminalSettings.defaultFontFamily() },
            fontSize = s.fontSize,
            scrollbackLines = s.scrollback,
            copyOnSelect = s.copyOnSelect,
        )
    }

    // ---- Tabs ----

    private fun open(tab: SessionTab) {
        tabs += tab
        activeTabId = tab.id
        tab.start()
    }

    fun openTerminal(host: HostEntry) = open(TerminalTab.ssh(this, host))
    fun openSftp(host: HostEntry) = open(SftpTab(this, host))
    fun openSplit(host: HostEntry) = open(SplitTab(this, host))
    fun openLocal(profile: ShellProfile = localShells.byId(settings.value.defaultShell)) = open(TerminalTab.local(this, profile))

    fun openDatabase(entry: DbEntry) = open(if (entry.kind == DbKind.REDIS) RedisTab(this, entry) else DatabaseTab(this, entry))

    fun closeTab(tab: SessionTab) {
        val index = tabs.indexOf(tab)
        tabs.remove(tab)
        tab.close()
        if (activeTabId == tab.id) activeTabId = tabs.getOrNull((index - 1).coerceAtLeast(0))?.id
    }

    fun closeAll() {
        tabs.toList().forEach { it.close() }
        tabs.clear()
    }

    /** Tabs, in die ein Snippet geschickt werden kann. */
    fun terminalTargets(): List<SessionTab> = tabs.filter { it is TerminalTab || it is SplitTab }

    fun sendSnippet(snippet: Snippet, target: SessionTab? = activeTab ?: terminalTargets().lastOrNull()) {
        when (target) {
            is TerminalTab -> target.send(snippet.command, snippet.runImmediately)
            is SplitTab -> target.send(snippet.command, snippet.runImmediately)
            else -> return notify("Kein Terminal geöffnet")
        }
        activeTabId = target.id
    }

    // ---- Datenpflege ----

    suspend fun saveHost(host: HostEntry, password: String?) {
        hosts.upsert(host)
        withContext(Dispatchers.IO) {
            when {
                !host.authType.usesPassword -> vault.delete(SecretNames.hostPassword(host.id))
                password != null -> vault.put(SecretNames.hostPassword(host.id), password)
            }
        }
    }

    suspend fun deleteHost(host: HostEntry) {
        hosts.delete(host.id)
        withContext(Dispatchers.IO) { vault.delete(SecretNames.hostPassword(host.id)) }
    }

    suspend fun hasHostPassword(host: HostEntry): Boolean =
        withContext(Dispatchers.IO) { vault.contains(SecretNames.hostPassword(host.id)) }

    /** Prüft und speichert einen Schlüssel; der private Teil landet ausschließlich im Tresor. */
    suspend fun importKey(name: String, keyText: String, passphrase: String?, rememberPassphrase: Boolean): KeyEntry =
        withContext(Dispatchers.IO) {
            val info = KeyLoader.inspect(keyText, passphrase?.takeIf { it.isNotEmpty() }?.toCharArray())
            val entry = KeyEntry(
                name = name.ifBlank { info.algorithm },
                algorithm = info.algorithm,
                fingerprint = info.fingerprint,
                publicKey = info.publicKeyLine,
                format = info.format.displayName,
                encrypted = info.encrypted,
            )
            vault.put(SecretNames.keyPrivate(entry.id), keyText)
            if (info.encrypted && rememberPassphrase && !passphrase.isNullOrEmpty()) {
                vault.put(SecretNames.keyPassphrase(entry.id), passphrase)
            }
            keys.upsert(entry)
            entry
        }

    suspend fun deleteKey(key: KeyEntry) {
        keys.delete(key.id)
        withContext(Dispatchers.IO) {
            vault.delete(SecretNames.keyPrivate(key.id))
            vault.delete(SecretNames.keyPassphrase(key.id))
        }
    }

    suspend fun forgetPassphrase(key: KeyEntry) = withContext(Dispatchers.IO) { vault.delete(SecretNames.keyPassphrase(key.id)) }

    suspend fun saveDatabase(entry: DbEntry, password: String?) {
        databases.upsert(entry)
        if (password != null) withContext(Dispatchers.IO) { vault.put(SecretNames.dbPassword(entry.id), password) }
    }

    suspend fun deleteDatabase(entry: DbEntry) {
        databases.delete(entry.id)
        withContext(Dispatchers.IO) { vault.delete(SecretNames.dbPassword(entry.id)) }
    }

    /** Passwort aus dem Tresor; fehlt es und wird eines gebraucht, wird gefragt. */
    suspend fun dbPassword(entry: DbEntry): String? {
        val stored = withContext(Dispatchers.IO) { vault.get(SecretNames.dbPassword(entry.id)) }
        if (stored != null) return stored
        if (entry.kind == DbKind.SQLITE) return null
        if (entry.kind == DbKind.REDIS && entry.username.isBlank()) {
            val answer = secretPrompts.ask("Redis ${entry.name}", "Passwort (leer lassen, wenn keins gesetzt ist)") ?: return null
            if (answer.remember) withContext(Dispatchers.IO) { vault.put(SecretNames.dbPassword(entry.id), answer.value) }
            return answer.value
        }
        val answer = secretPrompts.ask("Datenbank ${entry.name}", "Passwort für ${entry.username.ifBlank { "den Benutzer" }}") ?: return null
        if (answer.remember) withContext(Dispatchers.IO) { vault.put(SecretNames.dbPassword(entry.id), answer.value) }
        return answer.value
    }

    fun forgetHostKey(host: HostEntry) {
        scope.launch {
            val removed = withContext(Dispatchers.IO) { connections.knownHosts().forget(host.host, host.port) }
            notify(if (removed > 0) "Host-Schlüssel von ${host.host} entfernt" else "Kein gespeicherter Host-Schlüssel für ${host.host}")
        }
    }
}
