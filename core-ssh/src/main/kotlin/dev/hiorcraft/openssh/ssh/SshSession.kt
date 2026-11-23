package dev.hiorcraft.openssh.ssh

import dev.hiorcraft.openssh.ssh.keys.KeyLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Ergebnis eines einzelnen, nicht-interaktiven Befehls. */
data class ExecResult(val exitCode: Int?, val stdout: String, val stderr: String) {
    val isSuccess: Boolean get() = exitCode == 0
}

/** Parameter für das Pseudo-Terminal einer interaktiven Shell. */
data class PtyRequest(
    val term: String = "xterm-256color",
    val columns: Int = 120,
    val rows: Int = 32,
)

/**
 * Eine authentifizierte SSH-Verbindung. Auf einer Verbindung können parallel
 * mehrere Shells, SFTP-Clients und Port-Forwardings laufen.
 * Alle blockierenden Aufrufe laufen auf [Dispatchers.IO].
 */
class SshSession private constructor(
    val spec: ConnectionSpec,
    private val client: SSHClient,
) : Closeable {

    private val forwards = mutableListOf<PortForward>()

    val isConnected: Boolean get() = client.isConnected && client.isAuthenticated

    /** Führt einen Befehl aus und sammelt stdout/stderr. */
    suspend fun exec(command: String, timeoutSeconds: Long = 60): ExecResult = withContext(Dispatchers.IO) {
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val err = ByteArrayOutputStream()
            val errReader = thread(isDaemon = true, name = "ssh-exec-stderr") { cmd.errorStream.copyTo(err) }
            val out = runInterruptible { cmd.inputStream.readBytes() }
            errReader.join(TimeUnit.SECONDS.toMillis(timeoutSeconds))
            cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            ExecResult(cmd.exitStatus, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
        }
    }

    /** Öffnet eine interaktive Shell mit Pseudo-Terminal. */
    suspend fun openShell(pty: PtyRequest = PtyRequest()): ShellChannel = withContext(Dispatchers.IO) {
        val session = client.startSession()
        try {
            session.allocatePTY(pty.term, pty.columns, pty.rows, 0, 0, emptyMap())
            ShellChannel(session, session.startShell())
        } catch (e: Exception) {
            runCatching { session.close() }
            throw e.toSshException(spec)
        }
    }

    /** Öffnet einen neuen SFTP-Client auf dieser Verbindung. */
    suspend fun openSftp(): SFTPClient = withContext(Dispatchers.IO) {
        try {
            client.newSFTPClient()
        } catch (e: Exception) {
            throw SshException("SFTP-Subsystem nicht verfügbar: ${e.message}", e)
        }
    }

    /**
     * Startet ein Local Port Forwarding: `bindAddress:localPort` → (über SSH) → `remoteHost:remotePort`.
     * Mit `localPort = 0` wird ein freier Port gewählt, siehe [PortForward.localPort].
     */
    suspend fun forwardLocal(
        remoteHost: String,
        remotePort: Int,
        localPort: Int = 0,
        bindAddress: String = "127.0.0.1",
    ): PortForward = withContext(Dispatchers.IO) {
        val server = ServerSocket()
        try {
            server.reuseAddress = true
            server.bind(InetSocketAddress(bindAddress, localPort))
        } catch (e: Exception) {
            server.close()
            throw SshException("Lokaler Port $bindAddress:$localPort kann nicht geöffnet werden: ${e.message}", e)
        }
        val params = Parameters(bindAddress, server.localPort, remoteHost, remotePort)
        val forwarder = client.newLocalPortForwarder(params, server)
        val forward = PortForward(bindAddress, server.localPort, remoteHost, remotePort, server, forwarder) {
            synchronized(forwards) { forwards.remove(it) }
        }
        synchronized(forwards) { forwards += forward }
        forward.start()
        forward
    }

    fun activeForwards(): List<PortForward> = synchronized(forwards) { forwards.toList() }

    override fun close() {
        activeForwards().forEach { runCatching { it.close() } }
        runCatching { client.disconnect() }
    }

    companion object {
        /** Baut die Verbindung auf, prüft den Host-Schlüssel und authentifiziert. */
        suspend fun connect(spec: ConnectionSpec, hostKeyVerifier: HostKeyVerifier): SshSession =
            withContext(Dispatchers.IO) {
                val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
                val client = SSHClient(config)
                client.addHostKeyVerifier(hostKeyVerifier)
                client.connectTimeout = spec.connectTimeoutMillis
                try {
                    runInterruptible { client.connect(spec.host, spec.port) }
                    if (spec.keepAliveSeconds > 0) {
                        client.connection.keepAlive.keepAliveInterval = spec.keepAliveSeconds
                    }
                    client.auth(spec.username, authMethods(spec))
                    SshSession(spec, client)
                } catch (e: Throwable) {
                    runCatching { client.disconnect() }
                    throw e.toSshException(spec)
                }
            }

        private fun authMethods(spec: ConnectionSpec): List<AuthMethod> = spec.auths.flatMap { auth ->
            when (auth) {
                is SshAuth.Password -> {
                    val finder = staticPassword(auth.password)
                    listOf(AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
                }
                is SshAuth.PrivateKey -> listOf(AuthPublickey(KeyLoader.load(auth.keyText, auth.passphrase)))
                is SshAuth.KeyFile -> listOf(AuthPublickey(KeyLoader.load(auth.path, auth.passphrase)))
            }
        }

        private fun staticPassword(password: CharArray) = object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = password.copyOf()
            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
    }
}

/** Eine interaktive Shell. [input] liefert die Ausgabe des Servers, [output] nimmt Tastatureingaben auf. */
class ShellChannel internal constructor(
    private val session: net.schmizz.sshj.connection.channel.direct.Session,
    private val shell: net.schmizz.sshj.connection.channel.direct.Session.Shell,
) : Closeable {
    val input: InputStream get() = shell.inputStream
    val output: java.io.OutputStream get() = shell.outputStream

    val isOpen: Boolean get() = shell.isOpen

    fun resize(columns: Int, rows: Int, widthPx: Int = 0, heightPx: Int = 0) {
        if (shell.isOpen) shell.changeWindowDimensions(columns, rows, widthPx, heightPx)
    }

    /** Blockiert, bis der Server die Shell schließt. */
    fun awaitClose(timeoutMillis: Long = 0) {
        if (timeoutMillis > 0) shell.join(timeoutMillis, TimeUnit.MILLISECONDS) else shell.join()
    }

    override fun close() {
        runCatching { shell.close() }
        runCatching { session.close() }
    }
}

/** Ein laufendes Local Port Forwarding. */
class PortForward internal constructor(
    val bindAddress: String,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    private val server: ServerSocket,
    private val forwarder: net.schmizz.sshj.connection.channel.direct.LocalPortForwarder,
    private val onClose: (PortForward) -> Unit,
) : Closeable {
    @Volatile
    var lastError: Throwable? = null
        private set

    val isRunning: Boolean get() = !server.isClosed

    internal fun start() {
        thread(isDaemon = true, name = "ssh-forward-$localPort") {
            try {
                forwarder.listen()
            } catch (e: Exception) {
                if (!server.isClosed) lastError = e
            }
        }
    }

    override fun close() {
        runCatching { forwarder.close() }
        runCatching { server.close() }
        onClose(this)
    }

    override fun toString() = "$bindAddress:$localPort → $remoteHost:$remotePort"
}
