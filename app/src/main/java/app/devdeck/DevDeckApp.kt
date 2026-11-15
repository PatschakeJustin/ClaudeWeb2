package app.devdeck

import android.app.Application
import app.devdeck.core.agent.AgentToolbox
import app.devdeck.core.ai.OpenAiCompatClient
import app.devdeck.core.ai.SelfHostedSession
import app.devdeck.core.agent.ClaudeAgent
import app.devdeck.core.claudecode.ClaudeCodePermission
import app.devdeck.core.claudecode.LoginStep
import app.devdeck.core.claudecode.CliAgent
import app.devdeck.core.claudecode.CliAgentSession
import app.devdeck.core.claudecode.ClaudeCodeSession
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.flow.MutableStateFlow
import app.devdeck.data.ConnectionsRepository
import app.devdeck.data.KnownHostsRepository
import app.devdeck.data.SecureStore
import app.devdeck.data.SettingsRepository
import app.devdeck.data.Updater
import app.devdeck.data.ServerBackup
import app.devdeck.ui.terminal.TerminalViewModel
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import okhttp3.OkHttpClient
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class DevDeckApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC" provider; sshj needs the full BouncyCastle.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        installCrashRecorder()
        container = AppContainer(this)
    }

    /** Writes the stack trace of a crash to a file; the app shows it on the next start so it can be sent in. */
    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                java.io.File(filesDir, CRASH_FILE).writeText(
                    "Humantize DevApp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), " +
                        "Android ${android.os.Build.VERSION.RELEASE}, ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                        "Thread: ${thread.name}\n\n" + error.stackTraceToString()
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    companion object { const val CRASH_FILE = "last_crash.txt" }
}

data class TerminalRequest(
    val connectionId: String,
    val input: String? = null,
    /** Guided logins to run one after another (opens each login page automatically). */
    val logins: List<LoginStep> = emptyList(),
)

class AppContainer(private val app: Application) {
    private val secure = SecureStore(app)
    val connections = ConnectionsRepository(secure)
    val settings = SettingsRepository(secure)
    val knownHosts = KnownHostsRepository(secure)
    val http = OkHttpClient()
    val updater = Updater(app, http)
    val backup = ServerBackup(app, connections, settings)
    val ssh = SshManager(knownHosts)
    val toolbox = AgentToolbox({ connections.state.value }, ssh, http)

    val aiClient = OpenAiCompatClient(http, ssh) { id -> connections.state.value.ssh.firstOrNull { it.id == id } }

    fun newSelfHostedSession(aiId: String) = SelfHostedSession(aiClient) { connections.state.value.ai.firstOrNull { it.id == aiId } }

    /** The terminal session lives as long as the app process (not tied to a screen). */
    val terminal: TerminalViewModel by lazy { TerminalViewModel(this, app) }

    /** Lets other screens ask the terminal to connect to a server and type something (e.g. `claude`). */
    val terminalRequests = MutableStateFlow<TerminalRequest?>(null)

    fun newCliSession(agent: CliAgent) = CliAgentSession(
        ssh, agent,
        connection = { connections.state.value.ssh.firstOrNull { it.id == settings.state.value.ccConnectionId } },
        workDir = {
            val s = settings.state.value
            s.ccWorkDir.ifBlank { connections.state.value.ssh.firstOrNull { it.id == s.ccConnectionId }?.defaultDirectory.orEmpty() }
        },
        permission = { runCatching { ClaudeCodePermission.valueOf(settings.state.value.ccPermission) }.getOrDefault(ClaudeCodePermission.EDIT) },
        ollamaModel = { settings.state.value.ollamaModel },
    )

    fun newClaudeCodeSession() = ClaudeCodeSession(
        ssh,
        connection = {
            val id = settings.state.value.ccConnectionId
            connections.state.value.ssh.firstOrNull { it.id == id }
        },
        workDir = {
            val s = settings.state.value
            s.ccWorkDir.ifBlank { connections.state.value.ssh.firstOrNull { it.id == s.ccConnectionId }?.defaultDirectory.orEmpty() }
        },
        permission = { runCatching { ClaudeCodePermission.valueOf(settings.state.value.ccPermission) }.getOrDefault(ClaudeCodePermission.EDIT) },
    )

    private var cachedKey: String? = null
    private var cachedClient: AnthropicClient? = null

    /** A new agent (= new conversation) using the current API key; null if no key is set. */
    @Synchronized
    fun newAgent(): ClaudeAgent? {
        val key = settings.state.value.apiKey.trim()
        if (key.isEmpty()) return null
        if (key != cachedKey || cachedClient == null) {
            cachedClient = AnthropicOkHttpClient.builder().apiKey(key).build()
            cachedKey = key
        }
        return ClaudeAgent(cachedClient!!, toolbox) { settings.state.value.agentSettings() }
    }
}
