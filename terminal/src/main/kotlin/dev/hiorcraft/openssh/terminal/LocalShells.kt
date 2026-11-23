package dev.hiorcraft.openssh.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.ProcessTtyConnector
import com.pty4j.PtyProcess
import com.pty4j.PtyProcessBuilder
import com.pty4j.WinSize
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Eine lokale Shell, die in einem Terminal-Tab gestartet werden kann. */
data class ShellProfile(
    val id: String,
    val displayName: String,
    val command: List<String>,
)

/** Erkennt die auf dem Rechner verfügbaren Shells (PowerShell 7, Windows PowerShell, CMD, WSL, Git Bash). */
class LocalShells(
    private val env: Map<String, String> = System.getenv(),
    private val exists: (Path) -> Boolean = { Files.isRegularFile(it) },
    private val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
) {

    fun detect(): List<ShellProfile> = if (isWindows) detectWindows() else detectUnix()

    fun default(): ShellProfile = detect().first()

    fun byId(id: String?): ShellProfile = detect().firstOrNull { it.id == id } ?: default()

    private fun detectWindows(): List<ShellProfile> {
        val systemRoot = env.getIgnoreCase("SystemRoot") ?: "C:\\Windows"
        val programFiles = env.getIgnoreCase("ProgramFiles") ?: "C:\\Program Files"
        val result = mutableListOf<ShellProfile>()

        val pwsh = sequenceOf(Paths.get(programFiles, "PowerShell", "7", "pwsh.exe"))
            .plus(findOnPath("pwsh.exe"))
            .firstOrNull(exists)
        if (pwsh != null) result += ShellProfile("pwsh", "PowerShell 7", listOf(pwsh.toString(), "-NoLogo"))

        val winPs = Paths.get(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        if (exists(winPs)) result += ShellProfile("powershell", "Windows PowerShell", listOf(winPs.toString(), "-NoLogo"))

        val cmd = env.getIgnoreCase("ComSpec")?.let(Paths::get) ?: Paths.get(systemRoot, "System32", "cmd.exe")
        if (exists(cmd)) result += ShellProfile("cmd", "Eingabeaufforderung (CMD)", listOf(cmd.toString()))

        val wsl = Paths.get(systemRoot, "System32", "wsl.exe")
        if (exists(wsl)) result += ShellProfile("wsl", "WSL", listOf(wsl.toString()))

        val gitBash = Paths.get(programFiles, "Git", "bin", "bash.exe")
        if (exists(gitBash)) result += ShellProfile("gitbash", "Git Bash", listOf(gitBash.toString(), "--login", "-i"))

        if (result.isEmpty()) result += ShellProfile("cmd", "Eingabeaufforderung (CMD)", listOf("cmd.exe"))
        return result
    }

    private fun detectUnix(): List<ShellProfile> {
        val result = mutableListOf<ShellProfile>()
        env["SHELL"]?.let { result += ShellProfile("login", "Login-Shell", listOf(it, "-l")) }
        for (candidate in listOf("/bin/bash", "/bin/zsh", "/bin/sh")) {
            val p = Paths.get(candidate)
            if (exists(p) && result.none { it.command.first() == candidate }) {
                result += ShellProfile(p.fileName.toString(), p.fileName.toString(), listOf(candidate))
            }
        }
        if (result.isEmpty()) result += ShellProfile("sh", "sh", listOf("/bin/sh"))
        return result
    }

    private fun findOnPath(exe: String): Sequence<Path> {
        val path = env.getIgnoreCase("PATH") ?: return emptySequence()
        val separator = if (isWindows) ';' else ':'
        return path.split(separator).asSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { Paths.get(it.trim('"'), exe) }.getOrNull() }
    }

    /** Startet die Shell in einem Pseudo-Terminal (unter Windows über ConPTY). */
    fun start(profile: ShellProfile, columns: Int = 120, rows: Int = 32, workingDirectory: Path? = null): LocalShellConnector {
        val environment = HashMap(env).apply {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
        }
        val dir = workingDirectory ?: Paths.get(System.getProperty("user.home"))
        val process = PtyProcessBuilder(profile.command.toTypedArray())
            .setEnvironment(environment)
            .setDirectory(dir.toString())
            .setInitialColumns(columns)
            .setInitialRows(rows)
            .setConsole(false)
            .setUseWinConPty(true)
            .start()
        return LocalShellConnector(process, profile)
    }
}

/** JediTerm-Connector für einen lokalen pty4j-Prozess. */
class LocalShellConnector(
    private val ptyProcess: PtyProcess,
    val profile: ShellProfile,
) : ProcessTtyConnector(ptyProcess, Charsets.UTF_8, profile.command) {

    override fun getName(): String = profile.displayName

    override fun resize(termSize: TermSize) {
        if (ptyProcess.isAlive) ptyProcess.winSize = WinSize(termSize.columns, termSize.rows)
    }

    override fun isConnected(): Boolean = ptyProcess.isAlive
}

private fun Map<String, String>.getIgnoreCase(key: String): String? =
    get(key) ?: entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
