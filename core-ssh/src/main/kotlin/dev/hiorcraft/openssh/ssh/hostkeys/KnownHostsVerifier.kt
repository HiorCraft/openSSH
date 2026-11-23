package dev.hiorcraft.openssh.ssh.hostkeys

import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.nio.file.Files
import java.nio.file.Path
import java.security.PublicKey

/** Beschreibt einen Host-Schlüssel, den der Benutzer bestätigen muss. */
data class HostKeyCheck(
    /** Hostname wie in known_hosts, bei Nicht-Standard-Ports `[host]:port`. */
    val host: String,
    val keyType: String,
    val fingerprint: String,
    val kind: Kind,
) {
    enum class Kind {
        /** Host ist noch nicht bekannt. */
        UNKNOWN,

        /** Host ist bekannt, aber der Schlüssel hat sich geändert (möglicher MITM-Angriff). */
        CHANGED,
    }
}

/**
 * Wird blockierend auf dem SSH-Transport-Thread aufgerufen.
 * `true` vertraut dem Schlüssel und speichert ihn in known_hosts.
 */
fun interface HostKeyPrompt {
    fun confirm(check: HostKeyCheck): Boolean
}

/**
 * known_hosts-Prüfung im OpenSSH-Format. Unbekannte oder geänderte Schlüssel werden
 * dem Benutzer über [prompt] vorgelegt und nach Zustimmung dauerhaft gespeichert.
 */
class KnownHostsVerifier(
    file: Path,
    private val prompt: HostKeyPrompt,
) : OpenSSHKnownHosts(prepare(file)) {

    @Synchronized
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean = super.verify(hostname, port, key)

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        val accepted = prompt.confirm(check(hostname, key, HostKeyCheck.Kind.UNKNOWN))
        if (accepted) {
            val entry = HostEntry(null, hostname, KeyType.fromKey(key), key)
            entries.add(entry)
            write(entry)
        }
        return accepted
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        val accepted = prompt.confirm(check(hostname, key, HostKeyCheck.Kind.CHANGED))
        if (accepted) {
            val type = KeyType.fromKey(key)
            entries.removeIf { runCatching { it.appliesTo(type, hostname) }.getOrDefault(false) }
            entries.add(HostEntry(null, hostname, type, key))
            write()
        }
        return accepted
    }

    /** Entfernt alle Einträge für einen Host (z.B. aus den Einstellungen heraus). */
    @Synchronized
    fun forget(host: String, port: Int = 22): Int {
        val name = if (port == 22) host else "[$host]:$port"
        val before = entries.size
        entries.removeIf { runCatching { it.appliesTo(name) }.getOrDefault(false) }
        write()
        return before - entries.size
    }

    private fun check(hostname: String, key: PublicKey, kind: HostKeyCheck.Kind) =
        HostKeyCheck(hostname, KeyType.fromKey(key).toString(), Fingerprints.sha256(key), kind)

    private companion object {
        fun prepare(file: Path): java.io.File {
            file.toAbsolutePath().parent?.let(Files::createDirectories)
            return file.toFile()
        }
    }
}
