package dev.hiorcraft.openssh.vault

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verhalten, das jede [SecretVault]-Implementierung erfüllen muss. */
abstract class SecretVaultContract {
    abstract fun vault(): SecretVault

    @Test
    fun `speichern, lesen, ueberschreiben, loeschen`() {
        val v = vault()
        assertNull(v.get("host:1:password"))
        v.put("host:1:password", "geheim")
        assertEquals("geheim", v.get("host:1:password"))
        assertTrue(v.contains("host:1:password"))
        v.put("host:1:password", "neu")
        assertEquals("neu", v.get("host:1:password"))
        v.delete("host:1:password")
        assertNull(v.get("host:1:password"))
        v.delete("host:1:password") // doppeltes Löschen ist kein Fehler
    }

    @Test
    fun `Unicode, leere Werte und Auflistung`() {
        val v = vault()
        v.put("a", "Pässwörd 🔑 ß")
        v.put("b", "")
        v.put("c:x", "3")
        assertEquals("Pässwörd 🔑 ß", v.get("a"))
        assertEquals("", v.get("b"))
        assertEquals(setOf("a", "b", "c:x"), v.keys())
        v.deleteByPrefix("c:")
        assertEquals(setOf("a", "b"), v.keys())
    }
}

class InMemoryVaultTest : SecretVaultContract() {
    override fun vault() = InMemoryVault()
}

/** Umkehrbare Test-Verschlüsselung, damit der Datei-Tresor auch ohne Windows getestet werden kann. */
private object XorCipher : SecretCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    override fun decrypt(encrypted: ByteArray) = encrypt(encrypted)
}

class DpapiFileVaultTest : SecretVaultContract() {
    @TempDir
    lateinit var dir: Path

    override fun vault() = DpapiFileVault(dir.resolve("secrets"), XorCipher)

    @Test
    fun `Klartext steht nicht in der Datei und Dateiname verraet den Schluessel nicht`() {
        val v = vault()
        v.put("host:geheimer-server:password", "supergeheim")
        val file = dir.resolve("secrets").listDirectoryEntries().single()
        assertFalse(file.fileName.toString().contains("geheimer"))
        assertFalse(String(Files.readAllBytes(file)).contains("supergeheim"))
    }

    @Test
    fun `beschaedigte Datei wird gemeldet`() {
        val failing = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(encrypted: ByteArray): ByteArray = throw IllegalStateException("kaputt")
        }
        val v = DpapiFileVault(dir.resolve("s2"), failing)
        v.put("k", "v")
        assertFailsWith<VaultException> { v.get("k") }
        assertEquals(emptySet(), v.keys())
    }
}

class WindowsVaultRoutingTest {
    @Test
    fun `grosse Geheimnisse landen im Datei-Tresor, kleine im Credential Manager`() {
        val small = InMemoryVault()
        val large = InMemoryVault()
        val v = WindowsVault(small, large, smallLimitBytes = 10)
        v.put("k", "kurz")
        assertEquals("kurz", small.get("k"))
        v.put("k", "x".repeat(50))
        assertNull(small.get("k"))
        assertEquals("x".repeat(50), v.get("k"))
        v.put("k", "kurz")
        assertNull(large.get("k"))
        assertEquals(setOf("k"), v.keys())
        v.delete("k")
        assertNull(v.get("k"))
    }
}

@EnabledOnOs(OS.WINDOWS)
class RealDpapiVaultTest : SecretVaultContract() {
    @TempDir
    lateinit var dir: Path

    override fun vault() = DpapiFileVault(dir.resolve("secrets"), DpapiCipher("OpenSSHTest"))

    @Test
    fun `DPAPI-Daten sind an die Entropie gebunden`() {
        val encrypted = DpapiCipher("A").encrypt("hallo".toByteArray())
        assertEquals("hallo", String(DpapiCipher("A").decrypt(encrypted)))
        assertFailsWith<Exception> { DpapiCipher("B").decrypt(encrypted) }
    }
}

@EnabledOnOs(OS.WINDOWS)
class RealCredentialManagerVaultTest : SecretVaultContract() {
    private val namespace = "OpenSSHTest-" + UUID.randomUUID().toString().take(8)
    private val created = mutableListOf<CredentialManagerVault>()

    override fun vault() = CredentialManagerVault(namespace).also { created += it }

    @AfterEach
    fun cleanup() = created.forEach { v -> v.keys().forEach(v::delete) }

    @Test
    fun `zu grosse Geheimnisse werden abgelehnt`() {
        assertFailsWith<VaultException> { vault().put("big", "x".repeat(CredentialManagerVault.MAX_BLOB + 1)) }
    }

    @Test
    fun `kombinierter Windows-Tresor speichert einen 4096-Bit-Schluessel`(@TempDir dir: Path) {
        val v = WindowsVault(vault(), DpapiFileVault(dir, DpapiCipher(namespace)))
        val bigKey = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + "A".repeat(3300) + "\n-----END OPENSSH PRIVATE KEY-----\n"
        v.put("key:1:private", bigKey)
        v.put("host:1:password", "pw")
        assertEquals(bigKey, v.get("key:1:private"))
        assertEquals(setOf("key:1:private", "host:1:password"), v.keys())
        v.delete("key:1:private")
        v.delete("host:1:password")
        assertTrue(v.keys().isEmpty())
    }
}
