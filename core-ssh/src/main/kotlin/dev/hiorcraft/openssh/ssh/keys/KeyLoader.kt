package dev.hiorcraft.openssh.ssh.keys

import dev.hiorcraft.openssh.ssh.InvalidPassphraseException
import dev.hiorcraft.openssh.ssh.SshException
import dev.hiorcraft.openssh.ssh.hostkeys.Fingerprints
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/** Öffentliche Informationen zu einem geladenen Schlüssel. */
data class KeyInfo(
    val format: PrivateKeyFormat,
    val algorithm: String,
    val fingerprint: String,
    val publicKeyLine: String,
    val encrypted: Boolean,
)

object KeyLoader {

    /**
     * Lädt einen privaten Schlüssel und entschlüsselt ihn sofort, damit eine falsche
     * Passphrase hier auffällt und nicht erst beim Verbindungsaufbau.
     */
    fun load(keyText: String, passphrase: CharArray? = null): KeyProvider {
        val format = PrivateKeyFormat.detect(keyText)
        if (format == PrivateKeyFormat.UNKNOWN) {
            throw SshException("Unbekanntes Schlüsselformat. Unterstützt: OpenSSH, PEM, PKCS#8 und PuTTY (.ppk).")
        }
        val encrypted = try {
            PrivateKeyFormat.isEncrypted(keyText)
        } catch (e: IllegalArgumentException) {
            throw SshException("Schlüssel ist beschädigt: ${e.message}", e)
        }
        if (encrypted && (passphrase == null || passphrase.isEmpty())) {
            throw InvalidPassphraseException()
        }
        if (format == PrivateKeyFormat.PUTTY) {
            val valid = try {
                PuttyMac.verifyUnencrypted(keyText)
            } catch (e: Exception) {
                false
            }
            if (!valid) throw SshException("PuTTY-Schlüssel ist beschädigt oder wurde verändert (MAC ungültig).")
        }
        val provider: FileKeyProvider = when (format) {
            PrivateKeyFormat.OPENSSH -> OpenSSHKeyV1KeyFile()
            PrivateKeyFormat.PUTTY -> PuTTYKeyFile()
            PrivateKeyFormat.PEM, PrivateKeyFormat.PKCS8 -> PKCS8KeyFile()
            PrivateKeyFormat.UNKNOWN -> error("unerreichbar")
        }
        if (encrypted) provider.init(StringReader(keyText), oneOff(passphrase!!)) else provider.init(StringReader(keyText))
        try {
            // Erzwingt das Parsen und Entschlüsseln
            provider.private
            provider.public
        } catch (e: Exception) {
            if (encrypted) throw InvalidPassphraseException(e)
            throw SshException("Schlüssel konnte nicht gelesen werden: ${e.message}", e)
        }
        return provider
    }

    fun load(path: Path, passphrase: CharArray? = null): KeyProvider = load(Files.readString(path), passphrase)

    fun inspect(keyText: String, passphrase: CharArray? = null): KeyInfo {
        val provider = load(keyText, passphrase)
        val publicKey = provider.public
        return KeyInfo(
            format = PrivateKeyFormat.detect(keyText),
            algorithm = provider.type.toString(),
            fingerprint = Fingerprints.sha256(publicKey),
            publicKeyLine = Fingerprints.openSshPublicKeyLine(publicKey),
            encrypted = PrivateKeyFormat.isEncrypted(keyText),
        )
    }

    private fun oneOff(passphrase: CharArray) = object : PasswordFinder {
        override fun reqPassword(resource: Resource<*>?): CharArray = passphrase.copyOf()
        override fun shouldRetry(resource: Resource<*>?): Boolean = false
    }
}
