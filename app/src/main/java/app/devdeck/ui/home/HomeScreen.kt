package app.devdeck.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import app.devdeck.AppContainer
import app.devdeck.core.hub.StoreWidget
import app.devdeck.data.WidgetDef
import app.devdeck.data.WidgetKind
import app.devdeck.data.WidgetResult
import app.devdeck.ui.Dest
import app.devdeck.ui.appContainer
import app.devdeck.ui.theme.Brand
import app.devdeck.ui.theme.MonoFamily
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val WidgetColors = listOf(Brand.Blue, Brand.Green, Brand.Pink, Color(0xFFF2A93B), Color(0xFF8E7CFF))

/** Start page: the user's widgets, editor and widget store. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navigate: (Dest) -> Unit) {
    val c = appContainer()
    val widgets by c.widgets.widgets.collectAsState()
    val results by c.widgets.results.collectAsState()
    val scope = rememberCoroutineScope()
    val running = remember { mutableStateMapOf<String, Boolean>() }
    var editing by remember { mutableStateOf<WidgetDef?>(null) }
    var storeOpen by remember { mutableStateOf(false) }
    var confirmRun by remember { mutableStateOf<WidgetDef?>(null) }

    fun refresh(w: WidgetDef) {
        if (w.kind == WidgetKind.AI || running[w.id] == true) return
        running[w.id] = true
        scope.launch { c.widgetRunner.run(w); running[w.id] = false; app.devdeck.widget.HomeWidgets.updateAll(c.appContext) }
    }

    LaunchedEffect(widgets.map { it.id }) {
        widgets.filter { it.kind != WidgetKind.BUTTON && it.kind != WidgetKind.AI }
            .filter { w -> results[w.id]?.let { System.currentTimeMillis() - it.at > 60_000 } ?: true }
            .forEach { refresh(it) }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Start") },
                actions = {
                    IconButton(onClick = { navigate(Dest.BROWSER) }) { Icon(Icons.Filled.Public, "Browser") }
                    IconButton(onClick = { storeOpen = true }) { Icon(Icons.Filled.Storefront, "Widget-Store") }
                    IconButton(onClick = { widgets.forEach { refresh(it) } }) { Icon(Icons.Filled.Refresh, "Alle aktualisieren") }
                },
            )
            if (widgets.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Noch keine Widgets", style = MaterialTheme.typography.titleMedium)
                    Text("Widgets zeigen dir Server-Zustand, Befehlsausgaben oder Schnellaktionen auf einen Blick – hier und " +
                        "auf dem Android-Startbildschirm (lange auf den Startbildschirm tippen → Widgets → Humantize).",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { editing = WidgetDef(title = "Server-Status", kind = WidgetKind.STATUS) }) { Text("Widget erstellen") }
                        OutlinedButton(onClick = { storeOpen = true }) { Text("Store öffnen") }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(widgets, key = { it.id }) { w ->
                    WidgetCard(w, results[w.id], running[w.id] == true,
                        onRefresh = { refresh(w) },
                        onRun = { confirmRun = w },
                        onAsk = { if (w.kind.planner) navigate(if (w.kind.life) Dest.LIFE else Dest.PLANNER) else { c.pendingPrompt.value = w.prompt; navigate(Dest.AGENT) } },
                        onEdit = { editing = w })
                }
            }
        }
        FloatingActionButton(
            onClick = { editing = WidgetDef(title = "", kind = WidgetKind.COMMAND) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Filled.Add, "Widget hinzufügen") }
    }

    editing?.let { w -> WidgetEditor(c, w, isNew = widgets.none { it.id == w.id }, onDismiss = { editing = null }) { saved -> refresh(saved) } }
    if (storeOpen) WidgetStore(c) { storeOpen = false }
    confirmRun?.let { w ->
        AlertDialog(
            onDismissRequest = { confirmRun = null },
            title = { Text("${w.title} ausführen?") },
            text = { Text(w.command, fontFamily = MonoFamily, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { confirmRun = null; refresh(w) }) { Text("Ausführen") } },
            dismissButton = { TextButton(onClick = { confirmRun = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetCard(
    w: WidgetDef, r: WidgetResult?, busy: Boolean,
    onRefresh: () -> Unit, onRun: () -> Unit, onAsk: () -> Unit, onEdit: () -> Unit,
) {
    val accent = WidgetColors[w.color.mod(WidgetColors.size)]
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp).combinedClickable(
            onClick = { when { w.kind == WidgetKind.BUTTON -> onRun(); w.kind == WidgetKind.AI || w.kind.planner -> onAsk(); else -> onRefresh() } },
            onLongClick = onEdit,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (r != null && !r.ok) MaterialTheme.colorScheme.error else accent))
                Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.size(8.dp))
            when (w.kind) {
                WidgetKind.AI -> Text(w.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                WidgetKind.BUTTON -> Text(r?.text ?: "Tippen zum Ausführen", fontFamily = MonoFamily, fontSize = 11.sp, maxLines = 5)
                else -> Text(r?.text ?: "Lädt …", fontFamily = if (w.kind.planner) null else MonoFamily, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 6,
                    overflow = TextOverflow.Ellipsis)
            }
            r?.let {
                Text(SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(it.at)), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetEditor(c: AppContainer, initial: WidgetDef, isNew: Boolean, onDismiss: () -> Unit, onSaved: (WidgetDef) -> Unit) {
    var w by remember { mutableStateOf(initial) }
    var serverMenu by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val servers = c.connections.state.collectAsState().value.ssh
    val loggedIn = c.hub.account.collectAsState().value != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Widget erstellen" else "Widget bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(w.title, { w = w.copy(title = it) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                listOf(
                    "Server & KI" to WidgetKind.entries.filter { !it.planner },
                    "Planer" to WidgetKind.entries.filter { it.planner && !it.life },
                    "Alltag" to WidgetKind.entries.filter { it.life },
                ).forEach { (group, kinds) ->
                    Text(group, style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kinds.forEach { k -> FilterChip(selected = w.kind == k, onClick = {
                            w = w.copy(kind = k, title = if (w.title.isBlank() || WidgetKind.entries.any { it.label == w.title }) k.label else w.title)
                        }, label = { Text(k.label) }) }
                    }
                }
                Text(w.kind.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (w.kind.needsServer) Box {
                    OutlinedButton(onClick = { serverMenu = true }) {
                        Text("Server: " + (servers.firstOrNull { it.id == w.connectionId }?.name ?: "wählen"))
                    }
                    DropdownMenu(serverMenu, onDismissRequest = { serverMenu = false }) {
                        servers.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { serverMenu = false; w = w.copy(connectionId = s.id) }) }
                    }
                }
                if (w.kind == WidgetKind.COMMAND || w.kind == WidgetKind.BUTTON) {
                    OutlinedTextField(w.command, { w = w.copy(command = it) }, label = { Text("Befehl") },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily), modifier = Modifier.fillMaxWidth())
                }
                if (w.kind == WidgetKind.NOTE) {
                    val notes = c.planner.data.collectAsState().value.activeNotes()
                    var noteMenu by remember { mutableStateOf(false) }
                    if (notes.isEmpty()) Text("Lege zuerst im Planer unter „Notizen“ eine Notiz an.", style = MaterialTheme.typography.bodySmall)
                    else Box {
                        OutlinedButton(onClick = { noteMenu = true }) {
                            Text("Notiz: " + (notes.firstOrNull { it.id == w.refId }?.let { it.title.ifBlank { it.text.take(20) } } ?: "wählen"))
                        }
                        DropdownMenu(noteMenu, onDismissRequest = { noteMenu = false }) {
                            notes.forEach { n -> DropdownMenuItem(text = { Text(n.title.ifBlank { n.text.take(30) }) },
                                onClick = { noteMenu = false; w = w.copy(refId = n.id, title = w.title.ifBlank { n.title }) }) }
                        }
                    }
                }
                if (w.kind == WidgetKind.AI) {
                    OutlinedTextField(w.prompt, { w = w.copy(prompt = it) }, label = { Text("Aufgabe für die KI") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
                if (w.kind == WidgetKind.STATUS || w.kind == WidgetKind.COMMAND || w.kind == WidgetKind.LOGINS) {
                    Text("Aktualisieren alle", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 60, 180).forEach { m ->
                            FilterChip(selected = w.refreshMinutes == m, onClick = { w = w.copy(refreshMinutes = m) },
                                label = { Text(if (m < 60) "$m min" else "${m / 60} h") })
                        }
                    }
                }
                Text("Farbe", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WidgetColors.forEachIndexed { i, col ->
                        Box(Modifier.size(if (w.color == i) 30.dp else 24.dp).clip(CircleShape).background(col)
                            .clickable { w = w.copy(color = i) })
                    }
                }
                if (!isNew && loggedIn) {
                    TextButton(enabled = !publishing, onClick = {
                        publishing = true
                        scope.launch {
                            message = try {
                                c.hub.storeClient()!!.publishWidget(c.hub.token()!!, w.title, w.kind.description, w.kind.name, c.widgets.storeDefinition(w))
                                "Im Store veröffentlicht."
                            } catch (e: Exception) { "Veröffentlichen fehlgeschlagen: ${e.message}" }
                            publishing = false
                        }
                    }) { Text("Im Widget-Store veröffentlichen") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val valid = w.title.isNotBlank() && (!w.kind.needsServer || w.connectionId != null) &&
                (w.kind != WidgetKind.COMMAND && w.kind != WidgetKind.BUTTON || w.command.isNotBlank()) &&
                (w.kind != WidgetKind.AI || w.prompt.isNotBlank()) && (w.kind != WidgetKind.NOTE || w.refId != null)
            TextButton(enabled = valid, onClick = { c.widgets.save(w); onDismiss(); onSaved(w) }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { c.widgets.delete(w.id); onDismiss() }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

/** Browse and install widgets other users published on the hub. Commands are shown before installing. */
@Composable
private fun WidgetStore(c: AppContainer, onDismiss: () -> Unit) {
    val client = c.hub.storeClient()
    var query by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<StoreWidget>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<StoreWidget?>(null) }
    val scope = rememberCoroutineScope()
    val servers = c.connections.state.collectAsState().value.ssh

    fun load() {
        if (client == null) return
        scope.launch {
            try { items = client.listWidgets(query); error = null } catch (e: Exception) { error = e.message }
        }
    }
    LaunchedEffect(Unit) { load() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Widget-Store") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (client == null) {
                    Text("Der Store läuft auf deinem Humantize Hub. Melde dich unter Einstellungen → Konto & Sync an.")
                } else {
                    OutlinedTextField(query, { query = it; load() }, label = { Text("Suchen") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val list = items
                    if (list == null) CircularProgressIndicator(Modifier.size(24.dp))
                    else if (list.isEmpty()) Text("Noch keine Widgets im Store. Veröffentliche eigene über „Widget bearbeiten“.")
                    else LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(list, key = { it.id }) { sw ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(sw.name, style = MaterialTheme.typography.titleSmall)
                                    Text("${sw.description}\nvon ${sw.author} · ${sw.installs}× installiert",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = { installing = sw }) { Text("Ansehen & installieren") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )

    installing?.let { sw ->
        val def = c.widgets.fromStore(sw.definition, sw.id, null)
        var server by remember { mutableStateOf(servers.firstOrNull()?.id) }
        var menu by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { installing = null },
            title = { Text("„${sw.name}“ installieren?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (def == null) Text("Dieses Widget wird von deiner App-Version nicht unterstützt.")
                    else {
                        Text("Typ: ${def.kind.label}")
                        if (def.command.isNotBlank()) {
                            Text("⚠ Dieses Widget führt folgenden Befehl auf deinem Server aus. Nur installieren, wenn du ihn verstehst:",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            Text(def.command, fontFamily = MonoFamily, fontSize = 12.sp)
                        }
                        if (def.prompt.isNotBlank()) Text("KI-Aufgabe: ${def.prompt}", style = MaterialTheme.typography.bodySmall)
                        if (def.kind.needsServer) Box {
                            OutlinedButton(onClick = { menu = true }) { Text("Server: " + (servers.firstOrNull { it.id == server }?.name ?: "wählen")) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                servers.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { menu = false; server = s.id }) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = def != null && (!def.kind.needsServer || server != null), onClick = {
                    c.widgets.save(def!!.copy(connectionId = server))
                    scope.launch { runCatching { client?.markInstalled(sw.id) } }
                    installing = null
                }) { Text("Installieren") }
            },
            dismissButton = { TextButton(onClick = { installing = null }) { Text("Abbrechen") } },
        )
    }
}
