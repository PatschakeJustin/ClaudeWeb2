package app.devdeck.core

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.claudecode.ClaudeCodePermission
import app.devdeck.core.claudecode.ClaudeCodeSession
import app.devdeck.core.claudecode.parseStatus
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.InMemoryKnownHosts
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.runBlocking
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.command.CommandFactory
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.server.shell.ProcessShellFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs [ClaudeCodeSession] against a fake `claude` binary on an embedded SSH server. */
class ClaudeCodeSessionTest {
    private lateinit var server: SshServer
    private lateinit var home: Path

    @BeforeTest fun setUp() {
        home = Files.createTempDirectory("home")
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { _, _, _ -> true }
            commandFactory = CommandFactory { channel, command ->
                ProcessShellFactory(command, "/bin/sh", "-c", "export HOME='$home' PATH=/usr/bin:/bin; $command").createShell(channel)
            }
            start()
        }
    }

    @AfterTest fun tearDown() { server.stop(true) }

    private fun installFakeClaude() {
        val bin = Files.createDirectories(home.resolve(".local/bin"))
        val script = bin.resolve("claude")
        Files.writeString(script, """
            #!/bin/sh
            prompt=${'$'}(cat)
            echo "${'$'}*" >> "${'$'}HOME/args.log"
            pwd >> "${'$'}HOME/pwd.log"
            echo 'nicht json'
            echo '{"type":"system","subtype":"init","session_id":"sess-1"}'
            echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Du sagtest: ${'$'}prompt\"},{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"Bash\",\"input\":{\"command\":\"ls -la\"}}]}}"
            echo '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":[{"type":"text","text":"a.txt"}]}]}}'
            echo '{"type":"result","subtype":"success","is_error":false,"result":"ok","session_id":"sess-1","permission_denials":[{"tool_name":"Bash"}]}'
        """.trimIndent() + "\n")
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun session(perm: ClaudeCodePermission = ClaudeCodePermission.FULL, dir: String? = "/tmp") = ClaudeCodeSession(
        SshManager(InMemoryKnownHosts()),
        { SshConnection(name = "s", host = "127.0.0.1", port = server.port, username = "u", password = "p") },
        { dir },
        { perm },
    )

    @Test fun streamsEventsAndResumesSession() = runBlocking {
        installFakeClaude()
        val s = session()
        val events = mutableListOf<AgentEvent>()
        s.send("Hallo 'Welt'") { events += it }

        assertEquals("sess-1", s.sessionId)
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Du sagtest: Hallo 'Welt'", text)
        val started = events.filterIsInstance<AgentEvent.ToolStarted>().single()
        assertEquals("Bash", started.name); assertEquals("ls -la", started.summary)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("t1", finished.id); assertEquals("a.txt", finished.output); assertFalse(finished.isError)
        assertTrue(events.any { it is AgentEvent.Notice && "abgelehnt" in it.text })
        assertTrue(events.none { it is AgentEvent.Failed })

        s.send("weiter") { }
        val args = Files.readAllLines(home.resolve("args.log"))
        assertEquals(2, args.size)
        assertContains(args[0], "-p --output-format stream-json --verbose --permission-mode acceptEdits --allowedTools Bash")
        assertFalse("--resume" in args[0])
        assertContains(args[1], "--resume sess-1")
        assertEquals(Path.of("/tmp").toRealPath().toString(), Files.readAllLines(home.resolve("pwd.log"))[0])
    }

    @Test fun planModeFlags() {
        assertContains(session(ClaudeCodePermission.PLAN).buildCommand(), "--permission-mode plan")
        assertFalse("allowedTools" in session(ClaudeCodePermission.EDIT).buildCommand())
        assertContains(session(dir = "/srv/mein projekt").buildCommand(), "cd '/srv/mein projekt' && ")
    }

    @Test fun missingClaudeIsExplained() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        session().send("hi") { events += it }
        assertContains((events.single() as AgentEvent.Failed).message, "nicht installiert")
    }

    @Test fun statusParsing() {
        val st = parseStatus("VERSION 2.1.0 (Claude Code)\nLOGGED_IN\n")
        assertTrue(st.installed); assertTrue(st.loggedIn); assertEquals("2.1.0 (Claude Code)", st.version)
        assertFalse(parseStatus("MISSING\n").installed)
    }
}
