package dev.hiorcraft.openssh.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.hiorcraft.openssh.sftp.RemoteEntry
import dev.hiorcraft.openssh.sftp.RemotePaths
import dev.hiorcraft.openssh.sftp.SftpBrowser
import dev.hiorcraft.openssh.sftp.TransferDirection
import dev.hiorcraft.openssh.sftp.TransferQueue
import dev.hiorcraft.openssh.sftp.TransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Zustand eines SFTP-Dateibrowsers; wird vom SFTP-Tab und der Split-Ansicht genutzt. */
class SftpState(
    private val scope: CoroutineScope,
    val browser: SftpBrowser,
    private val notify: (String) -> Unit,
) {
    var path by mutableStateOf("/")
        private set
    var entries by mutableStateOf<List<RemoteEntry>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var selection by mutableStateOf<Set<String>>(emptySet())
    var filter by mutableStateOf("")
    var showHidden by mutableStateOf(false)

    val queue = TransferQueue(scope, browser, parallelism = 3) { transfer ->
        when (val s = transfer.state) {
            TransferState.Done -> {
                if (transfer.direction == TransferDirection.UPLOAD && RemotePaths.normalize(transfer.target) == path) refresh()
                if (transfer.direction == TransferDirection.DOWNLOAD) notify("Heruntergeladen: ${transfer.displayName} → ${transfer.target}")
            }
            is TransferState.Failed -> notify("Übertragung fehlgeschlagen (${transfer.displayName}): ${s.message}")
            else -> Unit
        }
    }

    val visibleEntries: List<RemoteEntry>
        get() = entries.filter { (showHidden || !it.name.startsWith(".")) && (filter.isBlank() || it.name.contains(filter, ignoreCase = true)) }

    fun start() = scope.launch {
        val home = runCatching { browser.home() }.getOrDefault("/")
        load(home)
    }

    fun open(target: String) = scope.launch { load(RemotePaths.normalize(target)) }

    fun up() = open(RemotePaths.parent(path))

    fun refresh() = scope.launch { load(path) }

    private suspend fun load(target: String) {
        loading = true
        try {
            val list = browser.list(target)
            entries = list
            path = target
            error = null
            selection = emptySet()
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            loading = false
        }
    }

    fun mkdir(name: String) = action("Ordner anlegen") { browser.mkdir(RemotePaths.join(path, name)) }

    fun rename(entry: RemoteEntry, newName: String) =
        action("Umbenennen") { browser.rename(entry.path, RemotePaths.join(RemotePaths.parent(entry.path), newName)) }

    fun delete(entries: List<RemoteEntry>) = action("Löschen") {
        entries.forEach { browser.delete(it.path, recursive = it.isDirectory) }
    }

    fun chmod(entry: RemoteEntry, mode: Int) = action("Rechte ändern") { browser.chmod(entry.path, mode) }

    fun upload(files: List<Path>) {
        if (files.isEmpty()) return
        queue.enqueueUploads(files, path)
    }

    fun download(entries: List<RemoteEntry>, targetDir: Path) {
        if (entries.isEmpty()) return
        queue.enqueueDownloads(entries.map { it.path }, targetDir)
    }

    suspend fun readText(entry: RemoteEntry): String = browser.readText(entry.path, 2_000_000)

    fun writeText(entry: RemoteEntry, text: String) = action("Speichern") { browser.writeText(entry.path, text) }

    private fun action(label: String, block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            notify("$label fehlgeschlagen: ${e.message}")
        }
        load(path)
    }

    fun close() {
        queue.cancelAll()
        scope.launch(Dispatchers.IO) { withContext(Dispatchers.IO) { browser.close() } }
    }
}
