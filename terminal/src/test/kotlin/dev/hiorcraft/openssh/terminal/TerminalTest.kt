package dev.hiorcraft.openssh.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TerminalColor
import dev.hiorcraft.openssh.ssh.ConnectionSpec
import dev.hiorcraft.openssh.ssh.SshAuth
import dev.hiorcraft.openssh.ssh.SshSession
import dev.hiorcraft.openssh.ssh.hostkeys.KnownHostsVerifier
import dev.hiorcraft.openssh.ssh.testing.TestSshServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshTtyConnectorTest {

    @Test
    fun `liest und schreibt ueber die SSH-Shell`(): Unit = runBlocking {
        TestSshServer().start().use { server ->
            val spec = ConnectionSpec("127.0.0.1", server.port, TestSshServer.USER, listOf(SshAuth.Password(TestSshServer.PASSWORD.toCharArray())))
            SshSession.connect(spec, KnownHostsVerifier(Files.createTempFile("kh", "")) { true }).use { session ->
                var closedCalls = 0
                val connector = SshTtyConnector(session.openShell(), "test") { closedCalls++ }
                assertTrue(connector.isConnected)
                assertEquals("test", connector.name)
                assertEquals("welcome\r\n", readAtLeast(connector, "welcome\r\n".length))

                connector.write("grüße\n")
                assertEquals("> grüße\r\n", readAtLeast(connector, "> grüße\r\n".length))
                connector.resize(TermSize(100, 30))

                connector.write("exit\n")
                val buf = CharArray(16)
                while (connector.read(buf, 0, buf.size) >= 0) {
                    // bis zum Ende der Shell lesen
                }
                connector.close()
                assertEquals(1, closedCalls)
                assertFalse(connector.isConnected)
            }
        }
    }

    private fun readAtLeast(connector: SshTtyConnector, chars: Int): String {
        val sb = StringBuilder()
        val buf = CharArray(64)
        while (sb.length < chars) {
            val n = connector.read(buf, 0, buf.size)
            if (n < 0) break
            sb.appendRange(buf, 0, n)
        }
        return sb.toString()
    }
}

class LocalShellsTest {

    @Test
    fun `erkennt Windows-Shells anhand vorhandener Dateien`() {
        val present = setOf(
            Paths.get("C:\\Program Files", "PowerShell", "7", "pwsh.exe"),
            Paths.get("C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe"),
            Paths.get("C:\\Windows\\System32\\cmd.exe"),
        )
        val shells = LocalShells(
            env = mapOf("SystemRoot" to "C:\\Windows", "ProgramFiles" to "C:\\Program Files", "ComSpec" to "C:\\Windows\\System32\\cmd.exe"),
            exists = { it in present },
            isWindows = true,
        )
        assertEquals(listOf("pwsh", "powershell", "cmd"), shells.detect().map { it.id })
        assertEquals("pwsh", shells.default().id)
        assertEquals("cmd", shells.byId("cmd").id)
        assertEquals("pwsh", shells.byId("gibt-es-nicht").id)
    }

    @Test
    fun `findet pwsh auch ueber PATH, Umgebungsvariablen ohne Gross-Kleinschreibung`() {
        val pwshOnPath = Paths.get("D:\\Tools\\pwsh", "pwsh.exe")
        val shells = LocalShells(
            env = mapOf("SYSTEMROOT" to "C:\\Windows", "Path" to "C:\\x;D:\\Tools\\pwsh"),
            exists = { it == pwshOnPath },
            isWindows = true,
        )
        val detected = shells.detect()
        assertEquals("pwsh", detected.first().id)
        assertEquals(pwshOnPath.toString(), detected.first().command.first())
    }

    @Test
    fun `faellt auf cmd zurueck, wenn nichts gefunden wird`() {
        val shells = LocalShells(env = emptyMap(), exists = { false }, isWindows = true)
        assertEquals(listOf("cmd"), shells.detect().map { it.id })
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `startet eine echte lokale Shell ueber ConPTY`() {
        val profile = ShellProfile("cmd", "CMD", listOf("cmd.exe", "/c", "echo openssh-ok"))
        val connector = LocalShells().start(profile, 80, 24, Path.of(System.getProperty("java.io.tmpdir")))
        val out = StringBuilder()
        val buf = CharArray(256)
        val deadline = System.currentTimeMillis() + 15_000
        while (!out.contains("openssh-ok") && System.currentTimeMillis() < deadline) {
            val n = connector.read(buf, 0, buf.size)
            if (n < 0) break
            out.appendRange(buf, 0, n)
        }
        assertTrue(out.contains("openssh-ok"), "Ausgabe war: $out")
        connector.process.waitFor(10, TimeUnit.SECONDS)
        connector.close()
    }
}

class ThemedSettingsProviderTest {

    @Test
    fun `verwendet Farben und Schrift aus den Einstellungen`() {
        val settings = TerminalSettings(theme = TerminalTheme.Daylight, fontFamily = "Monospaced", fontSize = 17f, scrollbackLines = 1234)
        val provider = ThemedSettingsProvider(settings)
        assertEquals(17f, provider.terminalFontSize)
        assertEquals(17, provider.terminalFont.size)
        assertEquals(1234, provider.bufferMaxLinesCount)
        assertEquals(TerminalColor.rgb(0x38, 0x3A, 0x42).toColor(), provider.defaultForeground.toColor())
        assertEquals(TerminalColor.rgb(0xFA, 0xFA, 0xFA).toColor(), provider.defaultBackground.toColor())
        // JediTerm initialisiert den Textstil über getDefaultStyle(); muss zum Theme passen, sonst erscheint Text invertiert
        @Suppress("DEPRECATION")
        val defaultStyle = provider.defaultStyle
        assertEquals(provider.defaultForeground.toColor(), defaultStyle.foreground!!.toColor())
        assertEquals(provider.defaultBackground.toColor(), defaultStyle.background!!.toColor())
        assertEquals(TerminalTheme.Daylight.ansi[1], provider.terminalColorPalette.getForeground(TerminalColor.index(1)).rgb and 0xFFFFFF)
    }

    @Test
    fun `PowerShell-Schema entspricht der Windows-PowerShell-Konsole`() {
        val provider = ThemedSettingsProvider(TerminalSettings(theme = TerminalTheme.PowerShell, fontFamily = "Monospaced"))
        assertEquals(TerminalColor.rgb(0x01, 0x24, 0x56).toColor(), provider.defaultBackground.toColor())
        assertEquals(TerminalColor.rgb(0xCC, 0xCC, 0xCC).toColor(), provider.defaultForeground.toColor())
        // Gelb (Index 11) nutzt PSReadLine für Befehle, Cyan (14) für Strings
        assertEquals(0xF9F1A5, provider.terminalColorPalette.getForeground(TerminalColor.index(11)).rgb and 0xFFFFFF)
        // Auswahl wie in der Konsole: hell hinterlegt, Text in Hintergrundfarbe
        assertEquals(TerminalColor.rgb(0x01, 0x24, 0x56).toColor(), provider.selectionColor.foreground!!.toColor())
    }

    @Test
    fun `Theme-Lookup mit Fallback`() {
        assertEquals(TerminalTheme.Contrast, TerminalTheme.byId("contrast"))
        assertEquals(TerminalTheme.PowerShell, TerminalTheme.byId(null))
        assertTrue(TerminalSettings.availableMonospaceFonts().isNotEmpty())
    }
}
