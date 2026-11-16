package app.devdeck.ui.terminal

import android.content.Context
import app.devdeck.AppContainer
import app.devdeck.TerminalService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import app.devdeck.core.model.SshConnection
import app.devdeck.core.ssh.ShellSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import app.devdeck.core.claudecode.LOGIN_STATUS_SCRIPT
import app.devdeck.core.claudecode.LoginStep
import app.devdeck.core.claudecode.extractLoginUrls
import app.devdeck.core.claudecode.loginSucceeded
import app.devdeck.core.claudecode.pickLoginUrl
import app.devdeck.core.claudecode.findDeviceCode
import app.devdeck.core.claudecode.loginSteps
import app.devdeck.core.claudecode.stripTerminalCodes
import java.io.ByteArrayOutputStream

/**
 * The terminal session. Owned by the app (not by a screen), so it survives leaving the app, screen changes and
 * rebuilt activities; a foreground service keeps the process alive while connected.
 */
class TerminalViewModel(private val container: AppContainer, private val appContext: Context) {
    private val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

    /** Guided login in progress (null = none). */
    data class LoginState(val steps: List<LoginStep>, val index: Int, val done: Boolean = false) {
        val step get() = steps[index]
        val isLast get() = index == steps.lastIndex
    }

    /** Login link / one-time code currently visible in the terminal (detected in any session, not only guided logins). */
    data class Detected(val url: String? = null, val code: String? = null)

    private val _detected = MutableStateFlow(Detected())
    val detected: StateFlow<Detected> = _detected.asStateFlow()

    private val _login = MutableStateFlow<LoginState?>(null)
    val login: StateFlow<LoginState?> = _login.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    private val scan = StringBuilder()
    private val openedUrls = mutableSetOf<String>()
    @Volatile private var lastOutputAt = 0L
    private var currentConn: SshConnection? = null
    private var followUpJob: Job? = null

    private val _ctrlArmed = MutableStateFlow(false)
    val ctrlArmed: StateFlow<Boolean> = _ctrlArmed.asStateFlow()

    private var shell: ShellSession? = null

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
        lastOutputAt = System.currentTimeMillis()
        appendForScan(data)
        if (buffer.size() > MAX_BUFFER) {
            val keep = buffer.toByteArray().let { it.copyOfRange(it.size - MAX_BUFFER / 2, it.size) }
            buffer.reset(); buffer.write(keep)
        }
        buffer.write(data)
        sink?.invoke(data)
        Unit
    }

    private fun local(text: String) = output(text.replace("\n", "\r\n").toByteArray())

    @Volatile private var userClosed = false
    private var reconnectJob: Job? = null

    fun connect(conn: SshConnection, initialInput: String? = null, logins: List<LoginStep> = emptyList()) =
        open(conn, initialInput, logins, reconnect = false)

    private fun open(conn: SshConnection, initialInput: String?, logins: List<LoginStep>, reconnect: Boolean) {
        if (!reconnect) reconnectJob?.cancel()
        closeShell()
        userClosed = false
        currentConn = conn
        followUpJob?.cancel()
        _login.value = if (logins.isEmpty()) null else LoginState(logins, 0)
        synchronized(scanLock) { scan.clear() }
        _detected.value = Detected()
        if (!reconnect) {
            synchronized(lock) { buffer.reset() }
            sink?.invoke("\u001bc".toByteArray()) // reset the visible terminal
        }
        _status.value = Status.Connecting(conn.name)
        local(if (reconnect) "\n[Verbinde neu mit ${conn.name} …]\n" else "Verbinde mit ${conn.username}@${conn.host}:${conn.port} …\n")
        TerminalService.start(appContext, conn.name)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                shell = openShellFor(conn, plain = logins.isEmpty(), fresh = reconnect)
                _status.value = Status.Connected(conn.name)
                TerminalService.start(appContext, conn.name)
                // Plain sessions run inside tmux on the server: if the connection drops, reconnecting
                // brings back exactly the same session (including a running Claude Code).
                if (logins.isEmpty() && !conn.windows && container.settings.state.value.terminalTmux) {
                    // "mouse on" lets swipe gestures (sent as wheel events) scroll through tmux's history.
                    shell?.write(
                        "command -v tmux >/dev/null 2>&1 && exec tmux new -A -s humantize \\; " +
                            "set -g mouse on \\; set -g history-limit 20000\r"
                    )
                    if (initialInput != null) delay(800)
                }
                if (initialInput != null) shell?.write(initialInput)
                if (logins.isNotEmpty()) startStep(0)
            } catch (e: Exception) {
                local("Fehler: ${e.message ?: e.javaClass.simpleName}\n")
                _status.value = Status.Closed(conn.name, e.message)
                if (reconnect) scheduleReconnect(conn) else TerminalService.stop(appContext)
            }
        }
    }

    /** After an unexpected disconnect: retry a few times with growing pauses. */
    private fun scheduleReconnect(conn: SshConnection) {
        if (userClosed) return
        val attempt = reconnectAttempts++
        if (attempt >= RECONNECT_DELAYS.size) {
            local("\n[Neuverbindung aufgegeben – oben den Server erneut wählen]\n")
            TerminalService.stop(appContext)
            return
        }
        reconnectJob = viewModelScope.launch {
            delay(RECONNECT_DELAYS[attempt])
            if (!userClosed && currentConn?.id == conn.id) open(conn, null, emptyList(), reconnect = true)
        }
    }

    private var reconnectAttempts = 0

    // Each shell gets a generation number; events of an old shell (closed on purpose) are ignored.
    @Volatile private var generation = 0

    private suspend fun openShellFor(conn: SshConnection, plain: Boolean = true, fresh: Boolean = false): ShellSession {
        val gen = ++generation
        // After a drop the cached SSH client may be half-dead (network change): start from a fresh connection.
        if (fresh) container.ssh.disconnect(conn.id)
        val session = container.ssh.openShell(conn, ptyCols(), rows, object : ShellSession.Listener {
            override fun onOutput(data: ByteArray) { if (gen == generation) output(data) }
            override fun onClosed(error: Throwable?) {
                if (gen != generation) return
                local("\n[Verbindung beendet${error?.message?.let { ": $it" } ?: ""}]\n")
                _status.value = Status.Closed(conn.name, error?.message)
                if (!userClosed && plain && _login.value == null) scheduleReconnect(conn) else TerminalService.stop(appContext)
            }
        })
        reconnectAttempts = 0
        return session
    }

    private fun closeShell() {
        val old = shell ?: return
        shell = null
        generation++
        // Network I/O must not run on the main thread (it would fail and leave the remote shell open).
        viewModelScope.launch(Dispatchers.IO) { runCatching { old.close() } }
    }

    /** Connects again to the last server (after a disconnect). */
    fun reconnect() { currentConn?.let { connect(it) } }

    /** Disconnect on purpose (button, notification): no automatic reconnect. */
    fun disconnect() {
        userClosed = true
        reconnectJob?.cancel()
        followUpJob?.cancel()
        closeShell()
        currentConn?.let { _status.value = Status.Closed(it.name, null) }
        local("\n[Getrennt]\n")
        TerminalService.stop(appContext)
    }

    fun onResize(c: Int, r: Int) {
        if (c <= 0 || r <= 0) return
        cols = c; rows = r
        val s = shell ?: return
        viewModelScope.launch(Dispatchers.IO) { s.resize(c, r) }
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

    private fun ptyCols() = cols

    // Login-link detection runs throttled on a background thread. It must never block the output path:
    // TUIs redraw many times per second and the UI thread waits on [lock] when attaching the view.
    private val scanLock = Any()
    @Volatile private var scanDirty = false
    private var scanJob: Job? = null

    private fun appendForScan(data: ByteArray) {
        synchronized(scanLock) {
            scan.append(data.toString(Charsets.UTF_8))
            if (scan.length > 24_000) scan.delete(0, scan.length - 16_000)
        }
        scanDirty = true
        if (scanJob?.isActive != true) {
            scanJob = viewModelScope.launch(Dispatchers.Default) {
                while (scanDirty) {
                    scanDirty = false
                    delay(400)
                    runCatching { scanForLogin() }
                }
            }
        }
    }

    private fun scanForLogin() {
        val raw = synchronized(scanLock) { scan.toString() }
        val guided = _login.value != null
        val url = pickLoginUrl(extractLoginUrls(raw, anyUrl = guided))
        val plain = if (guided) stripTerminalCodes(raw) else ""
        val code = if (guided) findDeviceCode(plain) else null
        updateDetected(url, code)
        // A success message after the login link appeared: mark done and move on by itself.
        val st = _login.value
        if (st != null && !st.done && _detected.value.url != null && loginSucceeded(plain)) {
            _login.value = st.copy(done = true)
            autoNextJob?.cancel()
            autoNextJob = viewModelScope.launch {
                delay(3000)
                if (_login.value?.let { it.done && it.index == st.index } == true) nextLoginStep()
            }
        }
    }

    /** Links found in the rendered terminal buffer (second source during a guided login). */
    fun onBufferLinks(urls: List<String>) {
        if (_login.value != null && _detected.value.url == null) updateDetected(pickLoginUrl(urls), null)
    }

    private fun updateDetected(url: String?, code: String?) {
        val cur = _detected.value
        val next = Detected(url ?: cur.url, code ?: cur.code)
        if (next != cur) _detected.value = next
    }

    fun dismissDetected() { _detected.value = Detected() }

    /** True the first time a login page should be opened (the screen opens it in the browser). */
    fun shouldOpen(url: String): Boolean = synchronized(openedUrls) { openedUrls.add(url) }

    private var autoNextJob: Job? = null

    private fun startStep(index: Int) {
        val st = _login.value ?: return
        val step = st.steps[index]
        val conn = currentConn ?: return
        autoNextJob?.cancel()
        synchronized(scanLock) { scan.clear() }
        _detected.value = Detected()
        _login.value = st.copy(index = index, done = false)
        followUpJob?.cancel()
        followUpJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (index > 0) {
                    // Fresh shell for every tool: nothing left over from the previous login.
                    val old = shell
                    shell = null
                    generation++
                    runCatching { old?.close() }  // already on Dispatchers.IO
                    local("\n\n── ${step.label} (${index + 1}/${st.steps.size}) ──\n")
                    shell = openShellFor(conn)
                } else {
                    shell?.resize(ptyCols(), rows)
                }
                val s = shell ?: return@launch
                delay(500) // let the shell print its prompt
                s.write(step.command)
                step.followUp?.let { follow ->
                    // Wait until the tool's UI has settled before typing /login or /auth.
                    val started = System.currentTimeMillis()
                    delay(1500)
                    while (System.currentTimeMillis() - lastOutputAt < 2500 && System.currentTimeMillis() - started < 25_000) delay(300)
                    s.write(follow)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                local("\nFehler beim Start von ${step.label}: ${e.message}\n")
            }
        }
    }

    fun nextLoginStep() {
        val st = _login.value ?: return
        if (st.isLast) finishLogin() else startStep(st.index + 1)
    }

    fun finishLogin() {
        followUpJob?.cancel()
        autoNextJob?.cancel()
        _login.value = null
        _messages.tryEmit("Anmeldung beendet. Claude Code und Gemini/Qwen mit /exit bzw. Ctrl-C zweimal verlassen.")
    }

    /** Pastes the clipboard text (e.g. the code from the login page) and presses Enter. */
    fun pasteAndEnter(text: String) = send(text.trim() + "\r")

    /** Checks what is installed on the current server and logs in to everything again. */
    fun reloginAll() {
        val conn = currentConn ?: return
        viewModelScope.launch {
            try {
                val steps = loginSteps(container.ssh.exec(conn, LOGIN_STATUS_SCRIPT, timeoutSeconds = 30).stdout)
                if (steps.isEmpty()) _messages.tryEmit("Auf ${conn.name} ist nichts zum Anmelden installiert (Verbindungen → Server einrichten).")
                else connect(conn, null, steps)
            } catch (e: Exception) {
                _messages.tryEmit("Fehler: ${e.message}")
            }
        }
    }

    fun toggleCtrl() { _ctrlArmed.value = !_ctrlArmed.value }

    fun setFontSize(size: Int) = container.settings.update { it.copy(terminalFontSize = size.coerceIn(8, 28)) }

    // Must stay the LAST declaration: the collector may run immediately (Main.immediate) and call connect(),
    // which needs every field above to be initialised. (Placed earlier it crashed with a NullPointerException.)
    init {
        // Requests from other screens, e.g. "log in to Claude Code on this server".
        viewModelScope.launch {
            container.terminalRequests.collect { req ->
                if (req != null) {
                    container.terminalRequests.value = null
                    container.connections.state.value.ssh.firstOrNull { it.id == req.connectionId }?.let { connect(it, req.input, req.logins) }
                }
            }
        }
    }

    companion object {
        private const val MAX_BUFFER = 1 shl 20
        private val RECONNECT_DELAYS = longArrayOf(1_000, 3_000, 6_000, 12_000, 25_000, 45_000)
    }
}
