package app.devdeck.core

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.ai.AiServerException
import app.devdeck.core.ai.OpenAiCompatClient
import app.devdeck.core.ai.SelfHostedSession
import app.devdeck.core.ai.ollamaLinuxScript
import app.devdeck.core.ai.ollamaWindowsScript
import app.devdeck.core.ai.powershellCommand
import app.devdeck.core.model.AiServerConnection
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.InMemoryKnownHosts
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SelfHostedAiTest {
    private val web = MockWebServer()
    private lateinit var sshd: SshServer
    private val ssh = SshManager(InMemoryKnownHosts())
    private lateinit var sshConn: SshConnection

    @BeforeTest fun setUp() {
        web.start()
        sshd = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"; port = 0
            keyPairProvider = SimpleGeneratorHostKeyProvider()
            setPasswordAuthenticator { _, _, _ -> true }
            forwardingFilter = AcceptAllForwardingFilter.INSTANCE
            start()
        }
        sshConn = SshConnection(name = "srv", host = "127.0.0.1", port = sshd.port, username = "u", password = "p")
    }

    @AfterTest fun tearDown() { ssh.disconnectAll(); sshd.stop(true); web.shutdown() }

    private fun client() = OpenAiCompatClient(OkHttpClient(), ssh) { id -> sshConn.takeIf { it.id == id } }

    private fun sse(vararg deltas: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
        deltas.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":${kotlinx.serialization.json.JsonPrimitive(it)}}}]}\n\n" } +
            "data: [DONE]\n\n"
    )

    @Test fun chatDirectKeepsHistory() = runBlocking {
        val conn = AiServerConnection(name = "ki", baseUrl = web.url("/").toString(), apiKey = "geheim", model = "qwen2.5-coder:7b")
        val session = SelfHostedSession(client()) { conn }
        web.enqueue(sse("Hal", "lo ", "Welt"))
        val events = mutableListOf<AgentEvent>()
        assertTrue(session.send("Hi") { events += it })
        assertEquals("Hallo Welt", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })

        val req = web.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer geheim", req.getHeader("Authorization"))
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("qwen2.5-coder:7b", body["model"]!!.jsonPrimitive.content)

        web.enqueue(sse("Ok"))
        session.send("Und?") {}
        val msgs = Json.parseToJsonElement(web.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), msgs.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals("Hallo Welt", msgs[2].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test fun chatThroughSshTunnel() = runBlocking {
        // The model server is only reachable "from the SSH server" – here both are local, but traffic goes through sshd.
        val conn = AiServerConnection(name = "ki", baseUrl = "http://127.0.0.1:${web.port}", model = "m", tunnelSshId = sshConn.id)
        val c = client()
        val base = c.effectiveBase(conn)
        assertTrue(!base.endsWith(":${web.port}"), "expected a local tunnel port, got $base")
        web.enqueue(MockResponse().setBody("""{"data":[{"id":"b-model"},{"id":"a-model"}]}"""))
        assertEquals(listOf("a-model", "b-model"), c.listModels(conn))
        web.enqueue(sse("über ", "Tunnel"))
        assertEquals("über Tunnel", c.chat(conn, listOf("user" to "x")) {})
        assertEquals(base, c.effectiveBase(conn)) // tunnel is reused
    }

    @Test fun errorsAreReadable() = runBlocking {
        val conn = AiServerConnection(name = "ki", baseUrl = web.url("/").toString(), model = "fehlt")
        web.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"model 'fehlt' not found"}"""))
        val e = assertFailsWith<AiServerException> { runBlocking { client().chat(conn, listOf("user" to "x")) {} } }
        assertContains(e.message!!, "nicht gefunden")
        val events = mutableListOf<AgentEvent>()
        web.enqueue(MockResponse().setResponseCode(401))
        SelfHostedSession(client()) { conn }.send("x") { events += it }
        assertContains((events.single() as AgentEvent.Failed).message, "API-Key")
    }

    @Test fun installScripts() {
        val f = Files.createTempFile("ollama", ".sh")
        Files.writeString(f, ollamaLinuxScript("qwen2.5-coder:7b"))
        val p = ProcessBuilder("sh", "-n", f.toString()).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.readAllBytes().toString(Charsets.UTF_8))

        val ps = ollamaWindowsScript("llama3.2:3b")
        assertContains(ps, "pull 'llama3.2:3b'")
        assertContains(ps, "\$exe = \$candidates")
        val cmd = powershellCommand(ps)
        val decoded = String(java.util.Base64.getDecoder().decode(cmd.substringAfterLast(' ')), Charsets.UTF_16LE)
        assertEquals(ps, decoded)
    }
}
