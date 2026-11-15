package app.devdeck.ui.files

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.fs.RemoteEntry
import app.devdeck.core.model.Connection
import app.devdeck.ui.Dest
import app.devdeck.ui.LocalWideLayout
import app.devdeck.ui.common.containerViewModel
import app.devdeck.ui.theme.MonoFamily

@Composable
fun FilesScreen(navigate: (Dest) -> Unit) {
    val vm = containerViewModel { FilesViewModel(it) }
    val state by vm.state.collectAsState()
    val connections by vm.connections.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val wide = LocalWideLayout.current

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.messageShown() }
    }

    val browsable = connections.ssh + connections.nextcloud
    Box(Modifier.fillMaxSize()) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(340.dp).fillMaxHeight()) { Browser(vm, state, browsable, navigate) }
                VerticalDivider()
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    state.file?.let { Editor(vm, it) } ?: Text(
                        "Datei links auswählen", Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            val file = state.file
            if (file != null) {
                BackHandler { vm.closeFile() }
                Editor(vm, file)
            } else {
                BackHandler(enabled = state.connection != null && state.path != "/") { vm.up() }
                Browser(vm, state, browsable, navigate)
            }
        }
        SnackbarHost(snackbar, Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun Browser(vm: FilesViewModel, state: FilesState, connections: List<Connection>, navigate: (Dest) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    var createDialog by remember { mutableStateOf<Boolean?>(null) } // true = folder
    var deleteEntry by remember { mutableStateOf<RemoteEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Box {
                    TextButton(onClick = { picker = true }) {
                        Text(state.connection?.name ?: "Verbindung wählen", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(picker, onDismissRequest = { picker = false }) {
                        if (connections.isEmpty()) DropdownMenuItem(text = { Text("Verbindung anlegen …") }, onClick = {
                            picker = false; navigate(Dest.CONNECTIONS)
                        })
                        connections.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.name} · ${c.kind.label}") }, onClick = { picker = false; vm.select(c) })
                        }
                    }
                }
            },
            actions = {
                if (state.connection != null) {
                    IconButton(onClick = { createDialog = false }) { Icon(Icons.Filled.NoteAdd, "Neue Datei") }
                    IconButton(onClick = { createDialog = true }) { Icon(Icons.Filled.CreateNewFolder, "Neuer Ordner") }
                    IconButton(onClick = vm::refresh) { Icon(Icons.Filled.Refresh, "Aktualisieren") }
                }
            },
        )
        if (state.connection != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                IconButton(onClick = vm::up, enabled = state.path != "/") { Icon(Icons.Filled.ArrowUpward, "Hoch") }
                Text(
                    state.path, fontFamily = MonoFamily, fontSize = 12.sp, maxLines = 1,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.weight(1f)) {
            items(state.entries, key = { it.path }) { e ->
                val size = e.size
                val sub: (@Composable () -> Unit)? = if (!e.isDirectory && size != null) {
                    { Text(humanSize(size)) }
                } else null
                ListItem(
                    headlineContent = { Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = sub,
                    leadingContent = {
                        Icon(if (e.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null)
                    },
                    modifier = Modifier.combinedClickable(onClick = { vm.openFile(e) }, onLongClick = { deleteEntry = e }),
                )
            }
        }
    }

    createDialog?.let { folder ->
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDialog = null },
            title = { Text(if (folder) "Neuer Ordner" else "Neue Datei") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.create(name, folder); createDialog = null }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { createDialog = null }) { Text("Abbrechen") } },
        )
    }
    deleteEntry?.let { e ->
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text("Löschen?") },
            text = { Text("${e.path} wird endgültig gelöscht.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); deleteEntry = null }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Editor(vm: FilesViewModel, file: OpenFile) {
    var confirmClose by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = { if (file.dirty) confirmClose = true else vm.closeFile() }) {
                    Icon(if (LocalWideLayout.current) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack, "Schließen")
                }
            },
            title = {
                Column {
                    Text((if (file.dirty) "● " else "") + file.path.substringAfterLast('/'), maxLines = 1)
                    Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.StartEllipsis)
                }
            },
            actions = {
                if (file.saving) CircularProgressIndicator(Modifier.padding(12.dp).width(20.dp))
                else IconButton(onClick = vm::save, enabled = file.dirty) { Icon(Icons.Filled.Save, "Speichern") }
            },
        )
        HorizontalDivider()
        val lineCount = remember(file.text) { file.text.count { it == '\n' } + 1 }
        val style = TextStyle(fontFamily = MonoFamily, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(
                (1..lineCount).joinToString("\n"),
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(start = 6.dp, end = 8.dp, top = 8.dp),
            )
            BasicTextField(
                value = file.text,
                onValueChange = vm::edit,
                textStyle = style,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(top = 8.dp, end = 8.dp, bottom = 48.dp),
            )
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Ungespeicherte Änderungen") },
            text = { Text("Änderungen verwerfen?") },
            confirmButton = { TextButton(onClick = { confirmClose = false; vm.closeFile() }) { Text("Verwerfen") } },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Weiter bearbeiten") } },
        )
    }
}

private fun humanSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.1f MB".format(b / 1024.0 / 1024.0)
}
