package dev.hiorcraft.openssh.sftp

import java.time.Instant

enum class EntryType { FILE, DIRECTORY, SYMLINK, OTHER }

/** Eine Datei oder ein Verzeichnis auf dem Server. */
data class RemoteEntry(
    val path: String,
    val name: String,
    val type: EntryType,
    val size: Long,
    val modified: Instant?,
    /** Unix-Rechte als Oktalwert, z.B. `0644`. */
    val mode: Int,
    /** Bei Symlinks: ob das Ziel ein Verzeichnis ist. */
    val linkTargetIsDirectory: Boolean = false,
) {
    val isDirectory: Boolean get() = type == EntryType.DIRECTORY || (type == EntryType.SYMLINK && linkTargetIsDirectory)

    /** Rechte im `ls -l`-Stil, z.B. `drwxr-xr-x`. */
    val permissionString: String
        get() = buildString {
            append(
                when (type) {
                    EntryType.DIRECTORY -> 'd'
                    EntryType.SYMLINK -> 'l'
                    EntryType.FILE -> '-'
                    EntryType.OTHER -> '?'
                },
            )
            for (shift in intArrayOf(6, 3, 0)) {
                val bits = (mode shr shift) and 7
                append(if (bits and 4 != 0) 'r' else '-')
                append(if (bits and 2 != 0) 'w' else '-')
                append(if (bits and 1 != 0) 'x' else '-')
            }
        }
}

/** Hilfsfunktionen für POSIX-Pfade auf dem Server (unabhängig vom lokalen Betriebssystem). */
object RemotePaths {
    fun join(dir: String, name: String): String = when {
        name.startsWith("/") -> normalize(name)
        dir.isEmpty() -> normalize(name)
        dir.endsWith("/") -> normalize(dir + name)
        else -> normalize("$dir/$name")
    }

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == "/" || !n.contains('/')) return "/"
        val idx = n.lastIndexOf('/')
        return if (idx == 0) "/" else n.substring(0, idx)
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    /** Entfernt doppelte Slashes, `.` und löst `..` auf. */
    fun normalize(path: String): String {
        val absolute = path.startsWith("/")
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(segment)
            }
        }
        val joined = parts.joinToString("/")
        return if (absolute) "/$joined" else joined.ifEmpty { "." }
    }

    /** Liste der Pfadsegmente für eine Breadcrumb-Navigation, beginnend bei `/`. */
    fun breadcrumbs(path: String): List<Pair<String, String>> {
        val n = normalize(path)
        val result = mutableListOf("/" to "/")
        var current = ""
        for (segment in n.split('/').filter { it.isNotEmpty() }) {
            current = "$current/$segment"
            result += segment to current
        }
        return result
    }
}
