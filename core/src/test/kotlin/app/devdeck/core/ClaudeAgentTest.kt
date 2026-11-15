package app.devdeck.core

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.agent.AgentSettings
import app.devdeck.core.agent.AgentToolbox
import app.devdeck.core.agent.ClaudeAgent
import app.devdeck.core.model.ConnectionStore
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.InMemoryKnownHosts
import app.devdeck.core.ssh.SshManager
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Drives the agent loop against a fake Messages API that streams SSE like the real one. */
class ClaudeAgentTest {
    private val server = MockWebServer()

    @BeforeTest fun setUp() = server.start()
    @AfterTest fun tearDown() = server.shutdown()

    private fun sse(vararg events: Pair<String, String>) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { (type, data) -> "event: $type\ndata: $data\n\n" })

    private fun start() = "message_start" to """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""
    private fun textBlock(i: Int, text: String) = arrayOf(
        "content_block_start" to """{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":${kotlinx.serialization.json.JsonPrimitive(text).toString()}}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":$i}""",
    )
    private fun toolBlock(i: Int, id: String, name: String, inputJson: String) = arrayOf(
        "content_block_start" to """{"type":"content_block_start","index":$i,"content_block":{"type":"tool_use","id":"$id","name":"$name","input":{}}}""",
        "content_block_delta" to """{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":${kotlinx.serialization.json.JsonPrimitive(inputJson).toString()}}}""",
        "content_block_stop" to """{"type":"content_block_stop","index":$i}""",
    )
    private fun end(reason: String) = arrayOf(
        "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},"usage":{"output_tokens":20}}""",
        "message_stop" to """{"type":"message_stop"}""",
    )

    private fun agent(store: ConnectionStore = ConnectionStore()) = ClaudeAgent(
        AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(server.url("/").toString().trimEnd('/')).maxRetries(0).build(),
        AgentToolbox({ store }, SshManager(InMemoryKnownHosts())),
    ) { AgentSettings() }

    @Test fun toolLoopRoundTrip() = runBlocking {
        server.enqueue(sse(start(), *textBlock(0, "Ich schaue nach."), *toolBlock(1, "toolu_1", "list_connections", "{}"), *end("tool_use")))
        server.enqueue(sse(start(), *textBlock(0, "Keine Verbindungen."), *end("end_turn")))

        val events = mutableListOf<AgentEvent>()
        agent().send("Welche Server habe ich?", { _, _, _ -> error("no approval needed") }) { events += it }

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("Ich schaue nach.Keine Verbindungen.", text)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertContains(finished.output, "keine Verbindungen")

        val first = server.takeRequest()
        assertEquals("/v1/messages", first.path)
        assertTrue(first.getHeader("anthropic-beta")!!.contains("server-side-fallback-2026-07-01"))
        val firstBody = Json.parseToJsonElement(first.body.readUtf8()).jsonObject
        assertEquals("claude-opus-5-5", firstBody["model"]!!.jsonPrimitive.content)
        assertEquals("default", firstBody["fallbacks"]!!.jsonPrimitive.content)
        assertEquals("high", firstBody["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertTrue(firstBody["stream"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(6, firstBody["tools"]!!.jsonArray.size)

        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = second["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        val toolResult = messages[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", toolResult["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", toolResult["tool_use_id"]!!.jsonPrimitive.content)
    }

    @Test fun deniedCommandIsReportedAndNotRun() = runBlocking {
        val store = ConnectionStore(ssh = listOf(SshConnection(name = "prod", host = "192.0.2.1", username = "root", password = "x")))
        server.enqueue(sse(start(), *toolBlock(0, "toolu_9", "run_command", """{"connection":"prod","command":"rm -rf /tmp/x"}"""), *end("tool_use")))
        server.enqueue(sse(start(), *textBlock(0, "Okay, nicht ausgeführt."), *end("end_turn")))

        val asked = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        agent(store).send("Lösch das", { _, summary, _ -> asked += summary; false }) { events += it }

        assertEquals(listOf("prod: rm -rf /tmp/x"), asked)
        assertTrue(events.filterIsInstance<AgentEvent.ToolFinished>().single().denied)
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertContains(body, "abgelehnt")
    }

    @Test fun invalidToolInputIsReturnedAsError() = runBlocking {
        server.enqueue(sse(start(), *toolBlock(0, "toolu_2", "read_file", """{"connection":"x"}"""), *end("tool_use")))
        server.enqueue(sse(start(), *textBlock(0, "ok"), *end("end_turn")))
        agent().send("lies", { _, _, _ -> true }) {}
        server.takeRequest()
        val body = server.takeRequest().body.readUtf8()
        assertContains(body, "Pflichtfeld 'path' fehlt")
        assertContains(body, "\"is_error\":true")
    }

    @Test fun apiErrorIsReported() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""))
        val events = mutableListOf<AgentEvent>()
        agent().send("hi", { _, _, _ -> true }) { events += it }
        assertContains((events.single() as AgentEvent.Failed).message, "API-Key ungültig")
    }
}
