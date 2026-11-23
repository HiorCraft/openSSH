package dev.hiorcraft.openssh.ssh

import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Fehler mit einer für Benutzer verständlichen Meldung. */
open class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)

class HostKeyRejectedException(host: String, cause: Throwable? = null) :
    SshException("Der Host-Schlüssel von $host wurde nicht akzeptiert. Verbindung abgebrochen.", cause)

class AuthenticationFailedException(user: String, cause: Throwable?) :
    SshException("Anmeldung als '$user' fehlgeschlagen. Passwort oder Schlüssel prüfen.", cause)

class InvalidPassphraseException(cause: Throwable? = null) :
    SshException("Die Passphrase des Schlüssels ist falsch oder fehlt.", cause)

internal fun Throwable.toSshException(spec: ConnectionSpec): SshException {
    val root = generateSequence(this) { it.cause }.toList()
    return when {
        this is SshException -> this
        root.any { it is UserAuthException } -> AuthenticationFailedException(spec.username, this)
        root.any { it is UnknownHostException } -> SshException("Host '${spec.host}' wurde nicht gefunden.", this)
        root.any { it is ConnectException } -> SshException("Verbindung zu ${spec.host}:${spec.port} abgelehnt.", this)
        root.any { it is NoRouteToHostException } -> SshException("Keine Route zu ${spec.host}.", this)
        root.any { it is SocketTimeoutException } ->
            SshException("Zeitüberschreitung beim Verbinden mit ${spec.host}:${spec.port}.", this)
        root.any { it is TransportException && it.message?.contains("HostKeyVerifier", ignoreCase = true) == true } ||
            root.any { it.message?.contains("Could not verify", ignoreCase = true) == true } ->
            HostKeyRejectedException(spec.host, this)
        this is TransportException -> SshException("SSH-Transportfehler: ${message ?: javaClass.simpleName}", this)
        else -> SshException(message ?: javaClass.simpleName, this)
    }
}
