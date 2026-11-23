package dev.hiorcraft.openssh.ssh

import dev.hiorcraft.openssh.ssh.hostkeys.HostKeyCheck
import dev.hiorcraft.openssh.ssh.hostkeys.KnownHostsVerifier
import dev.hiorcraft.openssh.ssh.keys.KeyLoader
import dev.hiorcraft.openssh.ssh.testing.TestSshServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SshSessionTest {

    private val server = TestSshServer()

    @TempDir
    lateinit var dir: Path

    @BeforeAll
    fun start() {
        server.authorizedKeys += KeyLoader.load(TestKeys.resource("id_ed25519")).public
        server.start()
    }

    @AfterAll
    fun stop() = server.close()

    private fun spec(vararg auth: SshAuth) =
        ConnectionSpec("127.0.0.1", server.port, TestSshServer.USER, auth.toList(), connectTimeoutMillis = 5_000)

    private fun trustAll() = KnownHostsVerifier(dir.resolve("known_hosts_${System.nanoTime()}")) { true }

    @Test
    fun `Passwort-Login und exec`(): Unit = runBlocking {
        SshSession.connect(spec(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())), trustAll()).use { s ->
            assertTrue(s.isConnected)
            val ok = s.exec("echo hallo welt")
            assertEquals(0, ok.exitCode)
            assertEquals("hallo welt\n", ok.stdout)
            val failed = s.exec("fail")
            assertEquals(3, failed.exitCode)
            assertEquals("boom\n", failed.stderr)
            assertFalse(failed.isSuccess)
        }
    }

    @Test
    fun `falsches Passwort fuehrt zu AuthenticationFailedException`() {
        assertFailsWith<AuthenticationFailedException> {
            runBlocking { SshSession.connect(spec(SshAuth.Password("nope".toCharArray())), trustAll()) }
        }
    }

    @Test
    fun `Public-Key-Login, auch als Fallback nach falschem Passwort`(): Unit = runBlocking {
        val key = SshAuth.PrivateKey(TestKeys.resource("id_ed25519"))
        SshSession.connect(spec(SshAuth.Password("falsch".toCharArray()), key), trustAll()).use { s ->
            assertEquals("tester\n", s.exec("whoami").stdout)
        }
    }

    @Test
    fun `abgelehnter Host-Schluessel bricht ab`() {
        val prompts = mutableListOf<HostKeyCheck>()
        val verifier = KnownHostsVerifier(dir.resolve("kh_reject")) { prompts += it; false }
        assertFailsWith<HostKeyRejectedException> {
            runBlocking { SshSession.connect(spec(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())), verifier) }
        }
        assertEquals("[127.0.0.1]:${server.port}", prompts.single().host)
    }

    @Test
    fun `interaktive Shell mit PTY`(): Unit = runBlocking {
        SshSession.connect(spec(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())), trustAll()).use { s ->
            val shell = s.openShell(PtyRequest(columns = 80, rows = 24))
            val reader = shell.input.bufferedReader()
            assertEquals("welcome", reader.readLine())
            shell.output.write("ls -la\n".toByteArray()); shell.output.flush()
            assertEquals("> ls -la", reader.readLine())
            shell.resize(100, 40)
            shell.output.write("exit\n".toByteArray()); shell.output.flush()
            shell.awaitClose(5_000)
            shell.close()
        }
    }

    @Test
    fun `Local Port Forwarding leitet Verbindungen weiter`(): Unit = runBlocking {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { target ->
            thread(isDaemon = true) {
                target.accept().use { c ->
                    val line = c.getInputStream().bufferedReader().readLine()
                    c.getOutputStream().write("echo:$line\n".toByteArray())
                }
            }
            SshSession.connect(spec(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())), trustAll()).use { s ->
                val forward = s.forwardLocal("127.0.0.1", target.localPort)
                assertTrue(forward.localPort > 0)
                assertEquals(listOf(forward), s.activeForwards())
                Socket("127.0.0.1", forward.localPort).use { c ->
                    c.getOutputStream().write("ping\n".toByteArray())
                    assertEquals("echo:ping", c.getInputStream().bufferedReader().readLine())
                }
                forward.close()
                assertFalse(forward.isRunning)
                assertTrue(s.activeForwards().isEmpty())
            }
        }
    }

    @Test
    fun `Spec-Validierung`() {
        assertFailsWith<IllegalArgumentException> { ConnectionSpec("", 22, "u", listOf(SshAuth.Password(charArrayOf()))) }
        assertFailsWith<IllegalArgumentException> { ConnectionSpec("h", 0, "u", listOf(SshAuth.Password(charArrayOf()))) }
        assertFailsWith<IllegalArgumentException> { ConnectionSpec("h", 22, "u", emptyList()) }
        assertEquals("u@h:2200", ConnectionSpec("h", 2200, "u", listOf(SshAuth.Password(charArrayOf()))).displayName)
    }
}
