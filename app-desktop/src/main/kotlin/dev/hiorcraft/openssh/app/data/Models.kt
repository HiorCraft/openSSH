package dev.hiorcraft.openssh.app.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

@Serializable
enum class AuthType(val label: String) {
    PASSWORD("Passwort"),
    KEY("SSH-Schlüssel"),
    KEY_THEN_PASSWORD("Schlüssel, sonst Passwort"),
    ;

    val usesKey: Boolean get() = this != PASSWORD
    val usesPassword: Boolean get() = this != KEY
}

@Serializable
data class HostEntry(
    val id: String = newId(),
    val label: String,
    val group: String = "",
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val keyId: String? = null,
    /** Wird nach dem Login automatisch in der Shell ausgeführt. */
    val startupCommand: String = "",
    val notes: String = "",
    val lastConnected: Long? = null,
) {
    val address: String get() = if (port == 22) "$username@$host" else "$username@$host:$port"
}

@Serializable
data class KeyEntry(
    val id: String = newId(),
    val name: String,
    val algorithm: String,
    val fingerprint: String,
    val publicKey: String,
    val format: String,
    val encrypted: Boolean,
    val importedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class Snippet(
    val id: String = newId(),
    val name: String,
    val command: String,
    val description: String = "",
    /** `true`: mit Enter abschicken, `false`: nur einfügen. */
    val runImmediately: Boolean = true,
)

@Serializable
enum class DbKind(val label: String, val defaultPort: Int) {
    MARIADB("MariaDB / MySQL", 3306),
    POSTGRESQL("PostgreSQL", 5432),
    SQLITE("SQLite (lokale Datei)", 0),
    REDIS("Redis", 6379),
}

@Serializable
data class DbEntry(
    val id: String = newId(),
    val name: String,
    val kind: DbKind,
    /** Host-Eintrag, über dessen SSH-Verbindung getunnelt wird; `null` = direkte Verbindung. */
    val sshHostId: String? = null,
    val host: String = "127.0.0.1",
    val port: Int = kind.defaultPort,
    val database: String = "",
    val username: String = "",
    val sqlitePath: String = "",
    val redisDb: Int = 0,
    val useTls: Boolean = false,
)

@Serializable
enum class ThemeMode(val label: String) {
    POWERSHELL("PowerShell-Blau"),
    SYSTEM("Wie Windows (hell/dunkel)"),
    DARK("Dunkel"),
    LIGHT("Hell"),
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.POWERSHELL,
    val mica: Boolean = true,
    val terminalTheme: String = "powershell",
    val fontFamily: String = "",
    val fontSize: Float = 14f,
    val defaultShell: String = "",
    val copyOnSelect: Boolean = false,
    val scrollback: Int = 10_000,
    val maxRows: Int = 1000,
    val downloadDir: String = "",
)

/** Namen der Einträge im [dev.hiorcraft.openssh.vault.SecretVault]. */
object SecretNames {
    fun hostPassword(hostId: String) = "host:$hostId:password"
    fun keyPrivate(keyId: String) = "key:$keyId:private"
    fun keyPassphrase(keyId: String) = "key:$keyId:passphrase"
    fun dbPassword(dbId: String) = "db:$dbId:password"
}
