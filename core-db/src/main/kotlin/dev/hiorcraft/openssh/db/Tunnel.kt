package dev.hiorcraft.openssh.db

import dev.hiorcraft.openssh.ssh.PortForward
import dev.hiorcraft.openssh.ssh.SshSession
import java.io.Closeable

/** Lokaler Endpunkt, über den eine Datenbank erreicht wird – direkt oder per SSH-Tunnel. */
class Endpoint internal constructor(
    val host: String,
    val port: Int,
    private val forward: PortForward?,
) : Closeable {
    val isTunneled: Boolean get() = forward != null

    override fun close() {
        forward?.close()
    }

    override fun toString() = if (forward != null) "$host:$port (Tunnel → ${forward.remoteHost}:${forward.remotePort})" else "$host:$port"

    companion object {
        /**
         * Mit [session] wird ein Local Port Forwarding auf einem freien Port geöffnet,
         * sodass [remoteHost]:[remotePort] aus Sicht des SSH-Servers erreichbar ist.
         */
        suspend fun open(remoteHost: String, remotePort: Int, session: SshSession?): Endpoint =
            if (session == null) {
                Endpoint(remoteHost, remotePort, null)
            } else {
                val forward = session.forwardLocal(remoteHost.ifBlank { "127.0.0.1" }, remotePort)
                Endpoint(forward.bindAddress, forward.localPort, forward)
            }
    }
}
