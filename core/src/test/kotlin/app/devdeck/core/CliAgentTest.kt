package app.devdeck.core

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.claudecode.CliAgent
import app.devdeck.core.claudecode.CliAgentSession
import app.devdeck.core.claudecode.ClaudeCodePermission
import app.devdeck.core.claudecode.parseTools
import app.devdeck.core.claudecode.setupScript
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

class CliAgentTest {
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

    private fun fakeGemini(exit: Int = 0) {
        val bin = Files.createDirectories(home.resolve(".local/bin"))
        val f = bin.resolve("gemini")
        // Prints its arguments with ANSI colour, like a real CLI might.
        Files.writeString(f, "#!/bin/sh\nprintf '%s\\n' \"\$@\" >> \"\$HOME/args.log\"\nprintf '\\033[32mAntwort\\033[0m zu: %s\\n' \"\$3\"\necho zweite Zeile\nexit $exit\n")
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun session() = CliAgentSession(
        SshManager(InMemoryKnownHosts()), CliAgent.GEMINI,
        { SshConnection(name = "s", host = "127.0.0.1", port = server.port, username = "u", password = "p") },
        { "/tmp" }, { ClaudeCodePermission.FULL },
    )

    @Test fun streamsPlainTextAndKeepsHistory() = runBlocking {
        fakeGemini()
        val s = session()
        val events = mutableListOf<AgentEvent>()
        assertTrue(s.send("Was ist 1+1?") { events += it })
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Antwort zu: Was ist 1+1?\nzweite Zeile", text)
        assertEquals(1, events.count { it == AgentEvent.AssistantBlockStart })

        assertTrue(s.send("Und 2+2?") {})
        val args = Files.readString(home.resolve("args.log"))
        assertContains(args, "--yolo\n-p\nWas ist 1+1?")
        assertContains(args, "Bisheriger Gesprächsverlauf")
        assertContains(args, "Neue Nachricht: Und 2+2?")
    }

    @Test fun failureAndMissingBinary() = runBlocking {
        val events = mutableListOf<AgentEvent>()
        assertFalse(session().send("x") { events += it })
        assertContains((events.single() as AgentEvent.Failed).message, "nicht installiert")

        fakeGemini(exit = 3)
        val ev2 = mutableListOf<AgentEvent>()
        assertFalse(session().send("x") { ev2 += it })
        assertContains((ev2.last() as AgentEvent.Failed).message, "Exit-Code 3")
    }

    @Test fun commandsAndScripts() {
        assertContains(CliAgent.CODEX.runCommand(ClaudeCodePermission.PLAN, ""), "--sandbox read-only")
        assertEquals("ollama run 'qwen2.5-coder:7b' \"\$DEVDECK_PROMPT\"", CliAgent.OLLAMA.runCommand(ClaudeCodePermission.EDIT, "qwen2.5-coder:7b"))
        assertEquals(setOf(CliAgent.GEMINI, CliAgent.OLLAMA), parseTools("HAS gemini\nfoo\nHAS ollama\n"))
        // The generated installer must be valid POSIX shell.
        val script = Files.createTempFile("setup", ".sh")
        Files.writeString(script, setupScript(true, CliAgent.entries))
        val p = ProcessBuilder("sh", "-n", script.toString()).redirectErrorStream(true).start()
        val out = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)
    }
}
