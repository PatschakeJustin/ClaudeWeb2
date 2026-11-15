package app.devdeck

import android.app.Application
import app.devdeck.core.agent.AgentToolbox
import app.devdeck.core.agent.ClaudeAgent
import app.devdeck.core.ssh.SshManager
import app.devdeck.data.ConnectionsRepository
import app.devdeck.data.KnownHostsRepository
import app.devdeck.data.SecureStore
import app.devdeck.data.SettingsRepository
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
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val secure = SecureStore(app)
    val connections = ConnectionsRepository(secure)
    val settings = SettingsRepository(secure)
    val knownHosts = KnownHostsRepository(secure)
    val http = OkHttpClient()
    val ssh = SshManager(knownHosts)
    val toolbox = AgentToolbox({ connections.state.value }, ssh, http)

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
