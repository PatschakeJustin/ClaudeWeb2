package app.devdeck.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.claudecode.ClaudeCodePermission
import app.devdeck.core.model.ConnectionStore
import app.devdeck.data.AppSettings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import app.devdeck.ui.Dest
import app.devdeck.R
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import app.devdeck.ui.LocalWideLayout
import app.devdeck.ui.common.MarkdownText
import app.devdeck.ui.common.containerViewModel
import app.devdeck.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(navigate: (Dest) -> Unit) {
    val vm = containerViewModel { AgentViewModel(it) }
    val items by vm.items.collectAsState()
    val running by vm.running.collectAsState()
    val approval by vm.approval.collectAsState()
    val settings by vm.settings.collectAsState()
    val connections by vm.connections.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    // Prompt handed over by a "KI-Schnellfrage" widget.
    val container = app.devdeck.ui.appContainer()
    val pending by container.pendingPrompt.collectAsState()
    LaunchedEffect(pending) { pending?.let { input = it; container.pendingPrompt.value = null } }
    val listState = rememberLazyListState()
    val wide = LocalWideLayout.current

    LaunchedEffect(items.size, (items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex, scrollOffset = Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Claude")
                    val ccServer = connections.ssh.firstOrNull { it.id == settings.ccConnectionId }?.name
                    Text(
                        if (runsOnServer(settings.agentMode)) backendLabel(settings.agentMode, connections) + " · ${ccServer ?: "kein Server"}"
                        else if (settings.agentMode.startsWith(AI_PREFIX)) backendLabel(settings.agentMode, connections) + " · selfhosted"
                        else "API · " + settings.model + " · " + settings.effort,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = vm::newChat) { Icon(Icons.Filled.AddComment, "Neuer Chat") }
            },
        )

        ModeBar(vm, settings, connections, navigate)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                EmptyState(
                    hasKey = settings.agentMode != AppSettings.MODE_API || settings.apiKey.isNotBlank(),
                    connectionNames = connections.ssh.map { it.name } + connections.nextcloud.map { it.name },
                    onSetup = navigate,
                    onPrompt = { input = it },
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().let { if (wide) it.padding(horizontal = 24.dp) else it },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items, key = { it.key }) { item -> ChatRow(item) }
                if (running && items.lastOrNull() !is ChatItem.Assistant) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Claude arbeitet …", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                placeholder = { Text("Aufgabe für Claude …") },
                maxLines = 6,
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            if (running) {
                FilledIconButton(onClick = vm::stop, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Stop, "Stopp") }
            } else {
                FilledIconButton(
                    onClick = { vm.send(input); input = "" },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(52.dp),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Senden") }
            }
        }
    }

    approval?.let { a ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (a.toolName == "run_command") "Befehl ausführen?" else "Datei ändern?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(a.summary, fontFamily = MonoFamily, fontSize = 13.sp)
                    a.detail?.let {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            it, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 14.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .horizontalScroll(rememberScrollState()).padding(8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.answerApproval(true) }) { Text("Erlauben") }
                    TextButton(onClick = { vm.answerApproval(true, always = true) }) { Text("Immer in diesem Chat") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.answerApproval(false) }) { Text("Ablehnen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeBar(vm: AgentViewModel, settings: AppSettings, connections: ConnectionStore, navigate: (Dest) -> Unit) {
    // All backends except the API one run on the selected server.
    val cc = runsOnServer(settings.agentMode)
    var serverMenu by remember { mutableStateOf(false) }
    var permMenu by remember { mutableStateOf(false) }
    var dirDialog by remember { mutableStateOf(false) }
    val server = connections.ssh.firstOrNull { it.id == settings.ccConnectionId }
    val perm = runCatching { ClaudeCodePermission.valueOf(settings.ccPermission) }.getOrDefault(ClaudeCodePermission.EDIT)

    var modelMenu by remember { mutableStateOf(false) }
    var reserveMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            AssistChip(onClick = { modelMenu = true }, label = { Text("Modell: " + backendLabel(settings.agentMode, connections)) })
            DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                backends(connections).forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { modelMenu = false; vm.setMode(id) })
                }
            }
        }
        Box {
            AssistChip(
                onClick = { reserveMenu = true },
                label = { Text("Reserve: " + (settings.reserveMode.takeIf { it.isNotEmpty() }?.let { backendLabel(it, connections) } ?: "keine")) },
            )
            DropdownMenu(reserveMenu, onDismissRequest = { reserveMenu = false }) {
                DropdownMenuItem(text = { Text("Keine Reserve") }, onClick = { reserveMenu = false; vm.setReserve("") })
                backends(connections).filter { it.first != settings.agentMode }.forEach { (id, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { reserveMenu = false; vm.setReserve(id) })
                }
            }
        }
        if (cc) {
            Box {
                AssistChip(onClick = { serverMenu = true }, label = { Text("Server: " + (server?.name ?: "wählen")) })
                DropdownMenu(serverMenu, onDismissRequest = { serverMenu = false }) {
                    connections.ssh.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name) }, onClick = { serverMenu = false; vm.setClaudeCodeServer(c.id) })
                    }
                    DropdownMenuItem(text = { Text("Server einrichten …") }, onClick = { serverMenu = false; navigate(Dest.CONNECTIONS) })
                }
            }
            AssistChip(
                onClick = { dirDialog = true },
                label = { Text("Ordner: " + settings.ccWorkDir.ifBlank { server?.defaultDirectory ?: "~" }, maxLines = 1) },
            )
            Box {
                AssistChip(onClick = { permMenu = true }, label = { Text(perm.label) })
                DropdownMenu(permMenu, onDismissRequest = { permMenu = false }) {
                    ClaudeCodePermission.entries.forEach { p ->
                        DropdownMenuItem(
                            text = { Column { Text(p.label); Text(p.description, style = MaterialTheme.typography.bodySmall) } },
                            onClick = { permMenu = false; vm.setClaudeCodePermission(p) },
                        )
                    }
                }
            }
        }
    }

    if (dirDialog) {
        var dir by remember { mutableStateOf(settings.ccWorkDir) }
        AlertDialog(
            onDismissRequest = { dirDialog = false },
            title = { Text("Arbeitsordner") },
            text = {
                Column {
                    Text("In diesem Ordner auf dem Server arbeitet Claude Code (z. B. dein Projekt). Leer = Startverzeichnis der Verbindung.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(dir, { dir = it }, singleLine = true, placeholder = { Text("/root/projekt") },
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.setClaudeCodeWorkDir(dir); dirDialog = false }) { Text("Übernehmen") } },
            dismissButton = { TextButton(onClick = { dirDialog = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SelectionContainer {
                Text(
                    item.text,
                    modifier = Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer).padding(12.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        is ChatItem.Assistant -> if (item.text.isNotEmpty()) SelectionContainer { MarkdownText(item.text, Modifier.fillMaxWidth()) }
        is ChatItem.ToolCall -> ToolCard(item)
        is ChatItem.Notice -> Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun ToolCard(item: ChatItem.ToolCall) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().clickable(enabled = item.output != null) { expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    item.running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    item.isError -> Icon(Icons.Filled.Error, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    else -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Filled.Build, null, Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text(toolLabel(item.name), style = MaterialTheme.typography.labelMedium)
            }
            Text(
                item.summary,
                fontFamily = MonoFamily, fontSize = 12.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            AnimatedVisibility(expanded && item.output != null) {
                SelectionContainer {
                    Text(
                        item.output.orEmpty().take(20_000),
                        fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }
}

private fun toolLabel(name: String) = when (name) {
    "Bash" -> "Befehl"
    "Read" -> "Datei lesen"
    "Write" -> "Datei schreiben"
    "Edit", "MultiEdit" -> "Datei bearbeiten"
    "Glob", "Grep", "LS" -> "Suche"
    "WebFetch", "WebSearch" -> "Web"
    "TodoWrite" -> "Aufgabenliste"
    "Task", "Agent" -> "Teilaufgabe"
    "run_command" -> "Befehl"
    "read_file" -> "Datei lesen"
    "write_file" -> "Datei schreiben"
    "edit_file" -> "Datei bearbeiten"
    "list_directory" -> "Verzeichnis"
    "list_connections" -> "Verbindungen"
    else -> name
}

@Composable
private fun EmptyState(hasKey: Boolean, connectionNames: List<String>, onSetup: (Dest) -> Unit, onPrompt: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null,
            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF160A17)),
        )
        Text("Humantize DevApp", style = MaterialTheme.typography.titleLarge)
        Text("Was soll die KI für dich erledigen?", style = MaterialTheme.typography.bodyLarge)
        if (!hasKey) {
            Text("Zuerst einen Anthropic-API-Key hinterlegen.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onSetup(Dest.SETTINGS) }) { Text("Zu den Einstellungen") }
        }
        if (connectionNames.isEmpty()) {
            Text("Noch keine Server oder Nextcloud eingerichtet.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onSetup(Dest.CONNECTIONS) }) { Text("Verbindung anlegen") }
        } else {
            val first = connectionNames.first()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "Was läuft auf $first? Zeig mir Auslastung und Dienste.",
                    "Zeig mir den git status der Projekte auf $first.",
                    "Finde die Tests im Projekt auf $first, lass sie laufen und behebe Fehler.",
                ).forEach { p -> AssistChip(onClick = { onPrompt(p) }, label = { Text(p, maxLines = 1) }) }
            }
        }
    }
}
