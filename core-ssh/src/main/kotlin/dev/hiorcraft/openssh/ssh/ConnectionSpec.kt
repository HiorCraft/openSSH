package dev.hiorcraft.openssh.ssh

import java.nio.file.Path

/**
 * Eine Authentifizierungsmethode. Mehrere Methoden werden in [ConnectionSpec.auths]
 * der Reihe nach probiert, bis eine erfolgreich ist.
 */
sealed interface SshAuth {
    /** Passwort; wird für `password` und `keyboard-interactive` verwendet. */
    class Password(val password: CharArray) : SshAuth {
        override fun toString() = "Password(***)"
    }

    /** Privater Schlüssel als Text (OpenSSH, PEM/PKCS#8 oder PuTTY .ppk). */
    class PrivateKey(val keyText: String, val passphrase: CharArray? = null) : SshAuth {
        override fun toString() = "PrivateKey(***)"
    }

    /** Privater Schlüssel aus einer Datei. */
    class KeyFile(val path: Path, val passphrase: CharArray? = null) : SshAuth {
        override fun toString() = "KeyFile($path)"
    }
}

data class ConnectionSpec(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auths: List<SshAuth>,
    val connectTimeoutMillis: Int = 15_000,
    /** Intervall für Keep-Alive-Pakete in Sekunden, 0 deaktiviert Keep-Alive. */
    val keepAliveSeconds: Int = 30,
) {
    init {
        require(host.isNotBlank()) { "Host darf nicht leer sein" }
        require(port in 1..65535) { "Ungültiger Port: $port" }
        require(username.isNotBlank()) { "Benutzername darf nicht leer sein" }
        require(auths.isNotEmpty()) { "Mindestens eine Authentifizierungsmethode ist nötig" }
    }

    val displayName: String get() = if (port == 22) "$username@$host" else "$username@$host:$port"
}
