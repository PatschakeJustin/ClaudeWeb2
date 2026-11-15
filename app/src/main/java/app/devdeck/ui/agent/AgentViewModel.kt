package app.devdeck.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devdeck.AppContainer
import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.agent.ClaudeAgent
import app.devdeck.core.agent.ToolApprover
import app.devdeck.core.claudecode.ClaudeCodePermission
import app.devdeck.core.claudecode.ClaudeCodeSession
import app.devdeck.core.claudecode.CliAgent
import app.devdeck.core.claudecode.CliAgentSession
import app.devdeck.data.AppSettings
import app.devdeck.core.ai.SelfHostedSession
import app.devdeck.core.model.ConnectionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.atomic.AtomicLong

sealed interface ChatItem {
    val key: String
    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(override val key: String, val text: String) : ChatItem
    data class ToolCall(
        override val key: String,
        val name: String,
        val summary: String,
        val output: String? = null,
        val isError: Boolean = false,
        val denied: Boolean = false,
    ) : ChatItem {
        val running get() = output == null
    }
    data class Notice(override val key: String, val text: String, val isError: Boolean) : ChatItem
}

data class PendingApproval(
    val toolName: String,
    val summary: String,
    val detail: String?,
    val answer: CompletableDeferred<Boolean>,
)

class AgentViewModel(private val container: AppContainer) : ViewModel() {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    val settings = container.settings.state
    val connections = container.connections.state

    private var agent: ClaudeAgent? = null
    private var ccSession: ClaudeCodeSession? = null
    private val cliSessions = mutableMapOf<CliAgent, CliAgentSession>()
    private val aiSessions = mutableMapOf<String, SelfHostedSession>()
    private var job: Job? = null
    private var approveAllInChat = false
    private val ids = AtomicLong()

    private fun nextKey() = "i" + ids.incrementAndGet()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _running.value) return
        val st = container.settings.state.value
        append(ChatItem.User(nextKey(), trimmed))
        _running.value = true
        job = viewModelScope.launch {
            try {
                val ok = runBackend(st.agentMode, trimmed)
                val reserve = st.reserveMode
                if (!ok && reserve.isNotEmpty() && reserve != st.agentMode) {
                    append(ChatItem.Notice(nextKey(), "Wechsle zur Reserve: ${backendLabel(reserve, container.connections.state.value)} …", false))
                    runBackend(reserve, trimmed)
                }
            } finally {
                _running.value = false
                _approval.value?.answer?.complete(false)
                _approval.value = null
            }
        }
    }

    /** Runs one turn on the given backend; returns false if it failed. */
    private suspend fun runBackend(mode: String, prompt: String): Boolean {
        var failed = false
        val emit: (AgentEvent) -> Unit = { e -> if (e is AgentEvent.Failed) failed = true; onEvent(e) }
        val st = container.settings.state.value
        if (mode.startsWith(AI_PREFIX)) {
            val id = mode.removePrefix(AI_PREFIX)
            if (container.connections.state.value.ai.none { it.id == id }) {
                emit(AgentEvent.Failed("Dieser KI-Server existiert nicht mehr. Bitte oben ein anderes Modell wählen."))
                return false
            }
            aiSessions.getOrPut(id) { container.newSelfHostedSession(id) }.send(prompt, emit)
            return !failed
        }
        if (mode != AppSettings.MODE_API && container.connections.state.value.ssh.none { it.id == st.ccConnectionId }) {
            emit(AgentEvent.Failed("Bitte oben einen Server wählen (Verbindungen → „Server einrichten“)."))
            return false
        }
        when (mode) {
            AppSettings.MODE_CLAUDE_CODE -> {
                val session = ccSession ?: container.newClaudeCodeSession().also { ccSession = it }
                session.send(prompt, emit)
            }
            AppSettings.MODE_API -> {
                val a = agent ?: container.newAgent().also { agent = it }
                if (a == null) {
                    emit(AgentEvent.Failed("Kein API-Key gesetzt. Bitte unter Einstellungen eintragen oder ein anderes Modell wählen."))
                } else {
                    a.send(prompt, approver) { emit(it) }
                }
            }
            else -> {
                val cli = CliAgent.byId(mode)
                if (cli == null) emit(AgentEvent.Failed("Unbekanntes Modell: $mode"))
                else (cliSessions.getOrPut(cli) { container.newCliSession(cli) }).send(prompt, emit)
            }
        }
        return !failed
    }

    fun setMode(mode: String) {
        if (mode == container.settings.state.value.agentMode) return
        container.settings.update { it.copy(agentMode = mode) }
        newChat()
    }

    fun setClaudeCodeServer(id: String) {
        container.settings.update { it.copy(ccConnectionId = id, ccWorkDir = "") }
        newChat()
    }

    fun setClaudeCodeWorkDir(dir: String) {
        container.settings.update { it.copy(ccWorkDir = dir.trim()) }
        newChat()
    }

    fun setReserve(mode: String) = container.settings.update { it.copy(reserveMode = mode) }

    fun setClaudeCodePermission(p: ClaudeCodePermission) = container.settings.update { it.copy(ccPermission = p.name) }

    fun stop() {
        job?.cancel()
        append(ChatItem.Notice(nextKey(), "Abgebrochen.", false))
    }

    fun newChat() {
        job?.cancel()
        agent = null // fresh conversation (and picks up a changed API key)
        ccSession = null
        cliSessions.clear()
        aiSessions.clear()
        approveAllInChat = false
        _items.value = emptyList()
    }

    fun answerApproval(allow: Boolean, always: Boolean = false) {
        val p = _approval.value ?: return
        if (allow && always) approveAllInChat = true
        _approval.value = null
        p.answer.complete(allow)
    }

    private val approver = ToolApprover { toolName, summary, input ->
        if (container.settings.state.value.autoApprove || approveAllInChat) return@ToolApprover true
        fun str(k: String) = (input[k] as? JsonPrimitive)?.contentOrNull ?: ""
        val detail = when (toolName) {
            "write_file" -> str("content").take(4000)
            "edit_file" -> str("old_string").lines().joinToString("\n") { "- $it" } + "\n" +
                str("new_string").lines().joinToString("\n") { "+ $it" }
            else -> null
        }
        val deferred = CompletableDeferred<Boolean>()
        _approval.value = PendingApproval(toolName, summary, detail, deferred)
        deferred.await()
    }

    private fun onEvent(e: AgentEvent) {
        when (e) {
            AgentEvent.AssistantBlockStart -> append(ChatItem.Assistant(nextKey(), ""))
            is AgentEvent.TextDelta -> _items.update { list ->
                val last = list.lastOrNull()
                if (last is ChatItem.Assistant) list.dropLast(1) + last.copy(text = last.text + e.text)
                else list + ChatItem.Assistant(nextKey(), e.text)
            }
            is AgentEvent.ToolStarted -> append(ChatItem.ToolCall(e.id, e.name, e.summary))
            is AgentEvent.ToolFinished -> _items.update { list ->
                if (list.any { it.key == e.id }) list.map {
                    if (it is ChatItem.ToolCall && it.key == e.id) it.copy(output = e.output, isError = e.isError, denied = e.denied) else it
                } else list + ChatItem.Notice(nextKey(), e.output, true)
            }
            is AgentEvent.Notice -> append(ChatItem.Notice(nextKey(), e.text, false))
            is AgentEvent.Failed -> append(ChatItem.Notice(nextKey(), e.message, true))
        }
    }

    private fun append(item: ChatItem) = _items.update { it + item }
}

const val AI_PREFIX = "ai:"

/** All selectable backends (id to label), including the user's self-hosted model servers. */
fun backends(store: ConnectionStore): List<Pair<String, String>> = listOf(
    AppSettings.MODE_CLAUDE_CODE to "Claude Code (Abo)",
    AppSettings.MODE_API to "Claude (API-Key)",
) + CliAgent.entries.map { it.id to it.label } + store.ai.map { AI_PREFIX + it.id to "KI: ${it.name}" }

fun backendLabel(id: String, store: ConnectionStore) = backends(store).firstOrNull { it.first == id }?.second ?: id

/** Backends that run on the selected SSH server (and need server/folder/permission settings). */
fun runsOnServer(id: String) = id != AppSettings.MODE_API && !id.startsWith(AI_PREFIX)
