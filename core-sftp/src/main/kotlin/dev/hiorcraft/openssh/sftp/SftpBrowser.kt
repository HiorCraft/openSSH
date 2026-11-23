package dev.hiorcraft.openssh.sftp

import dev.hiorcraft.openssh.ssh.SshException
import dev.hiorcraft.openssh.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.EnumSet
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/** Fortschritt einer (ggf. rekursiven) Übertragung. */
data class TransferProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
    val currentFile: String,
) {
    val fraction: Float get() = if (bytesTotal <= 0) (if (filesTotal == 0 || filesDone == filesTotal) 1f else 0f) else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Dateioperationen über SFTP. Alle Methoden laufen auf [Dispatchers.IO] und reagieren
 * während Übertragungen auf Coroutine-Abbruch.
 */
class SftpBrowser(private val sftp: SFTPClient) : Closeable {

    suspend fun home(): String = io { sftp.canonicalize(".") }

    suspend fun canonicalize(path: String): String = io { sftp.canonicalize(path) }

    /** Listet ein Verzeichnis, Verzeichnisse zuerst, danach alphabetisch. */
    suspend fun list(path: String): List<RemoteEntry> = io {
        sftp.ls(path)
            .filter { it.name != "." && it.name != ".." }
            .map { toEntry(it) }
            .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    suspend fun stat(path: String): RemoteEntry? = io {
        try {
            toEntry(path, sftp.lstat(path))
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
        }
    }

    suspend fun exists(path: String): Boolean = stat(path) != null

    suspend fun mkdir(path: String) = io { sftp.mkdir(path) }

    suspend fun mkdirs(path: String) = io { sftp.mkdirs(path) }

    suspend fun rename(from: String, to: String) = io { sftp.rename(from, to) }

    suspend fun chmod(path: String, mode: Int) = io { sftp.chmod(path, mode) }

    /** Löscht eine Datei oder (mit [recursive]) einen Verzeichnisbaum. Symlinks werden nicht verfolgt. */
    suspend fun delete(path: String, recursive: Boolean = false): Unit = io { deleteBlocking(path, recursive) }

    private fun deleteBlocking(path: String, recursive: Boolean) {
        val attrs = sftp.lstat(path)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            if (recursive) {
                for (child in sftp.ls(path)) {
                    if (child.name == "." || child.name == "..") continue
                    deleteBlocking(child.path, true)
                }
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    /** Liest eine kleine Textdatei (z.B. für eine Vorschau). */
    suspend fun readText(path: String, maxBytes: Int = 1_000_000): String = io {
        sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
            val stream = file.ReadAheadRemoteFileInputStream(8)
            val bytes = stream.use { it.readNBytes(maxBytes) }
            bytes.toString(Charsets.UTF_8)
        }
    }

    suspend fun writeText(path: String, text: String) = io {
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            file.RemoteFileOutputStream(0, 16).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
    }

    /**
     * Lädt eine lokale Datei oder ein Verzeichnis (rekursiv) nach [remoteDir] hoch.
     * Liefert den Zielpfad auf dem Server.
     */
    suspend fun upload(
        local: Path,
        remoteDir: String,
        onProgress: (TransferProgress) -> Unit = {},
    ): String = io {
        require(Files.exists(local)) { "Lokale Datei existiert nicht: $local" }
        val target = RemotePaths.join(remoteDir, local.name)
        val files = mutableListOf<Pair<Path, String>>()
        val dirs = mutableListOf<String>()
        if (local.isDirectory()) {
            Files.walk(local).use { stream ->
                stream.forEach { p ->
                    val rel = local.relativize(p).joinToString("/") { it.toString() }
                    val remote = if (rel.isEmpty()) target else RemotePaths.join(target, rel)
                    if (p.isDirectory()) dirs += remote else files += p to remote
                }
            }
        } else {
            files += local to target
        }
        val total = files.sumOf { it.first.fileSize() }
        val tracker = ProgressTracker(total, files.size, onProgress)
        dirs.forEach { if (sftp.statExistence(it) == null) sftp.mkdirs(it) }
        for ((path, remote) in files) {
            currentCoroutineContext().ensureActive()
            tracker.startFile(path.name)
            uploadFile(path, remote, tracker)
            tracker.finishFile()
        }
        tracker.report(force = true)
        target
    }

    /**
     * Lädt eine Datei oder ein Verzeichnis (rekursiv) nach [localDir] herunter.
     * Liefert den lokalen Zielpfad.
     */
    suspend fun download(
        remotePath: String,
        localDir: Path,
        onProgress: (TransferProgress) -> Unit = {},
    ): Path = io {
        val root = RemotePaths.normalize(remotePath)
        val target = localDir.resolve(RemotePaths.name(root))
        val files = mutableListOf<Triple<String, Path, Long>>()
        val dirs = mutableListOf<Path>()
        collectRemote(root, target, files, dirs)
        val tracker = ProgressTracker(files.sumOf { it.third }, files.size, onProgress)
        dirs.forEach { Files.createDirectories(it) }
        Files.createDirectories(localDir)
        for ((remote, local, _) in files) {
            currentCoroutineContext().ensureActive()
            tracker.startFile(RemotePaths.name(remote))
            downloadFile(remote, local, tracker)
            tracker.finishFile()
        }
        tracker.report(force = true)
        target
    }

    private fun collectRemote(remote: String, local: Path, files: MutableList<Triple<String, Path, Long>>, dirs: MutableList<Path>) {
        val attrs = sftp.stat(remote)
        if (attrs.type == FileMode.Type.DIRECTORY) {
            dirs.add(local)
            for (child in sftp.ls(remote)) {
                if (child.name == "." || child.name == "..") continue
                collectRemote(child.path, local.resolve(safeLocalName(child.name)), files, dirs)
            }
        } else {
            files += Triple(remote, local, attrs.size)
        }
    }

    private suspend fun uploadFile(local: Path, remote: String, tracker: ProgressTracker) {
        var completed = false
        try {
            sftp.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
                Files.newInputStream(local).use { input ->
                    file.RemoteFileOutputStream(0, 16).use { output -> copy(input, output, tracker) }
                }
            }
            val mtime = Files.getLastModifiedTime(local).toInstant().epochSecond
            runCatching { sftp.setattr(remote, FileAttributes.Builder().withAtimeMtime(mtime, mtime).build()) }
            completed = true
        } finally {
            if (!completed) runCatching { sftp.rm(remote) }
        }
    }

    private suspend fun downloadFile(remote: String, local: Path, tracker: ProgressTracker) {
        val temp = local.resolveSibling(local.name + ".part")
        var completed = false
        try {
            Files.createDirectories(local.toAbsolutePath().parent)
            sftp.open(remote, EnumSet.of(OpenMode.READ)).use { file ->
                file.ReadAheadRemoteFileInputStream(16).use { input ->
                    Files.newOutputStream(temp).use { output -> copy(input, output, tracker) }
                }
                val mtime = file.fetchAttributes().mtime
                Files.move(temp, local, StandardCopyOption.REPLACE_EXISTING)
                if (mtime > 0) runCatching { Files.setLastModifiedTime(local, FileTime.from(Instant.ofEpochSecond(mtime))) }
            }
            completed = true
        } finally {
            if (!completed) runCatching { Files.deleteIfExists(temp) }
        }
    }

    private suspend fun copy(input: InputStream, output: OutputStream, tracker: ProgressTracker) {
        val buffer = ByteArray(BUFFER_SIZE)
        val context = currentCoroutineContext()
        while (true) {
            context.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            tracker.add(n.toLong())
        }
    }

    private fun toEntry(info: RemoteResourceInfo): RemoteEntry = toEntry(info.path, info.attributes)

    private fun toEntry(path: String, attrs: FileAttributes): RemoteEntry {
        val type = when (attrs.type) {
            FileMode.Type.DIRECTORY -> EntryType.DIRECTORY
            FileMode.Type.REGULAR -> EntryType.FILE
            FileMode.Type.SYMLINK -> EntryType.SYMLINK
            else -> EntryType.OTHER
        }
        val linkIsDir = type == EntryType.SYMLINK &&
            runCatching { sftp.stat(path).type == FileMode.Type.DIRECTORY }.getOrDefault(false)
        return RemoteEntry(
            path = path,
            name = RemotePaths.name(path),
            type = type,
            size = attrs.size,
            modified = attrs.mtime.takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            mode = attrs.mode.permissionsMask,
            linkTargetIsDirectory = linkIsDir,
        )
    }

    override fun close() {
        runCatching { sftp.close() }
    }

    private class ProgressTracker(
        private val total: Long,
        private val filesTotal: Int,
        private val onProgress: (TransferProgress) -> Unit,
    ) {
        private var done = 0L
        private var filesDone = 0
        private var current = ""
        private var lastReport = 0L

        fun startFile(name: String) { current = name; report(force = true) }
        fun finishFile() { filesDone++ }
        fun add(n: Long) { done += n; report() }

        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastReport > 50_000_000) {
                lastReport = now
                onProgress(TransferProgress(done, total, filesDone, filesTotal, current))
            }
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        suspend fun open(session: SshSession): SftpBrowser = SftpBrowser(session.openSftp())

        /** Ersetzt Zeichen, die unter Windows in Dateinamen nicht erlaubt sind. */
        fun safeLocalName(name: String): String {
            val replaced = name.replace(Regex("[<>:\"/\\\\|?*\\u0000-\\u001f]"), "_").trimEnd('.', ' ')
            val reserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
            val base = replaced.substringBefore('.').uppercase()
            return when {
                replaced.isEmpty() -> "_"
                base in reserved -> "_$replaced"
                else -> replaced
            }
        }
    }
}

private fun SFTPClient.statExistence(path: String): FileAttributes? = try {
    stat(path)
} catch (e: SFTPException) {
    if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) null else throw e
}

private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
    try {
        block()
    } catch (e: SFTPException) {
        throw SshException(sftpMessage(e), e)
    }
}

private fun sftpMessage(e: SFTPException): String = when (e.statusCode) {
    Response.StatusCode.NO_SUCH_FILE -> "Datei oder Verzeichnis nicht gefunden"
    Response.StatusCode.PERMISSION_DENIED -> "Zugriff verweigert"
    Response.StatusCode.FILE_ALREADY_EXISTS -> "Datei existiert bereits"
    Response.StatusCode.DIR_NOT_EMPTY -> "Verzeichnis ist nicht leer"
    else -> "SFTP-Fehler: ${e.message ?: e.statusCode}"
}
