package app.devdeck.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import java.io.Closeable
import java.io.OutputStream

/** An interactive PTY shell (e.g. to run `claude` on the server). Output is delivered on a background thread. */
class ShellSession private constructor(
    private val session: Session,
    private val shell: Session.Shell,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onOutput(data: ByteArray)
        fun onClosed(error: Throwable?)
    }

    private val input: OutputStream = shell.outputStream
    @Volatile private var closed = false

    private val reader = Thread({
        val buf = ByteArray(16 * 1024)
        var error: Throwable? = null
        try {
            val ins = shell.inputStream
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                listener.onOutput(buf.copyOf(n))
            }
        } catch (t: Throwable) {
            if (!closed) error = t
        }
        closed = true
        runCatching { session.close() }
        listener.onClosed(error)
    }, "ssh-shell-reader").apply { isDaemon = true }

    val isOpen: Boolean get() = !closed && shell.isOpen

    @Synchronized
    fun write(data: ByteArray) {
        if (closed) return
        input.write(data)
        input.flush()
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    fun resize(cols: Int, rows: Int) {
        if (!closed) runCatching { shell.changeWindowDimensions(cols, rows, 0, 0) }
    }

    override fun close() {
        closed = true
        runCatching { shell.close() }
        runCatching { session.close() }
    }

    companion object {
        fun open(client: SSHClient, cols: Int, rows: Int, listener: Listener): ShellSession {
            val session = client.startSession()
            try {
                session.allocatePTY("xterm-256color", cols, rows, 0, 0, emptyMap())
                val shell = session.startShell()
                return ShellSession(session, shell, listener).also { it.reader.start() }
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
        }
    }
}
