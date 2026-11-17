package app.devdeck.data

import app.devdeck.core.hub.HubClient
import app.devdeck.core.hub.HubException
import app.devdeck.core.hub.HubKeys
import app.devdeck.core.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Logged-in hub account (stored encrypted on the device; the encryption key never leaves it). */
@Serializable
data class HubAccount(
    val url: String,
    val fingerprint: String,
    val email: String,
    val token: String,
    val encKeyHex: String,
    val version: Int = 0,
)

/** What is synced: all servers, the settings and the widgets – encrypted end to end. */
@Serializable
data class VaultPayload(
    val connections: ConnectionStore = ConnectionStore(),
    val settings: AppSettings? = null,
    val widgets: JsonArray = JsonArray(emptyList()),
    val planner: app.devdeck.core.planner.PlannerData = app.devdeck.core.planner.PlannerData(),
)

/** Hub address + fingerprint learned from a server installation, used to pre-fill the login form. */
@Serializable
data class KnownHub(val url: String, val fingerprint: String)

class HubSync(
    private val store: SecureStore,
    private val connections: ConnectionsRepository,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val widgetsJson: () -> JsonArray = { JsonArray(emptyList()) },
    private val applyWidgets: (JsonArray) -> Unit = {},
    /** Further changes that should trigger a sync (e.g. the widget list). */
    private val changes: kotlinx.coroutines.flow.Flow<Any?> = kotlinx.coroutines.flow.flowOf(Unit),
    private val planner: PlannerRepository? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _account = MutableStateFlow(store.read(FILE)?.let { runCatching { json.decodeFromString(HubAccount.serializer(), it) }.getOrNull() })
    val account: StateFlow<HubAccount?> = _account.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _knownHub = MutableStateFlow(store.read(KNOWN)?.let { runCatching { json.decodeFromString(KnownHub.serializer(), it) }.getOrNull() })
    val knownHub: StateFlow<KnownHub?> = _knownHub.asStateFlow()

    private var lastSynced: String? = null

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            combine(connections.state, settings.state, changes, planner?.data ?: kotlinx.coroutines.flow.flowOf(Unit)) { _, _, _, _ -> }
                .drop(1).debounce(2000).collect {
                if (_account.value != null) runCatching { sync() }
            }
        }
        if (_account.value != null) scope.launch { runCatching { sync() } }
    }

    fun rememberHub(url: String, fingerprint: String) {
        val k = KnownHub(url, fingerprint)
        _knownHub.value = k
        store.write(KNOWN, json.encodeToString(KnownHub.serializer(), k))
    }

    private fun client(a: HubAccount) = HubClient(a.url, a.fingerprint, http)
    private fun keys(a: HubAccount) = HubKeys("", hexToBytes(a.encKeyHex))

    private fun localPayload(): String = json.encodeToString(VaultPayload.serializer(), VaultPayload(
        connections = connections.state.value,
        settings = settings.state.value.copy(backupUri = null),
        widgets = widgetsJson(),
        planner = planner?.data?.value ?: app.devdeck.core.planner.PlannerData(),
    ))

    /** Logs in (or registers) and merges the account's data with what is on this device. */
    suspend fun login(url: String, fingerprint: String, email: String, password: String, register: Boolean) {
        val c = HubClient(url, fingerprint, http)
        val (token, keys) = if (register) c.register(email, password) else c.login(email, password)
        val acc = HubAccount(url.trim().trimEnd('/'), fingerprint, email.trim().lowercase(), token, keys.encKey.toHex())
        save(acc)
        rememberHub(acc.url, fingerprint)
        sync()
    }

    fun logout() {
        _account.value = null
        store.write(FILE, "")
        lastSynced = null
        _status.value = "Abgemeldet. Die Daten auf diesem Gerät bleiben erhalten."
    }

    /** Pull remote changes, merge, push local changes. Retries once on a version conflict. */
    suspend fun sync(): Unit = mutex.withLock {
        val acc = _account.value ?: return
        try {
            repeat(2) { attempt ->
                val cur = _account.value ?: return
                val remote = client(cur).pullVault(cur.token, keys(cur))
                val remoteText = remote.plaintext
                if (remoteText != null && remote.version != cur.version) applyRemote(remoteText)
                save(cur.copy(version = remote.version))
                val local = localPayload()
                if (local == lastSynced && remote.plaintext != null) { done(); return }
                try {
                    val v = client(cur).pushVault(cur.token, keys(cur), remote.version, local)
                    save(cur.copy(version = v))
                    lastSynced = local
                    done(); return
                } catch (e: HubException) {
                    if (e.status != 409 || attempt == 1) throw e
                }
            }
        } catch (e: HubException) {
            _status.value = if (e.status == 401) "Sitzung abgelaufen – bitte neu anmelden." else "Sync-Fehler: ${e.message}"
            if (e.status == 401) logout()
        } catch (e: Exception) {
            _status.value = "Hub nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** Remote data wins for entries it has; local-only entries are kept (union by id). */
    private fun applyRemote(text: String) {
        val remote = runCatching { json.decodeFromString(VaultPayload.serializer(), text) }.getOrNull() ?: return
        val local = connections.state.value
        remote.connections.all.forEach { connections.save(it) }
        local.all.filter { l -> remote.connections.all.none { it.id == l.id } }.forEach { connections.save(it) }
        remote.settings?.let { r -> settings.update { cur -> r.copy(backupUri = cur.backupUri) } }
        if (remote.widgets.isNotEmpty()) applyWidgets(remote.widgets)
        planner?.mergeRemote(remote.planner)
    }

    private fun done() {
        _status.value = "Synchronisiert " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date())
    }

    private fun save(a: HubAccount) {
        _account.value = a
        store.write(FILE, json.encodeToString(HubAccount.serializer(), a))
    }

    /** Posts a message through the hub's Discord bot (if one is configured). */
    fun notify(text: String, level: String = "info") {
        val acc = _account.value ?: return
        scope.launch { runCatching { client(acc).notify(acc.token, text, level) } }
    }

    fun storeClient(): HubClient? = _account.value?.let { client(it) }
    fun token(): String? = _account.value?.token

    companion object {
        private const val FILE = "hub_account.json"
        private const val KNOWN = "hub_known.json"
        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
        private fun hexToBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
