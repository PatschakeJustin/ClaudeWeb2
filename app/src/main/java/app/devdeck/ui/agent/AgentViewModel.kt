package app.devdeck.ui.agent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devdeck.AppContainer
import app.devdeck.core.agent.AgentEvent
import app.devdeck.core.agent.ClaudeAgent
import app.devdeck.core.agent.ToolApprover
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
    private var job: Job? = null
    private var approveAllInChat = false
    private val ids = AtomicLong()

    private fun nextKey() = "i" + ids.incrementAndGet()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _running.value) return
        val a = agent ?: container.newAgent().also { agent = it }
        if (a == null) {
            append(ChatItem.Notice(nextKey(), "Kein API-Key gesetzt. Bitte unter Einstellungen den Anthropic-API-Key eintragen.", true))
            return
        }
        append(ChatItem.User(nextKey(), trimmed))
        _running.value = true
        job = viewModelScope.launch {
            try {
                a.send(trimmed, approver) { onEvent(it) }
            } finally {
                _running.value = false
                _approval.value?.answer?.complete(false)
                _approval.value = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        append(ChatItem.Notice(nextKey(), "Abgebrochen.", false))
    }

    fun newChat() {
        job?.cancel()
        agent = null // fresh conversation (and picks up a changed API key)
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
