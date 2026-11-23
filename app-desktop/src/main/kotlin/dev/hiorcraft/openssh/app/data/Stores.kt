package dev.hiorcraft.openssh.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Speicherorte der App. Unter Windows `%APPDATA%\OpenSSH`. */
class AppPaths(val root: Path) {
    val hosts: Path = root.resolve("hosts.json")
    val keys: Path = root.resolve("keys.json")
    val snippets: Path = root.resolve("snippets.json")
    val databases: Path = root.resolve("databases.json")
    val settings: Path = root.resolve("settings.json")
    val knownHosts: Path = root.resolve("known_hosts")

    init {
        Files.createDirectories(root)
    }

    companion object {
        /** `OPENSSH_DATA_DIR` überschreibt den Speicherort (z.B. für einen portablen Betrieb vom USB-Stick). */
        fun default(): AppPaths {
            System.getenv("OPENSSH_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return AppPaths(Paths.get(it)) }
            val appData = System.getenv("APPDATA")
            val root = if (!appData.isNullOrBlank()) Paths.get(appData, "OpenSSH")
            else Paths.get(System.getProperty("user.home"), ".openssh-desktop")
            return AppPaths(root)
        }
    }
}

internal val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Schreibt erst in eine temporäre Datei und ersetzt dann atomar, damit nie halbe Dateien entstehen. */
internal fun writeAtomically(target: Path, text: String) {
    Files.createDirectories(target.toAbsolutePath().parent)
    val temp = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
    Files.writeString(temp, text)
    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

/** Unlesbare Dateien werden gesichert statt überschrieben, damit keine Daten verloren gehen. */
private fun backupBroken(file: Path) {
    runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".broken-" + System.currentTimeMillis())) }
}

/** Eine persistente Liste von Einträgen mit eindeutiger ID. */
class JsonListStore<T>(
    private val file: Path,
    serializer: KSerializer<T>,
    private val idOf: (T) -> String,
) {
    private val listSerializer = ListSerializer(serializer)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private fun load(): List<T> {
        if (!Files.exists(file)) return emptyList()
        return try {
            json.decodeFromString(listSerializer, Files.readString(file))
        } catch (e: Exception) {
            backupBroken(file)
            emptyList()
        }
    }

    fun get(id: String?): T? = id?.let { wanted -> _items.value.firstOrNull { idOf(it) == wanted } }

    @Synchronized
    fun upsert(item: T) {
        _items.update { list ->
            val idx = list.indexOfFirst { idOf(it) == idOf(item) }
            if (idx >= 0) list.toMutableList().also { it[idx] = item } else list + item
        }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        _items.update { list -> list.filterNot { idOf(it) == id } }
        save()
    }

    private fun save() = writeAtomically(file, json.encodeToString(listSerializer, _items.value))
}

class SettingsStore(private val file: Path) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun load(): AppSettings {
        if (!Files.exists(file)) return AppSettings()
        return try {
            json.decodeFromString(AppSettings.serializer(), Files.readString(file))
        } catch (e: Exception) {
            backupBroken(file)
            AppSettings()
        }
    }

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.update(transform)
        writeAtomically(file, json.encodeToString(AppSettings.serializer(), _settings.value))
    }
}
