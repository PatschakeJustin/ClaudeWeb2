package app.devdeck.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.devdeck.core.model.ConnectionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Keeps a copy of all connections in a text file chosen by the user (e.g. in Downloads), so the servers
 * survive uninstalling the app. The file is rewritten after every change.
 */
class ServerBackup(
    private val context: Context,
    private val connections: ConnectionsRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    init {
        scope.launch {
            connections.state.drop(1).collect { store -> settings.state.value.backupUri?.let { write(Uri.parse(it), store) } }
        }
    }

    /** Called after the user picked a target file: remember it (with permanent access) and write now. */
    fun enable(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        settings.update { it.copy(backupUri = uri.toString()) }
        scope.launch { write(uri, connections.state.value) }
    }

    fun disable() = settings.update { it.copy(backupUri = null) }

    private fun write(uri: Uri, store: ConnectionStore) {
        try {
            context.contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                out.write(HEADER.toByteArray())
                out.write(json.encodeToString(ConnectionStore.serializer(), store).toByteArray())
                out.write("\n".toByteArray())
            }
            _lastError.value = null
        } catch (e: Exception) {
            _lastError.value = "Sicherungsdatei konnte nicht geschrieben werden: ${e.message}"
        }
    }

    /** Reads a backup file and merges its servers into the app. Returns the number of connections imported. */
    fun import(uri: Uri): Int {
        val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val body = text.lines().dropWhile { it.startsWith("#") }.joinToString("\n")
        val store = json.decodeFromString(ConnectionStore.serializer(), body)
        store.all.forEach { connections.save(it) }
        return store.all.size
    }

    companion object {
        const val FILE_NAME = "humantize-server.txt"
        private const val HEADER =
            "# Humantize DevApp Mobile – Server-Sicherung\n" +
                "# ACHTUNG: enthält Zugangsdaten (Passwörter/Schlüssel) im Klartext. Sicher aufbewahren!\n" +
                "# Wiederherstellen: App → Verbindungen → ⋮ → „Server aus Datei laden“\n"
    }
}
