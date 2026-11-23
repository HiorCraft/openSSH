package dev.hiorcraft.openssh.ssh.hostkeys

import dev.hiorcraft.openssh.ssh.TestKeys
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnownHostsVerifierTest {

    @TempDir
    lateinit var dir: Path

    private val file get() = dir.resolve("sub/known_hosts")

    @Test
    fun `unbekannter Host wird abgelehnt, wenn der Benutzer ablehnt`() {
        val prompts = mutableListOf<HostKeyCheck>()
        val verifier = KnownHostsVerifier(file) { prompts += it; false }
        val key = TestKeys.rsaKeyPair().public

        assertFalse(verifier.verify("example.org", 22, key))
        assertEquals(1, prompts.size)
        assertEquals(HostKeyCheck.Kind.UNKNOWN, prompts.single().kind)
        assertEquals(Fingerprints.sha256(key), prompts.single().fingerprint)
        assertTrue(Files.notExists(file) || Files.readString(file).isBlank())
    }

    @Test
    fun `akzeptierter Host wird gespeichert und danach ohne Rueckfrage vertraut`() {
        val key = TestKeys.rsaKeyPair().public
        var prompts = 0
        assertTrue(KnownHostsVerifier(file) { prompts++; true }.verify("example.org", 2222, key))
        assertTrue(Files.readString(file).startsWith("[example.org]:2222 ssh-rsa "))

        // Neue Instanz liest die Datei erneut ein
        val reloaded = KnownHostsVerifier(file) { error("Es darf keine Rückfrage geben") }
        assertTrue(reloaded.verify("example.org", 2222, key))
        assertEquals(1, prompts)
    }

    @Test
    fun `geaenderter Schluessel loest eine Warnung aus und ersetzt den alten Eintrag`() {
        val oldKey = TestKeys.rsaKeyPair().public
        val newKey = TestKeys.rsaKeyPair().public
        KnownHostsVerifier(file) { true }.verify("srv", 22, oldKey)

        val prompts = mutableListOf<HostKeyCheck>()
        val verifier = KnownHostsVerifier(file) { prompts += it; true }
        assertTrue(verifier.verify("srv", 22, newKey))
        assertEquals(HostKeyCheck.Kind.CHANGED, prompts.single().kind)

        val lines = Files.readAllLines(file).filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(KnownHostsVerifier(file) { false }.verify("srv", 22, newKey))
        assertFalse(KnownHostsVerifier(file) { false }.verify("srv", 22, oldKey))
    }

    @Test
    fun `forget entfernt Eintraege`() {
        val key = TestKeys.rsaKeyPair().public
        val verifier = KnownHostsVerifier(file) { true }
        verifier.verify("a.example", 22, key)
        verifier.verify("b.example", 22, key)
        assertEquals(1, verifier.forget("a.example"))
        val asks = mutableListOf<String>()
        val reloaded = KnownHostsVerifier(file) { asks += it.host; false }
        assertFalse(reloaded.verify("a.example", 22, key))
        assertTrue(reloaded.verify("b.example", 22, key))
        assertEquals(listOf("a.example"), asks)
    }
}
