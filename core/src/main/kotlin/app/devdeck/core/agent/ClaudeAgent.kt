package app.devdeck.core.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class AgentSettings(
    val model: String = DEFAULT_MODEL,
    val effort: String = "high",
    /** Server-side refusal fallback (beta). */
    val refusalFallback: Boolean = true,
) {
    companion object { const val DEFAULT_MODEL = "claude-opus-5-5" }
}

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    /** A new assistant text block begins (UI starts a new bubble). */
    data object AssistantBlockStart : AgentEvent
    data class ToolStarted(val id: String, val name: String, val summary: String) : AgentEvent
    data class ToolFinished(val id: String, val output: String, val isError: Boolean, val denied: Boolean = false) : AgentEvent
    data class Notice(val text: String) : AgentEvent
    data class Failed(val message: String) : AgentEvent
}

/** Asks the user before a command runs or a file is changed. */
fun interface ToolApprover {
    suspend fun approve(toolName: String, summary: String, input: JsonObject): Boolean
}

/**
 * A coding agent: Claude with SSH / SFTP / Nextcloud tools, run as a manual tool-use loop with streaming.
 * The conversation is kept in memory; [reset] starts a new chat.
 */
class ClaudeAgent(
    private val client: AnthropicClient,
    private val toolbox: AgentToolbox,
    private val settings: () -> AgentSettings,
) {
    private val history = mutableListOf<MessageParam>()
    private val lock = Mutex()

    fun reset() { history.clear() }

    suspend fun send(userText: String, approver: ToolApprover, emit: suspend (AgentEvent) -> Unit) = lock.withLock {
        withContext(Dispatchers.IO) {
            history += MessageParam.ofUser(userText)
            try {
                loop(approver, emit)
            } catch (e: CancellationException) {
                repairAfterCancel()
                throw e
            } catch (e: Exception) {
                repairAfterCancel()
                emit(AgentEvent.Failed(describeError(e)))
            }
        }
    }

    private suspend fun loop(approver: ToolApprover, emit: suspend (AgentEvent) -> Unit) {
        var steps = 0
        while (true) {
            if (++steps > MAX_STEPS) {
                emit(AgentEvent.Notice("Abgebrochen nach $MAX_STEPS Schritten. Schreib „weiter“, um fortzufahren."))
                return
            }
            val message = streamOnce(emit)
            history += message.toParam()

            when (message.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val toolUses = message.content().mapNotNull { it.toolUse().orElse(null) }
                    val results = toolUses.map { runTool(it, approver, emit) }
                    history += MessageParam.ofUserOfContentBlockParams(results)
                }
                StopReason.PAUSE_TURN -> continue
                StopReason.REFUSAL -> {
                    val why = message.stopDetails().flatMap { it.explanation() }.orElse("")
                    emit(AgentEvent.Notice("Claude hat diese Anfrage abgelehnt. $why".trim()))
                    return
                }
                StopReason.MAX_TOKENS -> {
                    // A tool call cut off mid-input can't be answered; drop it so the history stays valid.
                    if (message.content().any { it.toolUse().isPresent }) {
                        history.removeAt(history.lastIndex)
                    }
                    emit(AgentEvent.Notice("Die Antwort hat das Token-Limit erreicht und wurde abgeschnitten."))
                    return
                }
                StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> {
                    emit(AgentEvent.Notice("Das Kontextfenster ist voll. Bitte einen neuen Chat starten."))
                    return
                }
                else -> return
            }
        }
    }

    private suspend fun streamOnce(emit: suspend (AgentEvent) -> Unit): Message {
        val s = settings()
        val params = MessageCreateParams.builder()
            .model(s.model)
            .maxTokens(64_000L)
            .system(SYSTEM_PROMPT)
            .messages(history.toList())
            .tools(toolbox.definitions.map { com.anthropic.models.messages.ToolUnion.ofTool(it) })
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(s.effort)).build())
            // Automatic prompt caching of the growing conversation prefix.
            .cacheControl(CacheControlEphemeral.builder().build())
            .apply {
                if (s.refusalFallback) {
                    putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                    putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                }
            }
            .build()

        val accumulator = MessageAccumulator.create()
        val stream = client.messages().createStreaming(params)
        val job = currentCoroutineContext().job
        val handle = job.invokeOnCompletion { runCatching { stream.close() } }
        try {
            stream.use { sr ->
                val it = sr.stream().iterator()
                while (it.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val event = accumulator.accumulate(it.next())
                    val start = event.contentBlockStart().orElse(null)
                    if (start != null && start.contentBlock().text().isPresent) emit(AgentEvent.AssistantBlockStart)
                    val delta = event.contentBlockDelta().flatMap { d -> d.delta().text() }.orElse(null)
                    if (delta != null) emit(AgentEvent.TextDelta(delta.text()))
                }
            }
        } finally {
            handle.dispose()
        }
        currentCoroutineContext().ensureActive()
        return accumulator.message()
    }

    private suspend fun runTool(use: ToolUseBlock, approver: ToolApprover, emit: suspend (AgentEvent) -> Unit): ContentBlockParam {
        fun result(text: String, isError: Boolean) = ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder().toolUseId(use.id()).content(text).isError(isError).build()
        )

        val input: JsonObject = try {
            val raw = jsonMapper().writeValueAsString(use._input())
            Json.parseToJsonElement(raw).jsonObject
        } catch (e: Exception) {
            emit(AgentEvent.ToolFinished(use.id(), "Ungültige Eingabe", true))
            return result("INVALID_JSON: Die Werkzeug-Eingabe war kein gültiges JSON-Objekt. Bitte erneut senden.", true)
        }
        try {
            toolbox.validate(use.name(), input)
        } catch (e: ToolInputException) {
            emit(AgentEvent.ToolFinished(use.id(), e.message ?: "Ungültige Eingabe", true))
            return result("Ungültige Eingabe: ${e.message}", true)
        }

        val summary = toolbox.describe(use.name(), input)
        emit(AgentEvent.ToolStarted(use.id(), use.name(), summary))
        if (toolbox.requiresApproval(use.name()) && !approver.approve(use.name(), summary, input)) {
            emit(AgentEvent.ToolFinished(use.id(), "Vom Nutzer abgelehnt", isError = true, denied = true))
            return result("Der Nutzer hat diese Aktion abgelehnt. Frage nach, wie er weitermachen möchte.", true)
        }
        val outcome = toolbox.execute(use.name(), input)
        emit(AgentEvent.ToolFinished(use.id(), outcome.text, outcome.isError))
        return result(outcome.text, outcome.isError)
    }

    /** Keeps the history valid after an interruption: every tool_use needs a matching tool_result. */
    private fun repairAfterCancel() {
        val last = history.lastOrNull() ?: return
        val assistant = last.assistant().orElse(null) ?: return
        val toolIds = assistant.content().contentBlockParams().orElse(emptyList())
            .mapNotNull { it.toolUse().orElse(null)?.id() }
        if (toolIds.isEmpty()) return
        history += MessageParam.ofUserOfContentBlockParams(toolIds.map {
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder().toolUseId(it).content("Vom Nutzer abgebrochen.").isError(true).build()
            )
        })
    }

    private fun describeError(e: Exception): String = when (e) {
        is UnauthorizedException -> "API-Key ungültig. Bitte in den Einstellungen prüfen."
        is RateLimitException -> "Rate-Limit erreicht. Bitte kurz warten und erneut senden."
        is AnthropicServiceException -> "API-Fehler ${e.statusCode()}: ${e.message}"
        is AnthropicIoException -> "Netzwerkfehler: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        const val MAX_STEPS = 60

        val SYSTEM_PROMPT = """
            Du bist ein Coding-Agent in der Android-App "Humantize DevApp Mobile". Der Nutzer programmiert unterwegs auf Smartphone oder Tablet.
            Über Werkzeuge hast du Zugriff auf die Verbindungen, die der Nutzer in der App eingerichtet hat:
            SSH-Server (Befehle ausführen, Dateien per SFTP lesen und schreiben) und Nextcloud (Dateien per WebDAV).
            RDP-Verbindungen kann nur der Nutzer selbst über eine Remote-Desktop-App öffnen.

            Arbeitsweise:
            - Wenn unklar ist, welcher Server gemeint ist, rufe list_connections auf. Gibt es genau einen passenden, nimm ihn.
            - Erkunde zuerst (ls, git status, Dateien lesen), bevor du änderst. Lies eine Datei, bevor du sie mit edit_file änderst.
            - Bevorzuge edit_file für Änderungen an bestehenden Dateien, write_file für neue Dateien.
            - Prüfe Änderungen, wo es sinnvoll ist (Build, Tests, Linter) und berichte ehrlich über das Ergebnis.
            - Befehle, Schreib- und Bearbeitungsaktionen muss der Nutzer eventuell bestätigen. Lehnt er ab, frag nach statt es anders zu versuchen.
            - Keine destruktiven Aktionen (rm -rf, Force-Push, Datenbanken löschen, Dienste stoppen) ohne ausdrückliche Bitte.
            - Keine interaktiven Programme starten; nutze nicht-interaktive Flags (z. B. apt-get -y, git --no-pager).
            - Für lang laufende Prozesse (Server, Watcher) nohup/tmux verwenden, damit der Befehl zurückkehrt.

            Antwortstil: Der Bildschirm ist klein. Antworte knapp und in der Sprache des Nutzers (meist Deutsch).
            Markdown ist erlaubt, Codeblöcke kurz halten. Am Ende einer Aufgabe kurz sagen, was geändert wurde und was offen ist.
        """.trimIndent()
    }
}
