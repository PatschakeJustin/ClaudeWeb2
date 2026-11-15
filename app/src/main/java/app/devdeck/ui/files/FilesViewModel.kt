package app.devdeck.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.devdeck.AppContainer
import app.devdeck.core.fs.RemoteEntry
import app.devdeck.core.fs.RemoteFs
import app.devdeck.core.fs.joinPath
import app.devdeck.core.fs.looksBinary
import app.devdeck.core.fs.parentPath
import app.devdeck.core.model.Connection
import app.devdeck.core.model.NextcloudConnection
import app.devdeck.core.model.SshConnection
import app.devdeck.core.nextcloud.WebDavFs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenFile(
    val path: String,
    val original: String,
    val text: String,
    val saving: Boolean = false,
) {
    val dirty get() = text != original
}

data class FilesState(
    val connection: Connection? = null,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val file: OpenFile? = null,
    val message: String? = null,
)

class FilesViewModel(private val container: AppContainer) : ViewModel() {
    val connections = container.connections.state
    private val _state = MutableStateFlow(FilesState())
    val state: StateFlow<FilesState> = _state.asStateFlow()
    private var fs: RemoteFs? = null

    fun select(c: Connection) {
        fs = when (c) {
            is SshConnection -> container.ssh.fs(c)
            is NextcloudConnection -> WebDavFs(c, container.http)
            else -> null
        }
        _state.value = FilesState(connection = c, loading = true)
        viewModelScope.launch {
            val home = runCatching { fs!!.home() }.getOrElse { "/" }
            open(home)
        }
    }

    fun open(path: String) {
        val f = fs ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val entries = f.list(path)
                _state.update { it.copy(path = path, entries = entries, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun up() = open(parentPath(_state.value.path))
    fun refresh() = open(_state.value.path)

    fun openFile(entry: RemoteEntry) {
        if (entry.isDirectory) return open(entry.path)
        val f = fs ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val bytes = f.read(entry.path)
                if (looksBinary(bytes)) {
                    _state.update { it.copy(loading = false, message = "${entry.name} ist eine Binärdatei.") }
                } else {
                    val text = bytes.toString(Charsets.UTF_8)
                    _state.update { it.copy(loading = false, file = OpenFile(entry.path, text, text)) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, message = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun edit(text: String) = _state.update { s -> s.copy(file = s.file?.copy(text = text)) }

    fun closeFile() = _state.update { it.copy(file = null) }

    fun save() {
        val f = fs ?: return
        val file = _state.value.file ?: return
        _state.update { it.copy(file = file.copy(saving = true)) }
        viewModelScope.launch {
            try {
                f.write(file.path, file.text.toByteArray(Charsets.UTF_8))
                _state.update { s -> s.copy(file = s.file?.copy(original = file.text, saving = false), message = "Gespeichert") }
            } catch (e: Exception) {
                _state.update { s -> s.copy(file = s.file?.copy(saving = false), message = "Speichern fehlgeschlagen: ${e.message}") }
            }
        }
    }

    fun create(name: String, directory: Boolean) {
        val f = fs ?: return
        val path = joinPath(_state.value.path, name.trim())
        viewModelScope.launch {
            try {
                if (directory) f.mkdirs(path) else f.write(path, ByteArray(0))
                refresh()
                if (!directory) _state.update { it.copy(file = OpenFile(path, "", "")) }
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message) }
            }
        }
    }

    fun delete(entry: RemoteEntry) {
        val f = fs ?: return
        viewModelScope.launch {
            try { f.delete(entry.path); refresh() } catch (e: Exception) { _state.update { it.copy(message = e.message) } }
        }
    }

    fun messageShown() = _state.update { it.copy(message = null) }
}
