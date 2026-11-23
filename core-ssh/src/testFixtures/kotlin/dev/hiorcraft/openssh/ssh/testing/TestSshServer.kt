package dev.hiorcraft.openssh.ssh.testing

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.Environment
import org.apache.sshd.server.ExitCallback
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.channel.ChannelSession
import org.apache.sshd.server.command.Command
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey
import kotlin.concurrent.thread

/**
 * Eingebetteter SSH-Server für Integrationstests.
 *
 * - Passwort-Login: [USER] / [PASSWORD]
 * - Public-Key-Login: alle Schlüssel in [authorizedKeys]
 * - Exec: `echo <text>`, `fail` (stderr + Exit-Code 3), `whoami`
 * - Shell: schreibt "welcome", spiegelt jede Zeile als `> zeile` zurück, `exit` beendet sie
 * - SFTP mit [sftpRoot] als Wurzelverzeichnis, Local Port Forwarding erlaubt
 */
class TestSshServer(
    val sftpRoot: Path = Files.createTempDirectory("openssh-sftp"),
    hostKeyFile: Path = Files.createTempFile("openssh-hostkey", ".ser").also { Files.delete(it) },
) : Closeable {
    val authorizedKeys: MutableSet<PublicKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private val server: SshServer = SshServer.setUpDefaultServer().apply {
        host = "127.0.0.1"
        port = 0
        keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile)
        setPasswordAuthenticator { user, password, _ -> user == USER && password == PASSWORD }
        setPublickeyAuthenticator { user, key, _ -> user == USER && authorizedKeys.any { it == key } }
        setCommandFactory { _, command -> ExecCommand(command) }
        setShellFactory { EchoShell() }
        subsystemFactories = listOf(SftpSubsystemFactory())
        fileSystemFactory = VirtualFileSystemFactory(sftpRoot)
        forwardingFilter = AcceptAllForwardingFilter.INSTANCE
    }

    val port: Int get() = server.port

    fun start(port: Int = 0): TestSshServer = apply {
        server.port = port
        server.start()
    }

    override fun close() {
        server.stop(true)
    }

    companion object {
        const val USER = "tester"
        const val PASSWORD = "s3cret!"
    }
}

private abstract class StreamCommand : Command {
    protected lateinit var input: InputStream
    protected lateinit var out: OutputStream
    protected lateinit var err: OutputStream
    protected lateinit var exit: ExitCallback

    override fun setInputStream(`in`: InputStream) { input = `in` }
    override fun setOutputStream(out: OutputStream) { this.out = out }
    override fun setErrorStream(err: OutputStream) { this.err = err }
    override fun setExitCallback(callback: ExitCallback) { exit = callback }
    override fun destroy(channel: ChannelSession) = Unit
}

private class ExecCommand(private val command: String) : StreamCommand() {
    override fun start(channel: ChannelSession, env: Environment) {
        thread(isDaemon = true) {
            val code = when {
                command.startsWith("echo ") -> { out.write((command.removePrefix("echo ") + "\n").toByteArray()); 0 }
                command == "whoami" -> { out.write((channel.session.username + "\n").toByteArray()); 0 }
                command == "fail" -> { err.write("boom\n".toByteArray()); 3 }
                else -> { err.write("unknown command\n".toByteArray()); 127 }
            }
            out.flush()
            err.flush()
            exit.onExit(code)
        }
    }
}

private class EchoShell : StreamCommand() {
    override fun start(channel: ChannelSession, env: Environment) {
        thread(isDaemon = true) {
            out.write("welcome\r\n".toByteArray()); out.flush()
            val reader = input.bufferedReader()
            while (true) {
                val line = reader.readLine()?.trimEnd('\r') ?: break
                if (line == "exit") break
                out.write("> $line\r\n".toByteArray()); out.flush()
            }
            exit.onExit(0)
        }
    }
}
