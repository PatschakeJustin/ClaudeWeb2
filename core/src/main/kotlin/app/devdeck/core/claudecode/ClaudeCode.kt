package app.devdeck.core.claudecode

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.SshManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Paths where the native installer and npm put the `claude` binary, for non-login SSH sessions. */
const val CLAUDE_PATH_PREFIX = "export PATH=\"\$HOME/.local/bin:\$HOME/.claude/local:\$HOME/.npm-global/bin:/usr/local/bin:\$PATH\"; "

/** What Claude Code may do without asking (it cannot ask interactively in headless mode). */
enum class ClaudeCodePermission(val label: String, val description: String) {
    PLAN("Nur planen", "Liest und analysiert, ändert nichts."),
    EDIT("Dateien ändern", "Darf Dateien bearbeiten; Shell-Befehle werden abgelehnt."),
    FULL("Alles erlauben", "Darf Dateien ändern und alle Shell-Befehle ausführen."),
}

/**
 * Runs the real Claude Code CLI on a server (`claude -p`, stream-json output) so the user's Claude
 * subscription that is logged in there is used. Follow-up messages continue the same session.
 */
class ClaudeCodeSession(
    private val ssh: SshManager,
    private val connection: () -> SshConnection?,
    private val workDir: () -> String?,
    private val permission: () -> ClaudeCodePermission,
) {
    @Volatile var sessionId: String? = null
        private set

    fun reset() { sessionId = null }

    fun buildCommand(): String {
        val args = mutableListOf("claude", "-p", "--output-format", "stream-json", "--verbose")
        when (permission()) {
            ClaudeCodePermission.PLAN -> args += listOf("--permission-mode", "plan")
            ClaudeCodePermission.EDIT -> args += listOf("--permission-mode", "acceptEdits")
            ClaudeCodePermission.FULL -> args += listOf("--permission-mode", "acceptEdits", "--allowedTools", "Bash")
        }
        sessionId?.let { args += listOf("--resume", it) }
        val dir = workDir()?.takeIf { it.isNotBlank() }
        val cd = if (dir != null) "cd ${shq(dir)} && " else ""
        return CLAUDE_PATH_PREFIX + cd + args.joinToString(" ") { if (it.all { c -> c.isLetterOrDigit() || c in "-_./" }) it else shq(it) }
    }

    /** Runs one turn. Events are delivered as they arrive, on the SSH reader thread. */
    suspend fun send(prompt: String, emit: (AgentEvent) -> Unit) {
        val conn = connection() ?: run {
            emit(AgentEvent.Failed("Kein Server für Claude Code ausgewählt.")); return
        }
        val parser = StreamJsonParser()
        val stderr = StringBuilder()
        val exit = try {
            ssh.execStreaming(
                conn, buildCommand(), stdin = prompt,
                onStdout = { line -> parser.parse(line).forEach(emit) },
                onStderr = { line -> synchronized(stderr) { if (stderr.length < 4000) stderr.append(line).append('\n') } },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            parser.sessionId?.let { sessionId = it }
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Failed("SSH-Fehler: ${e.message ?: e.javaClass.simpleName}")); return
        }
        parser.sessionId?.let { sessionId = it }
        if (!parser.sawResult) emit(AgentEvent.Failed(explainFailure(exit, synchronized(stderr) { stderr.toString().trim() })))
    }

    private fun explainFailure(exit: Int?, stderr: String): String = when {
        exit == 127 || "command not found" in stderr || "not found" in stderr && "claude" in stderr ->
            "Claude Code ist auf dem Server nicht installiert. Unter Verbindungen → „Server einrichten“ installieren."
        "login" in stderr.lowercase() || "authenticat" in stderr.lowercase() || "api key" in stderr.lowercase() ->
            "Claude Code ist nicht angemeldet. Im Terminal `claude` starten und mit /login das Claude-Abo verbinden.\n$stderr"
        else -> "Claude Code wurde beendet (Exit-Code ${exit ?: "?"}).${if (stderr.isNotBlank()) "\n$stderr" else ""}"
    }
}

/** Turns Claude Code's `--output-format stream-json` lines into [AgentEvent]s. */
class StreamJsonParser {
    var sessionId: String? = null
        private set
    var sawResult = false
        private set

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): List<AgentEvent> {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val obj = runCatching { json.parseToJsonElement(trimmed).jsonObject }.getOrNull() ?: return emptyList()
        obj.str("session_id")?.let { sessionId = it }
        return when (obj.str("type")) {
            "assistant" -> assistant(obj)
            "user" -> toolResults(obj)
            "result" -> result(obj)
            else -> emptyList()
        }
    }

    private fun blocks(obj: JsonObject): List<JsonObject> =
        ((obj["message"] as? JsonObject)?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()

    private fun assistant(obj: JsonObject): List<AgentEvent> = blocks(obj).flatMap { b ->
        when (b.str("type")) {
            "text" -> {
                val t = b.str("text").orEmpty()
                if (t.isBlank()) emptyList() else listOf(AgentEvent.AssistantBlockStart, AgentEvent.TextDelta(t))
            }
            "tool_use" -> listOf(AgentEvent.ToolStarted(b.str("id") ?: "", b.str("name") ?: "Werkzeug", summarize(b)))
            else -> emptyList()
        }
    }

    private fun toolResults(obj: JsonObject): List<AgentEvent> = blocks(obj).filter { it.str("type") == "tool_result" }.map { b ->
        val content = when (val c = b["content"]) {
            is JsonPrimitive -> c.contentOrNull.orEmpty()
            is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
            else -> ""
        }
        val isError = (b["is_error"] as? JsonPrimitive)?.booleanOrNull == true
        AgentEvent.ToolFinished(b.str("tool_use_id") ?: "", content.ifEmpty { "(keine Ausgabe)" }, isError, denied = isError && "permission" in content.lowercase())
    }

    private fun result(obj: JsonObject): List<AgentEvent> {
        sawResult = true
        val isError = (obj["is_error"] as? JsonPrimitive)?.booleanOrNull == true
        val out = mutableListOf<AgentEvent>()
        val denials = (obj["permission_denials"] as? JsonArray)?.size ?: 0
        if (denials > 0) out += AgentEvent.Notice("$denials Aktion(en) wurden wegen der Berechtigungsstufe abgelehnt. Stufe oben im Chat anpassen.")
        if (isError) out += AgentEvent.Failed(obj.str("result") ?: "Claude Code meldet einen Fehler (${obj.str("subtype")}).")
        return out
    }

    private fun summarize(b: JsonObject): String {
        val input = b["input"] as? JsonObject ?: return ""
        return input.str("command") ?: input.str("file_path") ?: input.str("path") ?: input.str("pattern")
            ?: input.str("url") ?: input.str("description") ?: input.toString().take(200)
    }
}

/** Base setup: system info and curl/git/tmux. */
val BASE_SETUP_SCRIPT = """
set -e
echo "== System =="
uname -sm
( . /etc/os-release 2>/dev/null && echo "${'$'}PRETTY_NAME" ) || true
echo "Benutzer: ${'$'}(whoami)"
SUDO=""
if [ "${'$'}(id -u)" -ne 0 ]; then SUDO="sudo -n"; fi
need=""
command -v curl >/dev/null 2>&1 || need="${'$'}need curl"
command -v git >/dev/null 2>&1 || need="${'$'}need git"
command -v tmux >/dev/null 2>&1 || need="${'$'}need tmux"
if [ -n "${'$'}need" ]; then
  echo "== Pakete installieren:${'$'}need =="
  if command -v apt-get >/dev/null 2>&1; then ${'$'}SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq && ${'$'}SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq ${'$'}need
  elif command -v dnf >/dev/null 2>&1; then ${'$'}SUDO dnf install -y -q ${'$'}need
  elif command -v yum >/dev/null 2>&1; then ${'$'}SUDO yum install -y -q ${'$'}need
  elif command -v apk >/dev/null 2>&1; then ${'$'}SUDO apk add --no-cache ${'$'}need bash libgcc libstdc++ ripgrep
  elif command -v pacman >/dev/null 2>&1; then ${'$'}SUDO pacman -Sy --noconfirm ${'$'}need
  else echo "Kein unterstützter Paketmanager gefunden."; fi
  command -v curl >/dev/null 2>&1 || { echo "curl fehlt und konnte nicht installiert werden (sudo-Rechte?)."; exit 1; }
fi
""".trimIndent()

/** Installs Claude Code (native installer) for the SSH user. */
val CLAUDE_INSTALL_SCRIPT = """
echo "== Claude Code installieren =="
curl -fsSL https://claude.ai/install.sh | bash
export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
for rc in "${'$'}HOME/.bashrc" "${'$'}HOME/.profile"; do
  [ -f "${'$'}rc" ] || touch "${'$'}rc"
  grep -q 'HOME/.local/bin' "${'$'}rc" || echo 'export PATH="${'$'}HOME/.local/bin:${'$'}PATH"' >> "${'$'}rc"
done
echo "== Prüfen =="
claude --version
if [ -f "${'$'}HOME/.claude/.credentials.json" ]; then echo "DEVDECK_LOGGED_IN"; fi
""".trimIndent()


/** Full setup script; prints DEVDECK_INSTALL_OK at the end and DEVDECK_TOOL_OK/FAIL per extra tool. */
fun setupScript(claude: Boolean, extras: Collection<CliAgent>): String = buildString {
    appendLine(BASE_SETUP_SCRIPT)
    if (claude) appendLine(CLAUDE_INSTALL_SCRIPT)
    appendLine("set +e")
    appendLine(extraInstallScript(extras))
    appendLine("echo DEVDECK_INSTALL_OK")
}

/** Claude Code only (kept for callers that just want Claude). */
val INSTALL_SCRIPT: String get() = setupScript(true, emptyList())

/** Quick probe: is Claude Code installed, which version, and does a login exist? */
const val STATUS_SCRIPT = CLAUDE_PATH_PREFIX +
    "if command -v claude >/dev/null 2>&1; then echo \"VERSION \$(claude --version 2>/dev/null | head -1)\"; else echo MISSING; fi; " +
    "[ -f \"\$HOME/.claude/.credentials.json\" ] && echo LOGGED_IN || true"

data class ClaudeCodeStatus(val installed: Boolean, val version: String?, val loggedIn: Boolean)

fun parseStatus(output: String): ClaudeCodeStatus {
    val version = output.lines().firstOrNull { it.startsWith("VERSION ") }?.removePrefix("VERSION ")?.trim()
    return ClaudeCodeStatus(installed = version != null, version = version, loggedIn = output.lines().any { it.trim() == "LOGGED_IN" })
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"

