package dev.hiorcraft.openssh.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue
import dev.hiorcraft.openssh.app.data.DbEntry
import dev.hiorcraft.openssh.app.data.DbKind
import dev.hiorcraft.openssh.app.data.HostEntry
import dev.hiorcraft.openssh.app.data.SecretNames
import dev.hiorcraft.openssh.app.data.newId
import dev.hiorcraft.openssh.db.ColumnMeta
import dev.hiorcraft.openssh.db.DatabaseClient
import dev.hiorcraft.openssh.db.DbConnectionSpec
import dev.hiorcraft.openssh.db.DbType
import dev.hiorcraft.openssh.db.StatementResult
import dev.hiorcraft.openssh.db.TableInfo
import dev.hiorcraft.openssh.db.redis.RedisBrowser
import dev.hiorcraft.openssh.db.redis.RedisKeyDetails
import dev.hiorcraft.openssh.db.redis.RedisSpec
import dev.hiorcraft.openssh.sftp.SftpBrowser
import dev.hiorcraft.openssh.ssh.PortForward
import dev.hiorcraft.openssh.ssh.PtyRequest
import dev.hiorcraft.openssh.ssh.SshSession
import dev.hiorcraft.openssh.terminal.ShellProfile
import dev.hiorcraft.openssh.terminal.SshTtyConnector
import dev.hiorcraft.openssh.terminal.TerminalController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.concurrent.thread

sealed interface TabStatus {
    data object Connecting : TabStatus
    data object Ready : TabStatus
    data class Failed(val message: String) : TabStatus
    data object Closed : TabStatus
}

/** Ein offener Tab mit eigenem Lebenszyklus. [close] gibt alle Ressourcen frei. */
sealed class SessionTab(protected val app: AppState) {
    val id: String = newId()
    var title by mutableStateOf("")
    var status by mutableStateOf<TabStatus>(TabStatus.Connecting)
        protected set

    protected val scope = CoroutineScope(SupervisorJob(app.scope.coroutineContext.job) + Dispatchers.Main)

    abstract fun start()

    open fun close() {
        status = TabStatus.Closed
        scope.cancel()
    }

    protected fun fail(e: Throwable) {
        status = if (e is ConnectionCancelledException) TabStatus.Failed("Abgebrochen") else TabStatus.Failed(e.message ?: e.javaClass.simpleName)
    }

    protected fun closeQuietly(resource: AutoCloseable?) {
        if (resource == null) return
        app.scope.launch(Dispatchers.IO) { runCatching { resource.close() } }
    }
}

/** Gemeinsame Logik für Tabs, die eine SSH-Verbindung zu einem Host halten. */
sealed class SshTab(app: AppState, val host: HostEntry) : SessionTab(app) {
    var session by mutableStateOf<SshSession?>(null)
        private set
    val forwards = mutableStateListOf<PortForward>()

    protected suspend fun openSession(): SshSession {
        session?.let { if (it.isConnected) return it }
        val s = app.connections.connect(host)
        session = s
        return s
    }

    fun addForward(localPort: Int, remoteHost: String, remotePort: Int) { scope.launch {
        val s = session ?: return@launch app.notify("Keine aktive Verbindung")
        try {
            val f = s.forwardLocal(remoteHost, remotePort, localPort)
            forwards += f
            app.notify("Tunnel aktiv: $f")
        } catch (e: Exception) {
            app.notify("Tunnel fehlgeschlagen: ${e.message}")
        }
    } }

    fun removeForward(forward: PortForward) {
        forwards.remove(forward)
        closeQuietly(forward)
    }

    override fun close() {
        super.close()
        forwards.clear()
        closeQuietly(session)
        session = null
    }
}

class TerminalTab private constructor(
    app: AppState,
    host: HostEntry?,
    private val profile: ShellProfile?,
) : SessionTab(app) {
    val sshHost: HostEntry? = host
    var controller by mutableStateOf<TerminalController?>(null)
        private set
    private var session: SshSession? = null
    val forwards = mutableStateListOf<PortForward>()

    val isLocal: Boolean get() = sshHost == null

    init {
        title = host?.label ?: profile!!.displayName
    }

    override fun start() {
        status = TabStatus.Connecting
        scope.launch {
            try {
                val settings = app.terminalSettings()
                val connector = if (sshHost != null) {
                    val s = app.connections.connect(sshHost)
                    session = s
                    val shell = s.openShell(PtyRequest(columns = 120, rows = 32))
                    SshTtyConnector(shell, sshHost.label) { scope.launch(Dispatchers.Main) { status = TabStatus.Closed } }
                } else {
                    val c = withContext(Dispatchers.IO) { app.localShells.start(profile!!) }
                    thread(isDaemon = true, name = "local-shell-wait") {
                        runCatching { c.process.waitFor() }
                        scope.launch(Dispatchers.Main) { status = TabStatus.Closed }
                    }
                    c
                }
                val ctrl = TerminalController(settings, connector)
                controller?.let { old -> closeQuietly(old) }
                controller = ctrl
                status = TabStatus.Ready
                val startup = sshHost?.startupCommand.orEmpty()
                if (startup.isNotBlank()) {
                    delay(400)
                    ctrl.send(startup, execute = true)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    /** Verbindet einen geschlossenen oder fehlgeschlagenen Tab neu. */
    fun restart() {
        controller?.let { closeQuietly(it) }
        controller = null
        closeQuietly(session)
        session = null
        start()
    }

    fun addForward(localPort: Int, remoteHost: String, remotePort: Int) { scope.launch {
        val s = session ?: return@launch app.notify("Keine aktive SSH-Verbindung")
        try {
            val f = s.forwardLocal(remoteHost, remotePort, localPort)
            forwards += f
            app.notify("Tunnel aktiv: $f")
        } catch (e: Exception) {
            app.notify("Tunnel fehlgeschlagen: ${e.message}")
        }
    } }

    fun removeForward(forward: PortForward) {
        forwards.remove(forward)
        closeQuietly(forward)
    }

    fun send(text: String, execute: Boolean) {
        controller?.send(text, execute)
        controller?.focus()
    }

    override fun close() {
        super.close()
        closeQuietly(controller)
        controller = null
        closeQuietly(session)
        session = null
    }

    companion object {
        fun ssh(app: AppState, host: HostEntry) = TerminalTab(app, host, null)
        fun local(app: AppState, profile: ShellProfile) = TerminalTab(app, null, profile)
    }
}

class SftpTab(app: AppState, host: HostEntry) : SshTab(app, host) {
    var sftp by mutableStateOf<SftpState?>(null)
        private set

    init {
        title = "SFTP · ${host.label}"
    }

    override fun start() {
        status = TabStatus.Connecting
        scope.launch {
            try {
                val s = openSession()
                val state = SftpState(scope, SftpBrowser.open(s), app::notify)
                sftp = state
                state.start()
                status = TabStatus.Ready
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    override fun close() {
        sftp?.close()
        sftp = null
        super.close()
    }
}

/** Terminal und SFTP nebeneinander, über dieselbe SSH-Verbindung. */
class SplitTab(app: AppState, host: HostEntry) : SshTab(app, host) {
    var controller by mutableStateOf<TerminalController?>(null)
        private set
    var sftp by mutableStateOf<SftpState?>(null)
        private set
    var splitFraction by mutableStateOf(0.55f)

    init {
        title = "${host.label} · Split"
    }

    override fun start() {
        status = TabStatus.Connecting
        scope.launch {
            try {
                val s = openSession()
                val shell = s.openShell()
                controller = TerminalController(app.terminalSettings(), SshTtyConnector(shell, host.label) {
                    scope.launch(Dispatchers.Main) { status = TabStatus.Closed }
                })
                val state = SftpState(scope, SftpBrowser.open(s), app::notify)
                sftp = state
                state.start()
                status = TabStatus.Ready
                if (host.startupCommand.isNotBlank()) {
                    delay(400)
                    controller?.send(host.startupCommand, execute = true)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun send(text: String, execute: Boolean) {
        controller?.send(text, execute)
        controller?.focus()
    }

    override fun close() {
        closeQuietly(controller)
        controller = null
        sftp?.close()
        sftp = null
        super.close()
    }
}

class DatabaseTab(app: AppState, val entry: DbEntry) : SessionTab(app) {
    var client by mutableStateOf<DatabaseClient?>(null)
        private set
    private var tunnelSession: SshSession? = null

    var serverVersion by mutableStateOf("")
        private set
    var schemas by mutableStateOf<List<String>>(emptyList())
        private set
    var schema by mutableStateOf<String?>(null)
        private set
    var tables by mutableStateOf<List<TableInfo>>(emptyList())
        private set
    val columns = mutableStateMapOf<String, List<ColumnMeta>>()
    var query by mutableStateOf(TextFieldValue(""))
    var results by mutableStateOf<List<StatementResult>>(emptyList())
        private set
    var selectedResult by mutableStateOf(0)
    var queryError by mutableStateOf<String?>(null)
        private set
    var running by mutableStateOf(false)
        private set
    private var queryJob: Job? = null

    init {
        title = entry.name
    }

    override fun start() {
        status = TabStatus.Connecting
        scope.launch {
            try {
                val tunnel = entry.sshHostId?.let { id ->
                    val host = app.hosts.get(id) ?: error("Der SSH-Host für den Tunnel existiert nicht mehr")
                    app.connections.connect(host).also { tunnelSession = it }
                }
                val password = app.dbPassword(entry) ?: ""
                val type = when (entry.kind) {
                    DbKind.MARIADB -> DbType.MARIADB
                    DbKind.POSTGRESQL -> DbType.POSTGRESQL
                    DbKind.SQLITE -> DbType.SQLITE
                    DbKind.REDIS -> error("Redis wird im Redis-Tab geöffnet")
                }
                val spec = DbConnectionSpec(
                    type = type,
                    host = entry.host,
                    port = entry.port,
                    database = entry.database,
                    username = entry.username,
                    password = password,
                    sqliteFile = entry.sqlitePath.takeIf { it.isNotBlank() }?.let(Path::of),
                )
                val c = DatabaseClient.connect(spec, tunnel)
                client = c
                serverVersion = runCatching { c.serverVersion() }.getOrDefault("")
                schemas = c.schemas()
                val default = c.defaultSchema()
                status = TabStatus.Ready
                selectSchema(default ?: schemas.firstOrNull())
                if (query.text.isEmpty()) query = TextFieldValue("-- Strg+Enter führt die Abfrage (oder die Auswahl) aus\n")
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun selectSchema(name: String?) {
        schema = name
        columns.clear()
        scope.launch {
            tables = runCatching { client?.tables(name).orEmpty() }.getOrElse {
                app.notify("Tabellen konnten nicht geladen werden: ${it.message}"); emptyList()
            }
        }
    }

    fun refreshTables() = selectSchema(schema)

    fun toggleColumns(table: TableInfo) {
        if (columns.containsKey(table.name)) {
            columns.remove(table.name)
            return
        }
        scope.launch {
            runCatching { client?.columns(schema, table.name).orEmpty() }
                .onSuccess { columns[table.name] = it }
                .onFailure { app.notify("Spalten konnten nicht geladen werden: ${it.message}") }
        }
    }

    fun previewTable(table: TableInfo) {
        val c = client ?: return
        val sql = dev.hiorcraft.openssh.db.SqlDialect.selectPreview(c.type, schema, table.name, 200)
        query = TextFieldValue(sql)
        run(sql)
    }

    /** Führt die Auswahl im Editor aus oder, ohne Auswahl, den ganzen Text. */
    fun runEditor() {
        val v = query
        val text = if (v.selection.collapsed) v.text else v.text.substring(v.selection.min, v.selection.max)
        run(text)
    }

    private fun run(sql: String) {
        val c = client ?: return
        if (sql.isBlank() || running) return
        queryJob = scope.launch {
            running = true
            queryError = null
            try {
                results = c.execute(sql, app.settings.value.maxRows)
                selectedResult = results.indexOfLast { it is StatementResult.Table }.coerceAtLeast(0)
                if (results.any { it is StatementResult.Update }) refreshTables()
            } catch (e: Exception) {
                queryError = e.message ?: e.javaClass.simpleName
            } finally {
                running = false
            }
        }
    }

    fun cancelQuery() {
        client?.cancelRunning()
        queryJob?.cancel()
    }

    fun restart() {
        closeQuietly(client)
        client = null
        closeQuietly(tunnelSession)
        tunnelSession = null
        start()
    }

    override fun close() {
        super.close()
        closeQuietly(client)
        client = null
        closeQuietly(tunnelSession)
        tunnelSession = null
    }
}

class RedisTab(app: AppState, val entry: DbEntry) : SessionTab(app) {
    var browser by mutableStateOf<RedisBrowser?>(null)
        private set
    private var tunnelSession: SshSession? = null

    var pattern by mutableStateOf("*")
    var keys by mutableStateOf<List<String>>(emptyList())
        private set
    var cursor by mutableStateOf("0")
        private set
    var finished by mutableStateOf(true)
        private set
    var loading by mutableStateOf(false)
        private set
    var details by mutableStateOf<RedisKeyDetails?>(null)
        private set
    var dbSize by mutableStateOf(0L)
        private set
    var serverVersion by mutableStateOf("")
        private set

    init {
        title = entry.name
    }

    override fun start() {
        status = TabStatus.Connecting
        scope.launch {
            try {
                val tunnel = entry.sshHostId?.let { id ->
                    val host = app.hosts.get(id) ?: error("Der SSH-Host für den Tunnel existiert nicht mehr")
                    app.connections.connect(host).also { tunnelSession = it }
                }
                val spec = RedisSpec(
                    host = entry.host,
                    port = entry.port,
                    username = entry.username,
                    password = app.dbPassword(entry) ?: "",
                    database = entry.redisDb,
                    useTls = entry.useTls,
                )
                val b = RedisBrowser.connect(spec, tunnel)
                b.ping()
                browser = b
                serverVersion = b.serverInfo()["redis_version"].orEmpty()
                status = TabStatus.Ready
                search()
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    fun search() {
        val b = browser ?: return
        scope.launch {
            loading = true
            try {
                val page = b.searchKeys(pattern.ifBlank { "*" }, 1000)
                keys = page.keys.sorted()
                cursor = page.cursor
                finished = page.finished
                dbSize = b.dbSize()
            } catch (e: Exception) {
                app.notify(e.message ?: "Redis-Fehler")
            } finally {
                loading = false
            }
        }
    }

    /** Lädt weitere Keys ab dem letzten Cursor. */
    fun loadMore() {
        val b = browser ?: return
        if (finished) return
        scope.launch {
            loading = true
            try {
                var c = cursor
                val more = LinkedHashSet<String>()
                do {
                    val page = b.scan(pattern.ifBlank { "*" }, c, 500)
                    more += page.keys
                    c = page.cursor
                    finished = page.finished
                } while (!page.finished && more.size < 1000)
                cursor = c
                keys = (keys + more).distinct().sorted()
            } catch (e: Exception) {
                app.notify(e.message ?: "Redis-Fehler")
            } finally {
                loading = false
            }
        }
    }

    fun select(key: String) {
        val b = browser ?: return
        scope.launch {
            details = runCatching { b.inspect(key) }.getOrElse { app.notify(it.message ?: "Fehler"); null }
        }
    }

    fun saveString(key: String, value: String) {
        val b = browser ?: return
        scope.launch {
            runCatching { b.setString(key, value) }.onFailure { app.notify(it.message ?: "Fehler") }
            select(key)
        }
    }

    fun setTtl(key: String, seconds: Long) {
        val b = browser ?: return
        scope.launch {
            runCatching { b.expire(key, seconds) }.onFailure { app.notify(it.message ?: "Fehler") }
            select(key)
        }
    }

    fun delete(key: String) {
        val b = browser ?: return
        scope.launch {
            runCatching { b.delete(key) }.onFailure { app.notify(it.message ?: "Fehler") }
            keys = keys - key
            details = null
        }
    }

    fun restart() {
        closeQuietly(browser)
        browser = null
        closeQuietly(tunnelSession)
        tunnelSession = null
        start()
    }

    override fun close() {
        super.close()
        closeQuietly(browser)
        browser = null
        closeQuietly(tunnelSession)
        tunnelSession = null
    }
}
