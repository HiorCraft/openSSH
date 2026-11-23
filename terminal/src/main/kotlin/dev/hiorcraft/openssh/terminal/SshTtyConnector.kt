package dev.hiorcraft.openssh.terminal

import com.jediterm.core.util.TermSize
import com.jediterm.terminal.TtyConnector
import dev.hiorcraft.openssh.ssh.ShellChannel
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Verbindet eine SSH-Shell mit JediTerm. JediTerm liest auf einem eigenen Thread,
 * daher sind die blockierenden Stream-Aufrufe hier richtig.
 */
class SshTtyConnector(
    private val shell: ShellChannel,
    private val name: String,
    private val onClosed: () -> Unit = {},
) : TtyConnector {

    private val reader = InputStreamReader(shell.input, Charsets.UTF_8)
    private val closed = AtomicBoolean(false)

    override fun read(buf: CharArray, offset: Int, length: Int): Int {
        val n = reader.read(buf, offset, length)
        if (n < 0) notifyClosed()
        return n
    }

    override fun write(bytes: ByteArray) {
        shell.output.write(bytes)
        shell.output.flush()
    }

    override fun write(string: String) = write(string.toByteArray(Charsets.UTF_8))

    override fun isConnected(): Boolean = shell.isOpen && !closed.get()

    override fun resize(termSize: TermSize) {
        runCatching { shell.resize(termSize.columns, termSize.rows) }
    }

    override fun waitFor(): Int {
        shell.awaitClose()
        return 0
    }

    override fun ready(): Boolean = reader.ready()

    override fun getName(): String = name

    override fun close() {
        shell.close()
        notifyClosed()
    }

    private fun notifyClosed() {
        if (closed.compareAndSet(false, true)) onClosed()
    }
}
