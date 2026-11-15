package app.devdeck.data

import app.devdeck.core.agent.AgentSettings
import app.devdeck.core.model.Connection
import app.devdeck.core.model.ConnectionStore
import app.devdeck.core.ssh.KnownHostsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class ConnectionsRepository(private val store: SecureStore) {
    private val _state = MutableStateFlow(
        store.read(FILE)?.let { runCatching { ConnectionStore.fromJson(it) }.getOrNull() } ?: ConnectionStore()
    )
    val state: StateFlow<ConnectionStore> = _state.asStateFlow()

    fun save(c: Connection) = mutate { it.upsert(c) }
    fun delete(id: String) = mutate { it.remove(id) }

    private fun mutate(f: (ConnectionStore) -> ConnectionStore) {
        _state.update(f)
        store.write(FILE, _state.value.toJson())
    }

    companion object { private const val FILE = "connections.json" }
}

@Serializable
data class AppSettings(
    val apiKey: String = "",
    val model: String = AgentSettings.DEFAULT_MODEL,
    val effort: String = "high",
    val autoApprove: Boolean = false,
    val refusalFallback: Boolean = true,
    val terminalFontSize: Int = 13,
    /** "claude_code" = Claude Code on a server with the user's subscription, "api" = API key. */
    val agentMode: String = MODE_CLAUDE_CODE,
    val ccConnectionId: String? = null,
    val ccWorkDir: String = "",
    val ccPermission: String = "EDIT",
    /** Backend used automatically when the main one fails ("" = none). Same ids as [agentMode]. */
    val reserveMode: String = "",
    val ollamaModel: String = "qwen2.5-coder:7b",
    /** Look for a new version when the app starts. */
    val autoUpdateCheck: Boolean = true,
    /** Text file (content URI) that always holds a copy of the servers; null = off. */
    val backupUri: String? = null,
) {
    companion object {
        const val MODE_CLAUDE_CODE = "claude_code"
        const val MODE_API = "api"
    }

    fun agentSettings() = AgentSettings(model = model, effort = effort, refusalFallback = refusalFallback)
}

class SettingsRepository(private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(
        store.read(FILE)?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() } ?: AppSettings()
    )
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    fun update(f: (AppSettings) -> AppSettings) {
        _state.update(f)
        store.write(FILE, json.encodeToString(AppSettings.serializer(), _state.value))
    }

    companion object { private const val FILE = "settings.json" }
}

/** SSH host key fingerprints, trust on first use. */
class KnownHostsRepository(private val store: SecureStore) : KnownHostsStore {
    private val json = Json
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())
    private val map: MutableMap<String, String> =
        store.read(FILE)?.let { runCatching { json.decodeFromString(mapSerializer, it) }.getOrNull() }?.toMutableMap()
            ?: mutableMapOf()

    @Synchronized override fun get(hostPort: String): String? = map[hostPort]

    @Synchronized override fun put(hostPort: String, fingerprint: String) {
        map[hostPort] = fingerprint
        persist()
    }

    @Synchronized fun forget(hostPort: String) {
        map.remove(hostPort)
        persist()
    }

    private fun persist() = store.write(FILE, json.encodeToString(mapSerializer, map.toMap()))

    companion object { private const val FILE = "known_hosts.json" }
}
