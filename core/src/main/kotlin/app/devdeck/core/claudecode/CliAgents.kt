package app.devdeck.core.claudecode

import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.SshManager

/**
 * Other coding CLIs that can run on the server as an alternative or reserve to Claude Code.
 * They are driven in plain-text mode: the prompt goes in, the answer streams out.
 */
enum class CliAgent(
    val id: String,
    val label: String,
    val binary: String,
    val note: String,
    /** Shell snippet run by the installer (may use `${'$'}SUDO`, has Node/npm on PATH when [needsNode]). */
    val install: String,
    val needsNode: Boolean,
    /** What to type in the terminal to log in; null if no login is needed. */
    val loginCommand: String?,
) {
    GEMINI(
        "gemini", "Gemini CLI", "gemini",
        "Google · Anmeldung mit Google-Konto, kostenloses Kontingent",
        "npm install -g --prefix \"\$HOME/.local\" @google/gemini-cli", needsNode = true,
        loginCommand = "NO_BROWSER=true gemini\r",
    ),
    QWEN(
        "qwen", "Qwen Code", "qwen",
        "Alibaba · Anmeldung mit qwen.ai-Konto, kostenloses Kontingent",
        "npm install -g --prefix \"\$HOME/.local\" @qwen-code/qwen-code", needsNode = true,
        loginCommand = "qwen\r",
    ),
    CODEX(
        "codex", "Codex CLI", "codex",
        "OpenAI · Anmeldung mit ChatGPT-Konto",
        "npm install -g --prefix \"\$HOME/.local\" @openai/codex", needsNode = true,
        loginCommand = "codex login --device-auth\r",
    );

    /** The command line; the prompt is read from the shell variable DEVDECK_PROMPT (filled from stdin). */
    fun runCommand(permission: ClaudeCodePermission, ollamaModel: String): String {
        val q = "\"\$DEVDECK_PROMPT\""
        val full = permission == ClaudeCodePermission.FULL
        return when (this) {
            GEMINI -> "gemini" + (if (full) " --yolo" else "") + " -p $q"
            QWEN -> "qwen" + (if (full) " --yolo" else "") + " -p $q"
            CODEX -> "codex exec --skip-git-repo-check" + when (permission) {
                ClaudeCodePermission.PLAN -> " --sandbox read-only"
                ClaudeCodePermission.EDIT -> " --sandbox workspace-write"
                ClaudeCodePermission.FULL -> " --full-auto"
            } + " $q"
        }
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

const val OLLAMA_DEFAULT_MODEL = "qwen2.5-coder:7b"

/** Installs Node.js (user-local) if a selected tool needs it, then the selected tools. */
fun extraInstallScript(agents: Collection<CliAgent>): String {
    if (agents.isEmpty()) return ""
    val sb = StringBuilder()
    sb.appendLine("export PATH=\"\$HOME/.local/bin:\$HOME/.local/node/bin:\$PATH\"")
    if (agents.any { it.needsNode }) sb.appendLine(NODE_INSTALL)
    for (a in agents) {
        sb.appendLine("echo \"== ${a.label} installieren ==\"")
        sb.appendLine("if ${a.install}; then echo \"DEVDECK_TOOL_OK ${a.id}\"; else echo \"DEVDECK_TOOL_FAIL ${a.id}\"; fi")
    }
    return sb.toString()
}

private val NODE_INSTALL = """
if ! command -v node >/dev/null 2>&1 || [ "${'$'}(node -p 'process.versions.node.split(".")[0]' 2>/dev/null || echo 0)" -lt 20 ]; then
  echo "== Node.js installieren (nach ~/.local/node) =="
  case "${'$'}(uname -m)" in x86_64) NARCH=x64;; aarch64|arm64) NARCH=arm64;; armv7l) NARCH=armv7l;; *) NARCH=x64;; esac
  NFILE=${'$'}(curl -fsSL https://nodejs.org/dist/latest-v22.x/SHASUMS256.txt | grep "linux-${'$'}NARCH.tar.gz" | awk '{print ${'$'}2}' | head -1)
  mkdir -p "${'$'}HOME/.local/node"
  curl -fsSL "https://nodejs.org/dist/latest-v22.x/${'$'}NFILE" | tar xz -C "${'$'}HOME/.local/node" --strip-components=1
  for rc in "${'$'}HOME/.bashrc" "${'$'}HOME/.profile"; do
    grep -q '.local/node/bin' "${'$'}rc" 2>/dev/null || echo 'export PATH="${'$'}HOME/.local/node/bin:${'$'}PATH"' >> "${'$'}rc"
  done
fi
node --version
""".trimIndent()

/** Shell probe listing which CLIs are installed: prints `HAS <id>` lines. */
val TOOLS_STATUS_SCRIPT: String = CLAUDE_PATH_PREFIX + "export PATH=\"\$HOME/.local/node/bin:\$PATH\"; " +
    CliAgent.entries.joinToString(" ") { "command -v ${it.binary} >/dev/null 2>&1 && echo 'HAS ${it.id}';" }

fun parseTools(output: String): Set<CliAgent> =
    output.lines().mapNotNull { l -> l.trim().removePrefix("HAS ").takeIf { l.trim().startsWith("HAS ") }?.let { CliAgent.byId(it) } }.toSet()

/**
 * Runs one of the [CliAgent]s on the server. Each call is a fresh process; the last few exchanges
 * are prepended to the prompt so follow-up questions keep their context.
 */
class CliAgentSession(
    private val ssh: SshManager,
    private val agent: CliAgent,
    private val connection: () -> SshConnection?,
    private val workDir: () -> String?,
    private val permission: () -> ClaudeCodePermission,
    private val ollamaModel: () -> String = { OLLAMA_DEFAULT_MODEL },
) {
    private val history = ArrayDeque<Pair<String, String>>()

    suspend fun send(prompt: String, emit: (AgentEvent) -> Unit): Boolean {
        val conn = connection() ?: run { emit(AgentEvent.Failed("Kein Server ausgewählt.")); return false }
        val fullPrompt = if (history.isEmpty()) prompt else buildString {
            appendLine("Bisheriger Gesprächsverlauf (zur Orientierung):")
            history.forEach { (q, a) -> appendLine("Nutzer: $q"); appendLine("Assistent: ${a.take(2000)}") }
            appendLine()
            append("Neue Nachricht: ").append(prompt)
        }
        val dir = workDir()?.takeIf { it.isNotBlank() }
        val cmd = CLAUDE_PATH_PREFIX + "export PATH=\"\$HOME/.local/node/bin:\$PATH\"; " +
            (if (dir != null) "cd ${shellQuote(dir)} && " else "") +
            "DEVDECK_PROMPT=\$(cat); " + agent.runCommand(permission(), ollamaModel())
        val answer = StringBuilder()
        val stderr = StringBuilder()
        var started = false
        val exit = try {
            ssh.execStreaming(conn, cmd, stdin = fullPrompt, onStdout = { raw ->
                val line = stripAnsi(raw)
                if (!started) { emit(AgentEvent.AssistantBlockStart); started = true }
                val chunk = (if (answer.isEmpty()) "" else "\n") + line
                answer.append(chunk)
                emit(AgentEvent.TextDelta(chunk))
            }, onStderr = { l -> synchronized(stderr) { if (stderr.length < 4000) stderr.append(stripAnsi(l)).append('\n') } })
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(AgentEvent.Failed("SSH-Fehler: ${e.message ?: e.javaClass.simpleName}")); return false
        }
        val err = synchronized(stderr) { stderr.toString().trim() }
        if (exit != 0 || answer.isBlank()) {
            emit(AgentEvent.Failed(when {
                exit == 127 -> "${agent.label} ist auf dem Server nicht installiert (Verbindungen → Server einrichten)."
                else -> "${agent.label} wurde mit Exit-Code ${exit ?: "?"} beendet. " +
                    "Falls noch nicht geschehen: im Terminal anmelden.\n$err".trimEnd()
            }))
            return false
        }
        history.addLast(prompt to answer.toString())
        while (history.size > 6) history.removeFirst()
        return true
    }
}

private val ANSI = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007]*\u0007|\r")
internal fun stripAnsi(s: String) = s.replace(ANSI, "")

internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
