package dev.hiorcraft.openssh.app.state

import dev.hiorcraft.openssh.app.data.HostEntry
import dev.hiorcraft.openssh.app.data.JsonListStore
import dev.hiorcraft.openssh.app.data.KeyEntry
import dev.hiorcraft.openssh.app.data.SecretNames
import dev.hiorcraft.openssh.ssh.AuthenticationFailedException
import dev.hiorcraft.openssh.ssh.ConnectionSpec
import dev.hiorcraft.openssh.ssh.InvalidPassphraseException
import dev.hiorcraft.openssh.ssh.SshAuth
import dev.hiorcraft.openssh.ssh.SshException
import dev.hiorcraft.openssh.ssh.SshSession
import dev.hiorcraft.openssh.ssh.hostkeys.KnownHostsVerifier
import dev.hiorcraft.openssh.ssh.keys.KeyLoader
import dev.hiorcraft.openssh.vault.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

class ConnectionCancelledException : SshException("Verbindung abgebrochen")

/**
 * Baut aus einem Host-Eintrag eine SSH-Verbindung: holt Geheimnisse aus dem Tresor,
 * fragt fehlende in der UI ab und prüft Host-Schlüssel über known_hosts.
 */
class ConnectionService(
    private val hosts: JsonListStore<HostEntry>,
    private val keys: JsonListStore<KeyEntry>,
    private val vault: SecretVault,
    private val knownHostsFile: Path,
    private val hostKeyBridge: HostKeyBridge,
    private val secrets: SecretPromptBridge,
) {
    fun knownHosts(): KnownHostsVerifier = KnownHostsVerifier(knownHostsFile, hostKeyBridge)

    suspend fun connect(host: HostEntry): SshSession {
        val auths = mutableListOf<SshAuth>()
        var passwordToRemember: String? = null
        var passphraseToRemember: String? = null

        if (host.authType.usesKey) {
            val key = keys.get(host.keyId)
                ?: throw SshException("Für ${host.label} ist kein (vorhandener) SSH-Schlüssel ausgewählt.")
            val keyText = vaultGet(SecretNames.keyPrivate(key.id))
                ?: throw SshException("Der private Schlüssel '${key.name}' fehlt im Tresor. Bitte erneut importieren.")
            var passphrase: String? = null
            if (key.encrypted) {
                passphrase = vaultGet(SecretNames.keyPassphrase(key.id))
                if (passphrase == null) {
                    val answer = secrets.ask("Passphrase für ${key.name}", "Der Schlüssel ist verschlüsselt.", "Passphrase")
                        ?: throw ConnectionCancelledException()
                    passphrase = answer.value
                    if (answer.remember) passphraseToRemember = answer.value
                }
                try {
                    withContext(Dispatchers.Default) { KeyLoader.load(keyText, passphrase.toCharArray()) }
                } catch (e: InvalidPassphraseException) {
                    vaultDelete(SecretNames.keyPassphrase(key.id))
                    throw e
                }
            }
            auths += SshAuth.PrivateKey(keyText, passphrase?.toCharArray())
        }

        if (host.authType.usesPassword) {
            var password = vaultGet(SecretNames.hostPassword(host.id))
            if (password == null) {
                val answer = secrets.ask("Anmelden bei ${host.label}", "Passwort für ${host.address}")
                if (answer == null) {
                    if (auths.isEmpty()) throw ConnectionCancelledException()
                } else {
                    password = answer.value
                    if (answer.remember) passwordToRemember = answer.value
                }
            }
            if (password != null) auths += SshAuth.Password(password.toCharArray())
        }

        val spec = ConnectionSpec(host.host.trim(), host.port, host.username.trim(), auths)
        val session = try {
            SshSession.connect(spec, knownHosts())
        } catch (e: AuthenticationFailedException) {
            // Ein gespeichertes, aber falsches Passwort beim nächsten Mal neu abfragen
            if (passwordToRemember == null && host.authType.usesPassword) vaultDelete(SecretNames.hostPassword(host.id))
            throw e
        }
        passwordToRemember?.let { vaultPut(SecretNames.hostPassword(host.id), it) }
        passphraseToRemember?.let { vaultPut(SecretNames.keyPassphrase(host.keyId!!), it) }
        hosts.get(host.id)?.let { hosts.upsert(it.copy(lastConnected = System.currentTimeMillis())) }
        return session
    }

    private suspend fun vaultGet(name: String) = withContext(Dispatchers.IO) { vault.get(name) }
    private suspend fun vaultPut(name: String, value: String) = withContext(Dispatchers.IO) { vault.put(name, value) }
    private suspend fun vaultDelete(name: String) = withContext(Dispatchers.IO) { vault.delete(name) }
}
