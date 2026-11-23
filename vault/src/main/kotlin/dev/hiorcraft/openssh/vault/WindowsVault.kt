package dev.hiorcraft.openssh.vault

/**
 * Kombiniert den Credential Manager (für Passwörter u.ä.) mit DPAPI-Dateien für Geheimnisse,
 * die das Blob-Limit des Credential Managers überschreiten (z.B. RSA-4096-Schlüssel).
 */
class WindowsVault(
    private val small: SecretVault,
    private val large: SecretVault,
    private val smallLimitBytes: Int = CredentialManagerVault.MAX_BLOB,
) : SecretVault {

    @Synchronized
    override fun put(key: String, secret: String) {
        if (secret.toByteArray(Charsets.UTF_8).size <= smallLimitBytes) {
            small.put(key, secret)
            large.delete(key)
        } else {
            large.put(key, secret)
            small.delete(key)
        }
    }

    override fun get(key: String): String? = small.get(key) ?: large.get(key)

    @Synchronized
    override fun delete(key: String) {
        small.delete(key)
        large.delete(key)
    }

    override fun keys(): Set<String> = small.keys() + large.keys()
}
