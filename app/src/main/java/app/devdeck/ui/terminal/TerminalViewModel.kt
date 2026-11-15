package app.devdeck.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devdeck.AppContainer
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.ShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class TerminalViewModel(private val container: AppContainer) : ViewModel() {
    sealed interface Status {
        data object Idle : Status
        data class Connecting(val name: String) : Status
        data class Connected(val name: String) : Status
        data class Closed(val name: String, val error: String?) : Status
    }

    val connections = container.connections.state
    val settings = container.settings.state

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _ctrlArmed = MutableStateFlow(false)
    val ctrlArmed: StateFlow<Boolean> = _ctrlArmed.asStateFlow()

    private var shell: ShellSession? = null

    init {
        // Requests from other screens, e.g. "log in to Claude Code on this server".
        viewModelScope.launch {
            container.terminalRequests.collect { req ->
                if (req != null) {
                    container.terminalRequests.value = null
                    container.connections.state.value.ssh.firstOrNull { it.id == req.connectionId }?.let { connect(it, req.input) }
                }
            }
        }
    }
    private var cols = 80
    private var rows = 24

    // Scrollback kept here so the terminal can be redrawn after the WebView is recreated.
    private val lock = Any()
    private val buffer = ByteArrayOutputStream()
    private var sink: ((ByteArray) -> Unit)? = null

    /** Attaches a terminal view; replays what was printed so far. */
    fun attach(newSink: (ByteArray) -> Unit): Unit = synchronized(lock) {
        if (buffer.size() > 0) newSink(buffer.toByteArray())
        sink = newSink
    }

    fun detach(): Unit = synchronized(lock) { sink = null }

    private fun output(data: ByteArray): Unit = synchronized(lock) {
        if (buffer.size() > MAX_BUFFER) {
            val keep = buffer.toByteArray().let { it.copyOfRange(it.size - MAX_BUFFER / 2, it.size) }
            buffer.reset(); buffer.write(keep)
        }
        buffer.write(data)
        sink?.invoke(data)
        Unit
    }

    private fun local(text: String) = output(text.replace("\n", "\r\n").toByteArray())

    fun connect(conn: SshConnection, initialInput: String? = null) {
        disconnect()
        synchronized(lock) { buffer.reset() }
        sink?.invoke("\u001bc".toByteArray()) // reset the visible terminal
        _status.value = Status.Connecting(conn.name)
        local("Verbinde mit ${conn.username}@${conn.host}:${conn.port} …\n")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shell = container.ssh.openShell(conn, cols, rows, object : ShellSession.Listener {
                    override fun onOutput(data: ByteArray) { output(data) }
                    override fun onClosed(error: Throwable?) {
                        local("\n[Verbindung beendet${error?.message?.let { ": $it" } ?: ""}]\n")
                        _status.value = Status.Closed(conn.name, error?.message)
                    }
                })
                _status.value = Status.Connected(conn.name)
                if (initialInput != null) shell?.write(initialInput)
            } catch (e: Exception) {
                local("Fehler: ${e.message ?: e.javaClass.simpleName}\n")
                _status.value = Status.Closed(conn.name, e.message)
            }
        }
    }

    fun disconnect() {
        shell?.close()
        shell = null
    }

    fun onResize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        cols = c; rows = r
        shell?.resize(c, r)
    }

    /** Input from the terminal view (keyboard). Applies the sticky Ctrl key. */
    fun onInput(data: String) {
        var d = data
        if (_ctrlArmed.value && d.length == 1) {
            val ch = d[0].uppercaseChar()
            if (ch in '@'..'_') d = (ch.code - 64).toChar().toString()
            _ctrlArmed.value = false
        }
        send(d)
    }

    fun send(text: String) {
        val s = shell ?: return
        viewModelScope.launch(Dispatchers.IO) { runCatching { s.write(text) } }
    }

    fun toggleCtrl() { _ctrlArmed.value = !_ctrlArmed.value }

    fun setFontSize(size: Int) = container.settings.update { it.copy(terminalFontSize = size.coerceIn(8, 28)) }

    override fun onCleared() {
        disconnect()
    }

    companion object { private const val MAX_BUFFER = 1 shl 20 }
}
