package dev.hiorcraft.openssh.vault

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Ablage für Geheimnisse (Passwörter, private Schlüssel, Passphrasen).
 * Schlüssel sind frei wählbare Namen wie `host:<id>:password`.
 */
interface SecretVault {
    fun put(key: String, secret: String)
    fun get(key: String): String?
    fun delete(key: String)
    fun keys(): Set<String>

    fun contains(key: String): Boolean = get(key) != null

    /** Löscht alle Einträge, deren Name mit [prefix] beginnt. */
    fun deleteByPrefix(prefix: String) = keys().filter { it.startsWith(prefix) }.forEach(::delete)
}

class VaultException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Flüchtiger Speicher, z.B. für Tests oder Betriebssysteme ohne sicheren Speicher. */
class InMemoryVault : SecretVault {
    private val map = ConcurrentHashMap<String, String>()
    override fun put(key: String, secret: String) { map[key] = secret }
    override fun get(key: String): String? = map[key]
    override fun delete(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

object Vaults {
    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    /**
     * Unter Windows: Credential Manager für kleine Geheimnisse, DPAPI-verschlüsselte Dateien
     * in [dataDir] für große. Auf anderen Systemen ein flüchtiger Speicher.
     */
    fun platformDefault(dataDir: Path, namespace: String = "OpenSSH"): SecretVault =
        if (isWindows) {
            WindowsVault(CredentialManagerVault(namespace), DpapiFileVault(dataDir.resolve("secrets"), DpapiCipher(namespace)))
        } else {
            InMemoryVault()
        }
}
