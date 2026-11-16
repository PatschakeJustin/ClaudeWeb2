package app.devdeck.core.ai

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.model.AiServerConnection
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.ByteString.Companion.toByteString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiServerException(message: String) : IOException(message)

/**
 * Talks to an OpenAI-compatible server (`/v1/chat/completions`, `/v1/models`), optionally through an
 * SSH tunnel so the model port does not have to be exposed to the internet.
 */
class OpenAiCompatClient(
    baseClient: OkHttpClient,
    private val ssh: SshManager,
    private val sshLookup: (String) -> SshConnection?,
) {
    private val http = baseClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // local models can be slow on CPU
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Base URL as reachable from this device (rewritten to the tunnel's local port if needed). */
    suspend fun effectiveBase(conn: AiServerConnection): String {
        val url = conn.baseUrl.trim().trimEnd('/').toHttpUrl()
        val tunnel = conn.tunnelSshId?.let { id ->
            sshLookup(id) ?: throw AiServerException("Die SSH-Verbindung für den Tunnel von '${conn.name}' existiert nicht mehr.")
        } ?: return url.toString().trimEnd('/')
        val local = ssh.forward(tunnel, url.host, url.port)
        return url.newBuilder().host("127.0.0.1").port(local).build().toString().trimEnd('/')
    }

    private fun apiRoot(base: String) = if (base.endsWith("/v1")) base else "$base/v1"

    private fun Request.Builder.auth(conn: AiServerConnection) = apply {
        if (conn.apiKey.isNotBlank()) header("Authorization", "Bearer ${conn.apiKey.trim()}")
    }

    suspend fun listModels(conn: AiServerConnection): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(apiRoot(effectiveBase(conn)) + "/models").auth(conn).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AiServerException("KI-Server antwortet mit HTTP ${resp.code}.")
            val data = json.parseToJsonElement(resp.body!!.string()).jsonObject["data"] as? JsonArray ?: return@use emptyList()
            data.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }.sorted()
        }
    }

    /** Streams one chat completion; returns the full answer. */
    suspend fun chat(conn: AiServerConnection, messages: List<Pair<String, String>>, onDelta: (String) -> Unit): String =
        withContext(Dispatchers.IO) {
            if (conn.model.isBlank()) throw AiServerException("Für '${conn.name}' ist kein Modell eingetragen.")
            val body = buildJsonObject {
                put("model", conn.model)
                put("stream", true)
                put("messages", buildJsonArray {
                    messages.forEach { (role, content) -> add(buildJsonObject { put("role", role); put("content", content) }) }
                })
            }
            val req = Request.Builder().url(apiRoot(effectiveBase(conn)) + "/chat/completions").auth(conn)
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val call = http.newCall(req)
            val handle = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val msg = runCatching { resp.body!!.string().take(500) }.getOrDefault("")
                        throw AiServerException(
                            when (resp.code) {
                                401, 403 -> "KI-Server: Zugriff verweigert – API-Key prüfen."
                                404 -> "KI-Server: Modell '${conn.model}' oder Endpunkt nicht gefunden. $msg"
                                else -> "KI-Server: HTTP ${resp.code}. $msg"
                            }.trim()
                        )
                    }
                    val answer = StringBuilder()
                    val source = resp.body!!.source()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                        obj["error"]?.let { throw AiServerException("KI-Server: $it") }
                        val choice = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: continue
                        val delta = ((choice["delta"] as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull
                        if (!delta.isNullOrEmpty()) { answer.append(delta); onDelta(delta) }
                    }
                    answer.toString()
                }
            } finally {
                handle.dispose()
            }
        }
}

/** A chat with a self-hosted model; keeps the conversation history. */
class SelfHostedSession(
    private val client: OpenAiCompatClient,
    private val connection: () -> AiServerConnection?,
) {
    private val history = mutableListOf<Pair<String, String>>()

    suspend fun send(prompt: String, emit: (AgentEvent) -> Unit): Boolean {
        val conn = connection() ?: run { emit(AgentEvent.Failed("KI-Server nicht gefunden.")); return false }
        val messages = listOf("system" to SYSTEM) + history.takeLast(20) + ("user" to prompt)
        var started = false
        return try {
            val answer = client.chat(conn, messages) { d ->
                if (!started) { emit(AgentEvent.AssistantBlockStart); started = true }
                emit(AgentEvent.TextDelta(d))
            }
            if (answer.isBlank()) { emit(AgentEvent.Failed("${conn.name} hat keine Antwort geliefert.")); return false }
            history += "user" to prompt
            history += "assistant" to answer
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Failed(e.message ?: e.javaClass.simpleName))
            false
        }
    }

    companion object {
        const val SYSTEM = "Du bist ein hilfreicher Programmier-Assistent in der App Humantize DevApp Mobile. " +
            "Antworte knapp und in der Sprache des Nutzers. Code in Markdown-Codeblöcken."
    }
}

/** Models offered in the installer; any Ollama model name can be typed in as well. */
val SUGGESTED_MODELS = listOf(
    "qwen2.5-coder:7b" to "Programmieren, ca. 8 GB RAM",
    "qwen2.5-coder:1.5b" to "klein und schnell, ca. 2 GB RAM",
    "llama3.2:3b" to "allgemein, ca. 4 GB RAM",
    "deepseek-r1:7b" to "denkt nach (Reasoning), ca. 8 GB RAM",
)

/** Installs Ollama on Linux (systemd service via the official script) and pulls [model]. */
fun ollamaLinuxScript(model: String): String = """
set -e
SUDO=""
if [ "$(id -u)" -ne 0 ]; then SUDO="sudo -n"; fi
command -v curl >/dev/null 2>&1 || { echo "curl fehlt – bitte zuerst installieren."; exit 1; }
if ! command -v ollama >/dev/null 2>&1; then
  echo "== Ollama installieren =="
  curl -fsSL https://ollama.com/install.sh | sh
fi
if ! curl -fsS http://127.0.0.1:11434/api/version >/dev/null 2>&1; then
  echo "== Ollama-Dienst starten =="
  ${'$'}SUDO systemctl enable --now ollama 2>/dev/null || (nohup ollama serve >/tmp/ollama.log 2>&1 &)
fi
i=0
until curl -fsS http://127.0.0.1:11434/api/version >/dev/null 2>&1; do i=$((i+1)); [ ${'$'}i -gt 60 ] && { echo "Ollama startet nicht."; exit 1; }; sleep 1; done
echo "== Modell laden: ${shq(model)} (kann dauern) =="
ollama pull ${shq(model)}
echo DEVDECK_AI_OK
""".trimIndent()

/**
 * PowerShell for Windows Server (OpenSSH): installs Ollama silently, registers a startup task so the
 * server keeps running after logout, and pulls [model].
 */
fun ollamaWindowsScript(model: String): String = """
${'$'}ErrorActionPreference = 'Stop'
${'$'}ProgressPreference = 'SilentlyContinue'
Write-Output "== System =="
Write-Output ([System.Environment]::OSVersion.VersionString)
${'$'}models = 'C:\ProgramData\Ollama\models'
New-Item -ItemType Directory -Force -Path ${'$'}models | Out-Null
[Environment]::SetEnvironmentVariable('OLLAMA_MODELS', ${'$'}models, 'Machine')
${'$'}env:OLLAMA_MODELS = ${'$'}models
${'$'}candidates = @((Join-Path ${'$'}env:LOCALAPPDATA 'Programs\Ollama\ollama.exe'), 'C:\Program Files\Ollama\ollama.exe')
${'$'}exe = ${'$'}candidates | Where-Object { Test-Path ${'$'}_ } | Select-Object -First 1
if (-not ${'$'}exe) {
  Write-Output "== Ollama herunterladen =="
  ${'$'}setup = Join-Path ${'$'}env:TEMP 'OllamaSetup.exe'
  Invoke-WebRequest -UseBasicParsing -Uri 'https://ollama.com/download/OllamaSetup.exe' -OutFile ${'$'}setup
  Write-Output "== Ollama installieren =="
  Start-Process -FilePath ${'$'}setup -ArgumentList '/VERYSILENT','/NORESTART','/SUPPRESSMSGBOXES' -Wait
  ${'$'}exe = ${'$'}candidates | Where-Object { Test-Path ${'$'}_ } | Select-Object -First 1
  if (-not ${'$'}exe) { throw 'ollama.exe wurde nach der Installation nicht gefunden.' }
}
Write-Output "Ollama: ${'$'}exe"
function Test-Ollama { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 'http://127.0.0.1:11434/api/version' | Out-Null; ${'$'}true } catch { ${'$'}false } }
if (-not (Test-Ollama)) {
  Write-Output "== Autostart-Aufgabe einrichten und starten =="
  ${'$'}action = New-ScheduledTaskAction -Execute ${'$'}exe -Argument 'serve'
  ${'$'}trigger = New-ScheduledTaskTrigger -AtStartup
  ${'$'}settings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
  Register-ScheduledTask -TaskName 'Ollama Serve' -Action ${'$'}action -Trigger ${'$'}trigger -Settings ${'$'}settings -User 'SYSTEM' -RunLevel Highest -Force | Out-Null
  Start-ScheduledTask -TaskName 'Ollama Serve'
}
${'$'}i = 0
while (-not (Test-Ollama)) { ${'$'}i++; if (${'$'}i -gt 60) { throw 'Ollama startet nicht.' }; Start-Sleep -Seconds 1 }
Write-Output "== Modell laden: $model (kann dauern) =="
& ${'$'}exe pull '${model.replace("'", "''")}'
if (${'$'}LASTEXITCODE -ne 0) { throw "ollama pull fehlgeschlagen" }
Write-Output 'DEVDECK_AI_OK'
""".trimIndent()

/** The SSH command that runs [script] in PowerShell (base64 UTF-16LE, avoids all quoting issues). */
fun powershellCommand(script: String): String {
    // okio instead of java.util.Base64, which Android only has from 8.0 on.
    val b64 = script.toByteArray(Charsets.UTF_16LE).toByteString().base64()
    return "powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand $b64"
}

private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
