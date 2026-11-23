package dev.hiorcraft.openssh.app.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoresTest {

    @TempDir
    lateinit var dir: Path

    private fun hostStore() = JsonListStore(dir.resolve("hosts.json"), HostEntry.serializer()) { it.id }

    @Test
    fun `Hosts werden gespeichert, aktualisiert und neu geladen`() {
        val store = hostStore()
        val host = HostEntry(label = "Web", host = "web01", username = "root", group = "Prod")
        store.upsert(host)
        store.upsert(host.copy(port = 2222))
        store.upsert(HostEntry(label = "DB", host = "db01", username = "admin"))
        assertEquals(2, store.items.value.size)

        val reloaded = hostStore()
        assertEquals(2222, reloaded.get(host.id)!!.port)
        reloaded.delete(host.id)
        assertNull(hostStore().get(host.id))
    }

    @Test
    fun `Passwoerter landen nie in der JSON-Datei`() {
        hostStore().upsert(HostEntry(label = "Web", host = "web01", username = "root"))
        val text = Files.readString(dir.resolve("hosts.json"))
        // Das Modell hat gar kein Passwortfeld – Geheimnisse liegen ausschließlich im Tresor
        assertFalse(Regex("\"[a-zA-Z]*(password|passphrase|secret)\"\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text), text)
    }

    @Test
    fun `beschaedigte Datei wird gesichert statt ueberschrieben`() {
        Files.writeString(dir.resolve("hosts.json"), "{ kaputt")
        val store = hostStore()
        assertTrue(store.items.value.isEmpty())
        assertTrue(dir.listDirectoryEntries().any { it.fileName.toString().startsWith("hosts.json.broken-") })
    }

    @Test
    fun `unbekannte Felder aus neueren Versionen werden ignoriert`() {
        Files.writeString(dir.resolve("hosts.json"), """[{"id":"1","label":"A","host":"h","username":"u","zukunftsfeld":42}]""")
        assertEquals("A", hostStore().get("1")!!.label)
    }

    @Test
    fun `Einstellungen haben sinnvolle Standardwerte und werden persistiert`() {
        val store = SettingsStore(dir.resolve("settings.json"))
        assertEquals(ThemeMode.POWERSHELL, store.settings.value.themeMode)
        assertEquals("powershell", store.settings.value.terminalTheme)
        store.update { it.copy(fontSize = 16f, themeMode = ThemeMode.DARK) }
        val reloaded = SettingsStore(dir.resolve("settings.json"))
        assertEquals(16f, reloaded.settings.value.fontSize)
        assertEquals(ThemeMode.DARK, reloaded.settings.value.themeMode)
    }

    @Test
    fun `Authentifizierungsarten`() {
        assertTrue(AuthType.KEY_THEN_PASSWORD.usesKey && AuthType.KEY_THEN_PASSWORD.usesPassword)
        assertFalse(AuthType.KEY.usesPassword)
        assertFalse(AuthType.PASSWORD.usesKey)
        assertEquals("deploy@web:2200", HostEntry(label = "x", host = "web", port = 2200, username = "deploy").address)
        assertEquals("host:abc:password", SecretNames.hostPassword("abc"))
    }
}
