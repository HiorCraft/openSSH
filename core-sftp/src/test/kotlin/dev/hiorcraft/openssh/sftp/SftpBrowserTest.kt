package dev.hiorcraft.openssh.sftp

import dev.hiorcraft.openssh.ssh.ConnectionSpec
import dev.hiorcraft.openssh.ssh.SshAuth
import dev.hiorcraft.openssh.ssh.SshException
import dev.hiorcraft.openssh.ssh.SshSession
import dev.hiorcraft.openssh.ssh.hostkeys.KnownHostsVerifier
import dev.hiorcraft.openssh.ssh.testing.TestSshServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SftpBrowserTest {

    private val server = TestSshServer()
    private lateinit var session: SshSession
    private lateinit var browser: SftpBrowser

    @TempDir
    lateinit var local: Path

    @BeforeAll
    fun startServer() {
        server.start()
    }

    @AfterAll
    fun stopServer() = server.close()

    @BeforeEach
    fun connect(): Unit = runBlocking {
        val spec = ConnectionSpec("127.0.0.1", server.port, TestSshServer.USER, listOf(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())))
        session = SshSession.connect(spec, KnownHostsVerifier(Files.createTempFile("kh", "")) { true })
        browser = SftpBrowser.open(session)
        server.sftpRoot.toFile().listFiles()?.forEach { it.deleteRecursively() }
    }

    @AfterEach
    fun disconnect() {
        browser.close()
        session.close()
    }

    @Test
    fun `listet Verzeichnisse zuerst, dann Dateien alphabetisch`(): Unit = runBlocking {
        Files.createDirectory(server.sftpRoot.resolve("zeta"))
        server.sftpRoot.resolve("b.txt").writeText("bb")
        server.sftpRoot.resolve("A.txt").writeText("a")
        val entries = browser.list("/")
        assertEquals(listOf("zeta", "A.txt", "b.txt"), entries.map { it.name })
        assertTrue(entries[0].isDirectory)
        assertEquals(2, entries.first { it.name == "b.txt" }.size)
        assertEquals("/b.txt", entries.first { it.name == "b.txt" }.path)
    }

    @Test
    fun `mkdir, rename, stat und rekursives Loeschen`(): Unit = runBlocking {
        browser.mkdirs("/a/b/c")
        browser.writeText("/a/b/c/f.txt", "inhalt")
        assertEquals("inhalt", browser.readText("/a/b/c/f.txt"))
        browser.rename("/a", "/x")
        assertNull(browser.stat("/a"))
        assertTrue(browser.exists("/x/b/c/f.txt"))
        assertFailsWith<SshException> { browser.delete("/x") }
        browser.delete("/x", recursive = true)
        assertFalse(browser.exists("/x"))
    }

    @Test
    fun `Upload und Download einer Datei mit Fortschritt`(): Unit = runBlocking {
        val data = Random(42).nextBytes(300_000)
        val file = local.resolve("daten.bin").also { it.writeBytes(data) }
        val progress = mutableListOf<TransferProgress>()

        val remote = browser.upload(file, "/") { progress += it }
        assertEquals("/daten.bin", remote)
        assertContentEquals(data, Files.readAllBytes(server.sftpRoot.resolve("daten.bin")))
        assertEquals(300_000, progress.last().bytesDone)
        assertEquals(1, progress.last().filesDone)
        assertEquals(1f, progress.last().fraction)

        val downloadDir = local.resolve("dl")
        val downloaded = browser.download("/daten.bin", downloadDir)
        assertContentEquals(data, Files.readAllBytes(downloaded))
        assertFalse(Files.exists(downloadDir.resolve("daten.bin.part")))
    }

    @Test
    fun `Ordner werden rekursiv hoch- und heruntergeladen`(): Unit = runBlocking {
        val dir = local.resolve("projekt")
        Files.createDirectories(dir.resolve("src/main"))
        Files.createDirectories(dir.resolve("leer"))
        dir.resolve("README.md").writeText("# Hallo")
        dir.resolve("src/main/App.kt").writeText("fun main() {}")

        val progress = mutableListOf<TransferProgress>()
        browser.upload(dir, "/") { progress += it }
        assertEquals("fun main() {}", server.sftpRoot.resolve("projekt/src/main/App.kt").readText())
        assertTrue(Files.isDirectory(server.sftpRoot.resolve("projekt/leer")))
        assertEquals(2, progress.last().filesTotal)

        val back = browser.download("/projekt", local.resolve("zurueck"))
        assertEquals("# Hallo", back.resolve("README.md").readText())
        assertEquals("fun main() {}", back.resolve("src/main/App.kt").readText())
        assertTrue(Files.isDirectory(back.resolve("leer")))
    }

    @Test
    fun `TransferQueue verarbeitet Drops und meldet Status`(): Unit = runBlocking {
        val f1 = local.resolve("eins.txt").also { it.writeText("1") }
        val f2 = local.resolve("zwei.txt").also { it.writeText("22") }
        val finished = CompletableDeferred<Unit>()
        val done = mutableListOf<Transfer>()
        val queue = TransferQueue(this, browser, parallelism = 2) {
            synchronized(done) { done += it; if (done.size == 2) finished.complete(Unit) }
        }
        queue.enqueueUploads(listOf(f1, f2), "/")
        withTimeout(10_000) { finished.await() }
        assertTrue(done.all { it.state == TransferState.Done }, done.toString())
        assertEquals("22", server.sftpRoot.resolve("zwei.txt").readText())

        queue.clearFinished()
        assertTrue(queue.transfers.value.isEmpty())

        val failed = queue.enqueueDownloads(listOf("/gibt-es-nicht"), local).single()
        val state = withTimeout(10_000) {
            queue.transfers.first { list -> list.any { it.id == failed.id && it.isFinished } }.first { it.id == failed.id }.state
        }
        assertIs<TransferState.Failed>(state)
    }

    @Test
    fun `abgebrochener Upload hinterlaesst keine halbe Datei`(): Unit = runBlocking {
        val big = local.resolve("gross.bin").also { it.writeBytes(ByteArray(20_000_000)) }
        val started = CompletableDeferred<Unit>()
        val job = async {
            browser.upload(big, "/") { if (it.bytesDone > 0) started.complete(Unit) }
        }
        withTimeout(10_000) { started.await() }
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertFalse(Files.exists(server.sftpRoot.resolve("gross.bin")))
    }
}
