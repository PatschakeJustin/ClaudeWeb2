package app.devdeck.core

import app.devdeck.core.agent.AgentToolbox
import app.devdeck.core.model.ConnectionStore
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.HostKeyMismatchException
import app.devdeck.core.ssh.InMemoryKnownHosts
import app.devdeck.core.ssh.ShellSession
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.shell.ProcessShellFactory
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs exec requests through /bin/sh -c like a real OpenSSH server does. */
private val shCommands = CommandFactory { channel, command ->
    ProcessShellFactory(command, "/bin/sh", "-c", command).createShell(channel)
}

class SshManagerTest {
    private lateinit var server: SshServer
    private lateinit var root: Path

    private fun startServer(keyProvider: KeyPairProvider = SimpleGeneratorHostKeyProvider()) {
        root = Files.createTempDirectory("sshroot")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            this.keyPairProvider = keyProvider
            setPasswordAuthenticator { u, p, _ -> u == "dev" && p == "secret" }
            commandFactory = shCommands
            shellFactory = ProcessShellFactory("/bin/sh", "/bin/sh", "-i")
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
            start()
        }
    }

    @BeforeTest fun setUp() = startServer()
    @AfterTest fun tearDown() { server.stop(true) }

    private fun conn(password: String = "secret") =
        SshConnection(name = "test", host = "127.0.0.1", port = server.port, username = "dev", password = password, defaultDirectory = "/")

    @Test fun execReturnsOutputAndExitCode() = runBlocking {
        val m = SshManager(InMemoryKnownHosts())
        val r = m.exec(conn(), "echo hallo; echo fehler 1>&2; exit 3")
        assertEquals("hallo\n", r.stdout)
        assertEquals("fehler\n", r.stderr)
        assertEquals(3, r.exitCode)
        assertFalse(r.timedOut)
        m.disconnectAll()
    }

    @Test fun execTimesOut() = runBlocking {
        val m = SshManager(InMemoryKnownHosts())
        val r = m.exec(conn(), "sleep 10", timeoutSeconds = 1)
        assertTrue(r.timedOut)
        m.disconnectAll()
    }

    @Test fun wrongPasswordFails() {
        val m = SshManager(InMemoryKnownHosts())
        assertFailsWith<Exception> { runBlocking { m.exec(conn("nope"), "true") } }
    }

    @Test fun hostKeyChangeIsRejected() {
        val known = InMemoryKnownHosts()
        runBlocking { SshManager(known).exec(conn(), "true") }
        val port = server.port
        server.stop(true)
        // Same port, new host key.
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; this.port = port
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { _, _, _ -> true }
            commandFactory = shCommands
            start()
        }
        assertFailsWith<HostKeyMismatchException> { runBlocking { SshManager(known).exec(conn(), "true") } }
    }

    @Test fun sftpReadWriteListAndAgentEdit() = runBlocking {
        val m = SshManager(InMemoryKnownHosts())
        val c = conn()
        val fs = m.fs(c)
        fs.mkdirs("/proj/src")
        fs.write("/proj/src/main.py", "print('hi')\nx = 1\n".toByteArray())
        assertEquals("print('hi')\nx = 1\n", fs.read("/proj/src/main.py").toString(Charsets.UTF_8))
        val entries = fs.list("/proj")
        assertEquals(listOf("src"), entries.map { it.name })
        assertTrue(entries.single().isDirectory)

        val toolbox = AgentToolbox({ ConnectionStore(ssh = listOf(c)) }, m)
        val edit = toolbox.execute("edit_file", buildJsonObject {
            put("connection", JsonPrimitive("TEST")) // names match case-insensitively
            put("path", JsonPrimitive("/proj/src/main.py"))
            put("old_string", JsonPrimitive("x = 1"))
            put("new_string", JsonPrimitive("x = 2"))
        })
        assertFalse(edit.isError, edit.text)
        val read = toolbox.execute("read_file", buildJsonObject {
            put("connection", JsonPrimitive("test")); put("path", JsonPrimitive("/proj/src/main.py"))
        })
        assertContains(read.text, "     2\tx = 2")

        val missing = toolbox.execute("edit_file", buildJsonObject {
            put("connection", JsonPrimitive("test")); put("path", JsonPrimitive("/proj/src/main.py"))
            put("old_string", JsonPrimitive("gibtsnicht")); put("new_string", JsonPrimitive("y"))
        })
        assertTrue(missing.isError)

        val write = toolbox.execute("write_file", buildJsonObject {
            put("connection", JsonPrimitive("test")); put("path", JsonPrimitive("/neu/tief/datei.txt")); put("content", JsonPrimitive("ok"))
        })
        assertFalse(write.isError, write.text)
        assertEquals("ok", Files.readString(root.resolve("neu/tief/datei.txt")))

        val cmd = toolbox.execute("run_command", buildJsonObject {
            put("connection", JsonPrimitive("test")); put("command", JsonPrimitive("pwd")); put("working_directory", JsonPrimitive("/tmp"))
        })
        assertContains(cmd.text, "Exit-Code: 0")
        assertContains(cmd.text, "/tmp")
        m.disconnectAll()
    }

    @Test fun interactiveShell() = runBlocking {
        val m = SshManager(InMemoryKnownHosts())
        val out = StringBuffer()
        val seen = CountDownLatch(1)
        val shell = m.openShell(conn(), 80, 24, object : ShellSession.Listener {
            override fun onOutput(data: ByteArray) {
                out.append(data.toString(Charsets.UTF_8))
                if (out.contains("devdeck-42")) seen.countDown()
            }
            override fun onClosed(error: Throwable?) {}
        })
        shell.write("echo devdeck-\$((40+2))\n")
        assertTrue(seen.await(10, TimeUnit.SECONDS), "output was: $out")
        shell.resize(120, 40)
        shell.close()
        m.disconnectAll()
    }
}
