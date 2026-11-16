package app.devdeck.data

import app.devdeck.core.claudecode.LOGIN_STATUS_SCRIPT
import app.devdeck.core.claudecode.toolStatus
import app.devdeck.core.model.ConnectionStore
import app.devdeck.core.model.newId
import app.devdeck.core.ssh.SshManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Widget types. [needsServer] = runs something on an SSH server. */
enum class WidgetKind(val label: String, val needsServer: Boolean, val description: String) {
    STATUS("Server-Status", true, "Last, RAM, Festplatte und Laufzeit"),
    COMMAND("Befehlsausgabe", true, "Zeigt die Ausgabe eines Befehls, regelmäßig aktualisiert"),
    BUTTON("Aktions-Knopf", true, "Führt per Tippen einen Befehl aus"),
    AI("KI-Schnellfrage", false, "Öffnet den Chat mit einer vorbereiteten Aufgabe"),
    LOGINS("Anmeldestatus", true, "Welche KI-Werkzeuge auf dem Server angemeldet sind"),
}

@Serializable
data class WidgetDef(
    val id: String = newId(),
    val title: String,
    val kind: WidgetKind,
    val connectionId: String? = null,
    val command: String = "",
    val prompt: String = "",
    val refreshMinutes: Int = 30,
    val color: Int = 0,
    /** Set when installed from the store. */
    val storeId: String? = null,
)

@Serializable
data class WidgetResult(val text: String, val ok: Boolean, val at: Long)

const val STATUS_COMMAND =
    "echo \"Last: $(cut -d' ' -f1-3 /proc/loadavg)\"; " +
        "free -m | awk '/Mem:/{printf \"RAM: %d / %d MB\\n\",$3,$2}'; " +
        "df -h / | awk 'NR==2{print \"Disk: \"$3\" / \"$2\" (\"$5\")\"}'; " +
        "echo \"Up: $(uptime -p 2>/dev/null | sed 's/^up //' || uptime)\""

class WidgetRepository(private val store: SecureStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(WidgetDef.serializer())
    private val resSer = MapSerializer(String.serializer(), WidgetResult.serializer())

    private val _widgets = MutableStateFlow(store.read(FILE)?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() } ?: emptyList())
    val widgets: StateFlow<List<WidgetDef>> = _widgets.asStateFlow()

    private val _results = MutableStateFlow(store.read(RESULTS)?.let { runCatching { json.decodeFromString(resSer, it) }.getOrNull() } ?: emptyMap())
    val results: StateFlow<Map<String, WidgetResult>> = _results.asStateFlow()

    fun save(w: WidgetDef) = mutate { list -> if (list.any { it.id == w.id }) list.map { if (it.id == w.id) w else it } else list + w }
    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }
    fun move(id: String, delta: Int) = mutate { list ->
        val i = list.indexOfFirst { it.id == id }; val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i < 0 || i == j) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }
    fun get(id: String) = _widgets.value.firstOrNull { it.id == id }

    private fun mutate(f: (List<WidgetDef>) -> List<WidgetDef>) {
        _widgets.update(f)
        store.write(FILE, json.encodeToString(listSer, _widgets.value))
    }

    fun setResult(id: String, r: WidgetResult) {
        _results.update { it + (id to r) }
        store.write(RESULTS, json.encodeToString(resSer, _results.value))
    }

    // ---- sync (hub vault) ----
    fun toJsonArray(): JsonArray = json.encodeToJsonElement(listSer, _widgets.value) as JsonArray
    fun applyJsonArray(arr: JsonArray) {
        val remote = runCatching { json.decodeFromJsonElement(listSer, arr) }.getOrNull() ?: return
        mutate { local -> remote + local.filter { l -> remote.none { it.id == l.id } } }
    }

    // ---- store (hub) ----
    /** Shareable definition: no ids, no server binding. */
    fun storeDefinition(w: WidgetDef): JsonElement = json.encodeToJsonElement(WidgetDef.serializer(),
        w.copy(id = "", connectionId = null, storeId = null)).jsonObject.let { JsonObject(it - "id" - "connectionId" - "storeId") }

    fun fromStore(definition: JsonElement, storeId: String, connectionId: String?): WidgetDef? =
        runCatching { json.decodeFromJsonElement(WidgetDef.serializer(), definition) }.getOrNull()
            ?.copy(id = newId(), connectionId = connectionId, storeId = storeId)

    companion object {
        private const val FILE = "widgets.json"
        private const val RESULTS = "widget_results.json"
    }
}

/** Evaluates widgets (runs their command on the server). */
class WidgetRunner(private val ssh: SshManager, private val connections: () -> ConnectionStore, private val repo: WidgetRepository) {
    suspend fun run(w: WidgetDef): WidgetResult {
        val result = try {
            when (w.kind) {
                WidgetKind.AI -> WidgetResult(w.prompt.take(200), true, System.currentTimeMillis())
                else -> {
                    val conn = connections().ssh.firstOrNull { it.id == w.connectionId }
                        ?: return WidgetResult("Kein Server gewählt", false, System.currentTimeMillis()).also { repo.setResult(w.id, it) }
                    val cmd = when (w.kind) {
                        WidgetKind.STATUS -> STATUS_COMMAND
                        WidgetKind.LOGINS -> LOGIN_STATUS_SCRIPT
                        else -> w.command
                    }
                    val r = ssh.exec(conn, cmd, timeoutSeconds = 45, maxOutputChars = 4000)
                    val text = when (w.kind) {
                        WidgetKind.LOGINS -> toolStatus(r.stdout).filter { it.installed }
                            .joinToString("\n") { "${if (it.loggedIn) "✓" else "✗"} ${it.label}" }.ifEmpty { "Keine KI-Werkzeuge installiert" }
                        else -> (r.stdout.ifBlank { r.stderr }).trim().lines().takeLast(12).joinToString("\n").ifBlank { "(keine Ausgabe)" }
                    }
                    WidgetResult(text, r.exitCode == 0 && !r.timedOut, System.currentTimeMillis())
                }
            }
        } catch (e: Exception) {
            WidgetResult("Fehler: ${e.message ?: e.javaClass.simpleName}", false, System.currentTimeMillis())
        }
        repo.setResult(w.id, result)
        return result
    }
}
