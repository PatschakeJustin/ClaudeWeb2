package app.devdeck.core.agent

import app.devdeck.core.fs.RemoteFs
import app.devdeck.core.fs.looksBinary
import app.devdeck.core.model.AiServerConnection
import app.devdeck.core.model.Connection
import app.devdeck.core.model.ConnectionStore
import app.devdeck.core.model.NextcloudConnection
import app.devdeck.core.model.RdpConnection
import app.devdeck.core.model.SshConnection
import app.devdeck.core.nextcloud.WebDavFs
import app.devdeck.core.ssh.SshManager
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient

data class ToolOutcome(val text: String, val isError: Boolean = false)

class ToolInputException(message: String) : Exception(message)

/**
 * The tools the Claude agent can call: commands over SSH and file access over SFTP / Nextcloud.
 * Every tool takes the connection *name* the user gave in the app.
 */
class AgentToolbox(
    private val connections: () -> ConnectionStore,
    private val ssh: SshManager,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private class Spec(
        val name: String,
        val description: String,
        val properties: Map<String, Map<String, Any>>,
        val required: List<String>,
        val mutating: Boolean,
    )

    private val conn = mapOf("type" to "string", "description" to "Name der Verbindung (siehe list_connections).")
    private val path = mapOf("type" to "string", "description" to "Absoluter Pfad. Bei Nextcloud relativ zum Nutzer-Root, z. B. /Projekte/app/main.py.")

    private val specs = listOf(
        Spec(
            "list_connections",
            "Listet die in der App eingerichteten Verbindungen (SSH-Server, Nextcloud, RDP) mit Name und Typ. " +
                "Rufe das zuerst auf, wenn du nicht weißt, welche Verbindung gemeint ist.",
            emptyMap(), emptyList(), mutating = false,
        ),
        Spec(
            "run_command",
            "Führt einen Shell-Befehl nicht-interaktiv auf einem SSH-Server aus und gibt stdout, stderr und Exit-Code zurück. " +
                "Keine interaktiven Programme (vim, top, less) starten. Lange Ausgaben werden gekürzt.",
            mapOf(
                "connection" to conn,
                "command" to mapOf("type" to "string", "description" to "Der auszuführende Befehl (bash)."),
                "working_directory" to mapOf("type" to "string", "description" to "Optional: Verzeichnis, in dem der Befehl läuft."),
                "timeout_seconds" to mapOf("type" to "integer", "description" to "Optional, Standard 120, maximal 1800."),
            ),
            listOf("connection", "command"), mutating = true,
        ),
        Spec(
            "list_directory",
            "Listet den Inhalt eines Verzeichnisses (SSH/SFTP oder Nextcloud).",
            mapOf("connection" to conn, "path" to path), listOf("connection", "path"), mutating = false,
        ),
        Spec(
            "read_file",
            "Liest eine Textdatei (SSH/SFTP oder Nextcloud). Gibt den Inhalt mit Zeilennummern zurück.",
            mapOf(
                "connection" to conn, "path" to path,
                "start_line" to mapOf("type" to "integer", "description" to "Optional: erste Zeile (1-basiert)."),
                "max_lines" to mapOf("type" to "integer", "description" to "Optional: maximale Anzahl Zeilen, Standard 2000."),
            ),
            listOf("connection", "path"), mutating = false,
        ),
        Spec(
            "write_file",
            "Schreibt (erstellt oder überschreibt) eine Datei mit dem kompletten Inhalt. Fehlende Verzeichnisse werden angelegt. " +
                "Für kleine Änderungen an bestehenden Dateien edit_file bevorzugen.",
            mapOf(
                "connection" to conn, "path" to path,
                "content" to mapOf("type" to "string", "description" to "Kompletter neuer Dateiinhalt."),
            ),
            listOf("connection", "path", "content"), mutating = true,
        ),
        Spec(
            "edit_file",
            "Ersetzt in einer Datei den exakten Text old_string durch new_string. old_string muss genau einmal vorkommen " +
                "(außer replace_all ist true). Vorher die Datei mit read_file lesen.",
            mapOf(
                "connection" to conn, "path" to path,
                "old_string" to mapOf("type" to "string", "description" to "Exakter zu ersetzender Text (ohne Zeilennummern)."),
                "new_string" to mapOf("type" to "string", "description" to "Neuer Text."),
                "replace_all" to mapOf("type" to "boolean", "description" to "Optional: alle Vorkommen ersetzen."),
            ),
            listOf("connection", "path", "old_string", "new_string"), mutating = true,
        ),
    )

    private val byName = specs.associateBy { it.name }

    val definitions: List<Tool> = specs.map { s ->
        Tool.builder()
            .name(s.name)
            .description(s.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder().apply {
                            s.properties.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(v)) }
                        }.build()
                    )
                    .required(s.required)
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            // Large file contents stream as they are generated; inputs are validated in [validate].
            .eagerInputStreaming(true)
            .build()
    }

    fun isKnown(name: String) = name in byName
    fun requiresApproval(name: String) = byName[name]?.mutating ?: true

    /** Checks the (possibly truncated) tool input against the schema before anything runs. */
    fun validate(name: String, input: JsonObject) {
        val spec = byName[name] ?: throw ToolInputException("Unbekanntes Werkzeug: $name")
        for (req in spec.required) {
            val v = input[req] ?: throw ToolInputException("Pflichtfeld '$req' fehlt.")
            if (v !is JsonPrimitive || !v.isString) throw ToolInputException("Feld '$req' muss ein String sein.")
        }
        for ((key, value) in input) {
            val type = spec.properties[key]?.get("type") ?: throw ToolInputException("Unbekanntes Feld '$key'.")
            val ok = value is JsonPrimitive && when (type) {
                "string" -> value.isString
                "integer" -> !value.isString && value.longOrNull != null
                "boolean" -> !value.isString && value.booleanOrNull != null
                else -> true
            }
            if (!ok) throw ToolInputException("Feld '$key' muss vom Typ $type sein.")
        }
    }

    /** Short human-readable summary for the approval dialog and the chat log. */
    fun describe(name: String, input: JsonObject): String {
        fun s(k: String) = input.str(k) ?: ""
        return when (name) {
            "run_command" -> "${s("connection")}: ${input.str("working_directory")?.let { "(in $it) " } ?: ""}${s("command")}"
            "write_file" -> "${s("connection")}: ${s("path")} schreiben (${s("content").length} Zeichen)"
            "edit_file" -> "${s("connection")}: ${s("path")} bearbeiten"
            "read_file", "list_directory" -> "${s("connection")}: ${s("path")}"
            else -> name
        }
    }

    suspend fun execute(name: String, input: JsonObject): ToolOutcome = try {
        validate(name, input)
        when (name) {
            "list_connections" -> ToolOutcome(listConnections())
            "run_command" -> runCommand(input)
            "list_directory" -> listDirectory(input)
            "read_file" -> readFile(input)
            "write_file" -> writeFile(input)
            "edit_file" -> editFile(input)
            else -> ToolOutcome("Unbekanntes Werkzeug: $name", isError = true)
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome("Fehler: ${e.message ?: e.javaClass.simpleName}", isError = true)
    }

    private fun listConnections(): String {
        val all = connections().all
        if (all.isEmpty()) return "Es sind keine Verbindungen eingerichtet. Der Nutzer kann sie im Tab 'Verbindungen' anlegen."
        return all.joinToString("\n") { c ->
            when (c) {
                is SshConnection -> "- ${c.name} [SSH] ${c.username}@${c.host}:${c.port}" +
                    (c.defaultDirectory?.let { ", Startverzeichnis $it" } ?: "") + " – Befehle und Dateien"
                is NextcloudConnection -> "- ${c.name} [Nextcloud] ${c.baseUrl} (Nutzer ${c.username}) – nur Dateien"
                is RdpConnection -> "- ${c.name} [RDP] ${c.host}:${c.port} – nur für den Nutzer per Remote-Desktop-App, für dich nicht nutzbar"
                is AiServerConnection -> "- ${c.name} [KI-Server] Modell ${c.model} – ein Sprachmodell, kein Dateizugriff"
            }
        }
    }

    private fun resolve(input: JsonObject): Connection {
        val key = input.str("connection")!!
        return connections().find(key)
            ?: throw ToolInputException("Verbindung '$key' nicht gefunden. Verfügbar: " + connections().all.joinToString { it.name })
    }

    private fun fsFor(c: Connection): RemoteFs = when (c) {
        is SshConnection -> ssh.fs(c)
        is NextcloudConnection -> WebDavFs(c, http)
        is RdpConnection -> throw ToolInputException("'${c.name}' ist eine RDP-Verbindung; darauf hast du keinen Dateizugriff.")
        is AiServerConnection -> throw ToolInputException("'${c.name}' ist ein KI-Server; darauf hast du keinen Dateizugriff.")
    }

    private suspend fun runCommand(input: JsonObject): ToolOutcome {
        val c = resolve(input) as? SshConnection
            ?: throw ToolInputException("Befehle gehen nur über SSH-Verbindungen.")
        val dir = input.str("working_directory")?.takeIf { it.isNotBlank() }
        val command = input.str("command")!!
        val full = if (dir != null) "cd ${shellQuote(dir)} && $command" else command
        val timeout = (input["timeout_seconds"] as? JsonPrimitive)?.longOrNull?.coerceIn(1, 1800) ?: 120
        val r = ssh.exec(c, full, timeout)
        val text = buildString {
            append("Exit-Code: ${r.exitCode ?: "unbekannt"}")
            if (r.timedOut) append(" (Zeitlimit von ${timeout}s erreicht, Befehl abgebrochen)")
            append("\n")
            if (r.stdout.isNotEmpty()) append("--- stdout ---\n").append(r.stdout).append("\n")
            if (r.stderr.isNotEmpty()) append("--- stderr ---\n").append(r.stderr).append("\n")
            if (r.stdout.isEmpty() && r.stderr.isEmpty()) append("(keine Ausgabe)")
        }
        return ToolOutcome(text, isError = r.timedOut || (r.exitCode ?: 0) != 0)
    }

    private suspend fun listDirectory(input: JsonObject): ToolOutcome {
        val entries = fsFor(resolve(input)).list(input.str("path")!!)
        if (entries.isEmpty()) return ToolOutcome("(leer)")
        return ToolOutcome(entries.take(1000).joinToString("\n") { e ->
            if (e.isDirectory) "${e.name}/" else "${e.name}  (${e.size ?: "?"} B)"
        } + if (entries.size > 1000) "\n[… ${entries.size - 1000} weitere Einträge]" else "")
    }

    private suspend fun readFile(input: JsonObject): ToolOutcome {
        val p = input.str("path")!!
        val bytes = fsFor(resolve(input)).read(p)
        if (looksBinary(bytes)) return ToolOutcome("$p ist eine Binärdatei (${bytes.size} Bytes) und wird nicht angezeigt.", isError = true)
        val lines = bytes.toString(Charsets.UTF_8).split("\n")
        val start = ((input["start_line"] as? JsonPrimitive)?.longOrNull ?: 1).toInt().coerceAtLeast(1)
        val max = ((input["max_lines"] as? JsonPrimitive)?.longOrNull ?: 2000).toInt().coerceIn(1, 5000)
        val slice = lines.drop(start - 1).take(max)
        val body = slice.mapIndexed { i, l -> "${(start + i).toString().padStart(6)}\t${l.take(2000)}" }.joinToString("\n")
        val more = lines.size - (start - 1 + slice.size)
        return ToolOutcome(if (more > 0) "$body\n[… $more weitere Zeilen, mit start_line weiterlesen]" else body)
    }

    private suspend fun writeFile(input: JsonObject): ToolOutcome {
        val p = input.str("path")!!
        val fs = fsFor(resolve(input))
        val parent = p.trimEnd('/').substringBeforeLast('/', "")
        if (parent.isNotEmpty()) runCatching { fs.mkdirs(parent) }
        val content = input.str("content")!!
        fs.write(p, content.toByteArray(Charsets.UTF_8))
        return ToolOutcome("$p geschrieben (${content.length} Zeichen).")
    }

    private suspend fun editFile(input: JsonObject): ToolOutcome {
        val p = input.str("path")!!
        val fs = fsFor(resolve(input))
        val text = fs.read(p).toString(Charsets.UTF_8)
        val old = input.str("old_string")!!
        val new = input.str("new_string")!!
        val all = (input["replace_all"] as? JsonPrimitive)?.booleanOrNull == true
        if (old.isEmpty()) throw ToolInputException("old_string darf nicht leer sein.")
        val count = text.windowedCount(old)
        if (count == 0) throw ToolInputException("old_string kommt in $p nicht vor. Datei erneut mit read_file lesen.")
        if (count > 1 && !all) throw ToolInputException("old_string kommt $count-mal vor. Mehr Kontext angeben oder replace_all setzen.")
        val updated = if (all) text.replace(old, new) else text.replaceFirst(old, new)
        fs.write(p, updated.toByteArray(Charsets.UTF_8))
        return ToolOutcome("$p bearbeitet ($count Ersetzung${if (count == 1) "" else "en"}).")
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

private fun String.windowedCount(needle: String): Int {
    var count = 0
    var idx = indexOf(needle)
    while (idx >= 0) { count++; idx = indexOf(needle, idx + needle.length) }
    return count
}
