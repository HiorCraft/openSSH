package dev.hiorcraft.openssh.vault

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

/** Symmetrische Verschlüsselung für [DpapiFileVault]. */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Windows DPAPI (CryptProtectData) im Benutzerkontext, mit anwendungsspezifischer Entropie. */
class DpapiCipher(namespace: String) : SecretCipher {
    private val entropy = "$namespace-vault-v1".toByteArray(Charsets.UTF_8)

    override fun encrypt(plain: ByteArray): ByteArray =
        Crypt32Util.cryptProtectData(plain, entropy, CRYPTPROTECT_UI_FORBIDDEN, "OpenSSH", null)

    override fun decrypt(encrypted: ByteArray): ByteArray =
        Crypt32Util.cryptUnprotectData(encrypted, entropy, CRYPTPROTECT_UI_FORBIDDEN, null)

    private companion object {
        const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
    }
}

/**
 * Speichert jedes Geheimnis als verschlüsselte Datei. Der Dateiname ist ein Hash des Schlüssels,
 * der Klartext-Name steht nur verschlüsselt im Inhalt. Schreiben ist atomar.
 */
class DpapiFileVault(
    private val dir: Path,
    private val cipher: SecretCipher,
) : SecretVault {

    @Synchronized
    override fun put(key: String, secret: String) {
        Files.createDirectories(dir)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + keyBytes.size + secretBytes.size)
            .putInt(keyBytes.size).put(keyBytes).put(secretBytes).array()
        val encrypted = try {
            cipher.encrypt(plain)
        } finally {
            plain.fill(0)
            secretBytes.fill(0)
        }
        val target = fileFor(key)
        val temp = Files.createTempFile(dir, "tmp", ".part")
        Files.write(temp, encrypted)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    override fun get(key: String): String? {
        val file = fileFor(key)
        if (!Files.exists(file)) return null
        val (storedKey, secret) = decode(Files.readAllBytes(file))
        if (storedKey != key) throw VaultException("Tresor-Datei passt nicht zum Schlüssel '$key'")
        return secret
    }

    @Synchronized
    override fun delete(key: String) {
        Files.deleteIfExists(fileFor(key))
    }

    @Synchronized
    override fun keys(): Set<String> {
        if (!Files.isDirectory(dir)) return emptySet()
        return dir.listDirectoryEntries()
            .filter { it.extension == "bin" }
            .mapNotNull { runCatching { decode(Files.readAllBytes(it)).first }.getOrNull() }
            .toSet()
    }

    private fun decode(encrypted: ByteArray): Pair<String, String> {
        val plain = try {
            cipher.decrypt(encrypted)
        } catch (e: Exception) {
            throw VaultException("Geheimnis konnte nicht entschlüsselt werden (anderer Windows-Benutzer?)", e)
        }
        try {
            val buf = ByteBuffer.wrap(plain)
            val keyLen = buf.int
            if (keyLen < 0 || keyLen > plain.size - 4) throw VaultException("Tresor-Datei ist beschädigt")
            val key = String(plain, 4, keyLen, Charsets.UTF_8)
            val secret = String(plain, 4 + keyLen, plain.size - 4 - keyLen, Charsets.UTF_8)
            return key to secret
        } finally {
            plain.fill(0)
        }
    }

    private fun fileFor(key: String): Path {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return dir.resolve(hash.joinToString("") { "%02x".format(it) } + ".bin")
    }
}
