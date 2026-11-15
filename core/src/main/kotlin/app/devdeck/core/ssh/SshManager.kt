package app.devdeck.core.ssh

import app.devdeck.core.fs.FileTooLargeException
import app.devdeck.core.fs.RemoteEntry
import app.devdeck.core.fs.RemoteFs
import app.devdeck.core.model.SshConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int?, val timedOut: Boolean)

/**
 * Keeps one authenticated SSH connection per configured server and offers command execution,
 * SFTP file access and interactive shells on top of it.
 */
class SshManager(
    private val knownHosts: KnownHostsStore,
    private val connectTimeoutMs: Int = 15_000,
) {
    private val clients = ConcurrentHashMap<String, SSHClient>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun client(conn: SshConnection): SSHClient = withContext(Dispatchers.IO) {
        locks.getOrPut(conn.id) { Mutex() }.withLock {
            clients[conn.id]?.let { if (it.isConnected && it.isAuthenticated) return@withLock it }
            clients.remove(conn.id)?.let { runCatching { it.disconnect() } }
            val verifier = TofuHostKeyVerifier(knownHosts)
            val c = SSHClient(DefaultConfig())
            c.addHostKeyVerifier(verifier)
            c.connectTimeout = connectTimeoutMs
            c.connection.keepAlive.keepAliveInterval = 30
            try {
                c.connect(conn.host, conn.port)
            } catch (e: TransportException) {
                verifier.mismatch?.let { throw it }
                throw e
            }
            try {
                authenticate(c, conn)
            } catch (e: Exception) {
                runCatching { c.disconnect() }
                throw e
            }
            clients[conn.id] = c
            c
        }
    }

    private fun authenticate(c: SSHClient, conn: SshConnection) {
        val key = conn.privateKey?.takeIf { it.isNotBlank() }
        if (key != null) {
            val provider = if (conn.keyPassphrase.isNullOrEmpty()) {
                c.loadKeys(key, null, null)
            } else {
                c.loadKeys(key, null, PasswordUtils.createOneOff(conn.keyPassphrase.toCharArray()))
            }
            c.authPublickey(conn.username, provider)
        } else {
            c.authPassword(conn.username, conn.password ?: "")
        }
    }

    /** Closes the cached connection, e.g. after the user edited it. */
    fun disconnect(connectionId: String) {
        clients.remove(connectionId)?.let { runCatching { it.disconnect() } }
    }

    fun disconnectAll() {
        clients.keys.toList().forEach { disconnect(it) }
    }

    /** Runs a non-interactive command (via `bash -lc` semantics of the login shell). */
    suspend fun exec(
        conn: SshConnection,
        command: String,
        timeoutSeconds: Long = 120,
        maxOutputChars: Int = 30_000,
    ): ExecResult = withContext(Dispatchers.IO) {
        val c = client(conn)
        c.startSession().use { session ->
            val cmd = session.exec(command)
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val tOut = Thread { copyCapped(cmd.inputStream, out, maxOutputChars * 4) }.apply { start() }
            val tErr = Thread { copyCapped(cmd.errorStream, err, maxOutputChars * 4) }.apply { start() }
            var timedOut = false
            val started = System.nanoTime()
            try {
                cmd.join(timeoutSeconds, TimeUnit.SECONDS)
            } catch (e: net.schmizz.sshj.connection.ConnectionException) {
                // sshj signals an expired timeout with a ConnectionException; anything earlier is a real error.
                val elapsedMs = (System.nanoTime() - started) / 1_000_000
                if (elapsedMs < timeoutSeconds * 1000 - 250) throw e
                timedOut = true
            }
            if (cmd.isOpen) {
                timedOut = true
                runCatching { cmd.close() }
            }
            tOut.join(2_000); tErr.join(2_000)
            ExecResult(
                stdout = truncate(out.toString(Charsets.UTF_8), maxOutputChars),
                stderr = truncate(err.toString(Charsets.UTF_8), maxOutputChars),
                exitCode = cmd.exitStatus,
                timedOut = timedOut,
            )
        }
    }

    fun fs(conn: SshConnection): RemoteFs = SftpFs(this, conn)

    suspend fun openShell(conn: SshConnection, cols: Int, rows: Int, listener: ShellSession.Listener): ShellSession =
        withContext(Dispatchers.IO) { ShellSession.open(client(conn), cols, rows, listener) }

    private fun copyCapped(input: InputStream, out: ByteArrayOutputStream, maxBytes: Int) {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val room = maxBytes - out.size()
                if (room > 0) out.write(buf, 0, minOf(n, room))
                // Keep draining after the cap so the remote side is not blocked.
            }
        } catch (_: Exception) {
        }
    }
}

internal fun truncate(text: String, max: Int): String =
    if (text.length <= max) text
    else text.take(max / 2) + "\n\n[… ${text.length - max} Zeichen gekürzt …]\n\n" + text.takeLast(max / 2)

private class SftpFs(private val manager: SshManager, private val conn: SshConnection) : RemoteFs {
    private suspend fun <T> sftp(block: (net.schmizz.sshj.sftp.SFTPClient) -> T): T = withContext(Dispatchers.IO) {
        manager.client(conn).newSFTPClient().use(block)
    }

    override suspend fun list(path: String): List<RemoteEntry> = sftp { s ->
        s.ls(path).map {
            RemoteEntry(
                name = it.name,
                path = it.path,
                isDirectory = it.attributes.type == FileMode.Type.DIRECTORY ||
                    (it.attributes.type == FileMode.Type.SYMLINK && runCatching { s.stat(it.path).type == FileMode.Type.DIRECTORY }.getOrDefault(false)),
                size = it.attributes.size,
                modifiedEpochSeconds = it.attributes.mtime,
            )
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = sftp { s ->
        s.open(path, EnumSet.of(OpenMode.READ)).use { f ->
            val size = f.length()
            if (size > maxBytes) throw FileTooLargeException(path, size, maxBytes)
            f.RemoteFileInputStream().use { it.readBytes() }
        }
    }

    override suspend fun write(path: String, data: ByteArray) = sftp { s ->
        s.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { f ->
            f.RemoteFileOutputStream().use { it.write(data) }
        }
    }

    override suspend fun mkdirs(path: String) = sftp { s -> s.mkdirs(path) }

    override suspend fun delete(path: String) = sftp { s ->
        if (s.stat(path).type == FileMode.Type.DIRECTORY) s.rmdir(path) else s.rm(path)
    }

    override suspend fun home(): String = conn.defaultDirectory?.takeIf { it.isNotBlank() }
        ?: sftp { s -> s.canonicalize(".") }
}
