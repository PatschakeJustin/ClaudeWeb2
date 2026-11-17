package app.devdeck.ui.connections

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.devdeck.AppContainer
import app.devdeck.core.model.AiServerConnection
import app.devdeck.core.model.Connection
import androidx.compose.material.icons.filled.Memory
import app.devdeck.core.model.NextcloudConnection
import app.devdeck.core.model.RdpConnection
import app.devdeck.core.model.SshConnection
import app.devdeck.core.nextcloud.WebDavFs
import app.devdeck.ui.appContainer
import app.devdeck.ui.Dest
import app.devdeck.data.ServerBackup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.devdeck.TerminalRequest
import app.devdeck.core.claudecode.LOGIN_STATUS_SCRIPT
import app.devdeck.core.claudecode.loginSteps
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import app.devdeck.ui.theme.MonoFamily
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(navigate: (Dest) -> Unit) {
    val container = appContainer()
    val store by container.connections.state.collectAsState()
    var editing by remember { mutableStateOf<Connection?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf<SshConnection?>(null) }
    var setupOpen by remember { mutableStateOf(false) }
    var statusFor by remember { mutableStateOf<SshConnection?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.state.collectAsState()
    val backupError by container.backup.lastError.collectAsState()
    var fileMenu by remember { mutableStateOf(false) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            container.backup.enable(uri)
            Toast.makeText(context, "Gesichert. Die Datei wird ab jetzt bei jeder Änderung aktualisiert.", Toast.LENGTH_LONG).show()
        }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                val n = container.backup.import(uri)
                if (container.settings.state.value.backupUri == null) container.backup.enable(uri)
                Toast.makeText(context, "$n Verbindungen geladen", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Datei konnte nicht gelesen werden: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Verbindungen") },
                actions = {
                    Box {
                        IconButton(onClick = { fileMenu = true }) { Icon(Icons.Filled.MoreVert, "Datei") }
                        DropdownMenu(fileMenu, onDismissRequest = { fileMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (settings.backupUri == null) "Server in Datei sichern …" else "Sicherungsdatei ändern …") },
                                onClick = { fileMenu = false; createBackup.launch(ServerBackup.FILE_NAME) },
                            )
                            DropdownMenuItem(text = { Text("Server aus Datei laden …") }, onClick = {
                                fileMenu = false; openBackup.launch(arrayOf("text/plain", "application/json", "*/*"))
                            })
                            if (settings.backupUri != null) DropdownMenuItem(text = { Text("Automatische Sicherung beenden") }, onClick = {
                                fileMenu = false; container.backup.disable()
                                Toast.makeText(context, "Automatische Sicherung beendet", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                },
            )
            backupError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)) }
            if (store.all.isEmpty()) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("App neu installiert?", style = MaterialTheme.typography.titleSmall)
                            Text("Server aus deiner Sicherungsdatei (${ServerBackup.FILE_NAME}) laden.", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { openBackup.launch(arrayOf("text/plain", "application/json", "*/*")) }) { Text("Laden") }
                    }
                }
            } else if (settings.backupUri == null) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Server sichern", style = MaterialTheme.typography.titleSmall)
                            Text("In einer Textdatei speichern, damit sie eine Neuinstallation überstehen.", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { createBackup.launch(ServerBackup.FILE_NAME) }) { Text("Sichern") }
                    }
                }
            }
            Card(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Server einrichten", style = MaterialTheme.typography.titleMedium)
                        Text("Claude Code auf einem Server installieren und mit deinem Claude-Abo anmelden.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { setup = null; setupOpen = true }) { Text("Start") }
                }
            }
            if (store.all.isEmpty()) {
                Text(
                    "Lege SSH-Server, Nextcloud-Instanzen und Remote-Desktop-Verbindungen an. " +
                        "Claude kann auf SSH und Nextcloud zugreifen; RDP öffnet den Windows-Desktop direkt in der App.",
                    modifier = Modifier.padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            LazyColumn(Modifier.weight(1f)) {
                section("SSH-Server", store.ssh) { c ->
                    ConnectionRow(
                        Icons.Filled.Dns, c.name, "${c.username}@${c.host}:${c.port}" + if (c.windows) " · Windows" else "",
                        trailing = {
                            SshMenu(
                                onTerminal = {
                                    container.terminalRequests.value = TerminalRequest(c.id)
                                    navigate(Dest.TERMINAL)
                                },
                                onRelogin = {
                                    Toast.makeText(context, "Prüfe, was auf ${c.name} installiert ist …", Toast.LENGTH_SHORT).show()
                                    scope.launch {
                                        try {
                                            val steps = loginSteps(container.ssh.exec(c, LOGIN_STATUS_SCRIPT, timeoutSeconds = 30).stdout)
                                            if (steps.isEmpty()) {
                                                Toast.makeText(context, "Nichts zum Anmelden installiert – zuerst „Server einrichten“.", Toast.LENGTH_LONG).show()
                                            } else {
                                                container.terminalRequests.value = TerminalRequest(c.id, logins = steps)
                                                navigate(Dest.TERMINAL)
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Verbindung fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                },
                                onSetup = { setup = c; setupOpen = true },
                                onEdit = { editing = c },
                                onStatus = { statusFor = c },
                            )
                        },
                    ) { editing = c }
                }
                section("Nextcloud", store.nextcloud) { c ->
                    ConnectionRow(Icons.Filled.Cloud, c.name, "${c.username} · ${c.baseUrl}") { editing = c }
                }
                section("Selfhosted KI-Server", store.ai) { c ->
                    ConnectionRow(Icons.Filled.Memory, c.name, "${c.model.ifBlank { "kein Modell" }} · ${c.baseUrl}" +
                        (store.ssh.firstOrNull { it.id == c.tunnelSshId }?.let { " · Tunnel über ${it.name}" } ?: "")) { editing = c }
                }
                section("Remote Desktop (RDP)", store.rdp) { c ->
                    ConnectionRow(
                        Icons.Filled.DesktopWindows, c.name, "${c.host}:${c.port}",
                        trailing = { TextButton(onClick = { launchRdp(context, c) }) { Text("Öffnen") } },
                    ) { editing = c }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Filled.Add, "Hinzufügen") }
            DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("SSH-Server") }, onClick = { addMenu = false; editing = SshConnection(name = "", host = "", username = "root") })
                DropdownMenuItem(text = { Text("Nextcloud") }, onClick = { addMenu = false; editing = NextcloudConnection(name = "", baseUrl = "https://", username = "", appPassword = "") })
                DropdownMenuItem(text = { Text("Remote Desktop (RDP)") }, onClick = { addMenu = false; editing = RdpConnection(name = "", host = "") })
                DropdownMenuItem(text = { Text("Selfhosted KI-Server") }, onClick = { addMenu = false; editing = AiServerConnection(name = "") })
            }
        }
    }

    editing?.let { c ->
        EditDialog(
            container, c, isNew = store.all.none { it.id == c.id }, onDismiss = { editing = null },
            onInstallClaude = { ssh -> editing = null; setup = ssh; setupOpen = true },
        )
    }
    if (setupOpen) ServerSetupDialog(container, setup, navigate) { setupOpen = false }
    statusFor?.let { c ->
        ServerStatusDialog(container, c, navigate, onInstall = { setup = c; setupOpen = true }) { statusFor = null }
    }
}

private fun <T> androidx.compose.foundation.lazy.LazyListScope.section(title: String, list: List<T>, row: @Composable (T) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$title") {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
    }
    items(list) { row(it) }
}

@Composable
private fun SshMenu(onTerminal: () -> Unit, onRelogin: () -> Unit, onSetup: () -> Unit, onEdit: () -> Unit, onStatus: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "Aktionen") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Anmeldestatus / anmelden …") }, onClick = { open = false; onStatus() })
            DropdownMenuItem(text = { Text("Terminal (neu) starten") }, onClick = { open = false; onTerminal() })
            DropdownMenuItem(text = { Text("Alle neu anmelden") }, onClick = { open = false; onRelogin() })
            DropdownMenuItem(text = { Text("Claude Code & KIs installieren …") }, onClick = { open = false; onSetup() })
            DropdownMenuItem(text = { Text("Bearbeiten") }, onClick = { open = false; onEdit() })
        }
    }
}

@Composable
private fun ConnectionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, fontFamily = MonoFamily, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { Icon(icon, null) },
        trailingContent = trailing,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

fun launchRdp(context: Context, c: RdpConnection) {
    if (app.devdeck.rdp.RdpLauncher.available(context)) {
        try { app.devdeck.rdp.RdpLauncher.open(context, c); return }
        catch (e: Throwable) { Toast.makeText(context, "Eingebautes RDP fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show() }
    }
    // Fallback (e.g. device CPU without built-in RDP): Microsoft "Windows App" / Remote Desktop and several other clients understand rdp:// URIs.
    val parts = mutableListOf("full%20address=s:${c.host}:${c.port}")
    if (c.username.isNotBlank()) {
        val user = if (c.domain.isNotBlank()) "${c.domain}\\${c.username}" else c.username
        parts += "username=s:" + Uri.encode(user)
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("rdp://" + parts.joinToString("&")))
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "Keine RDP-App gefunden. Installiere z. B. die „Windows App“ (Microsoft) oder aFreeRDP aus dem Play Store.",
            Toast.LENGTH_LONG,
        ).show()
    }
}

@Composable
private fun EditDialog(
    container: AppContainer, initial: Connection, isNew: Boolean, onDismiss: () -> Unit,
    onInstallClaude: (SshConnection) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun test() {
        testing = true; testResult = null
        scope.launch {
            testResult = try {
                when (val d = draft) {
                    is SshConnection -> {
                        container.ssh.disconnect(d.id)
                        val r = container.ssh.exec(d, "echo \"ok: \$(whoami)@\$(hostname)\"", timeoutSeconds = 20)
                        container.ssh.disconnect(d.id)
                        "✓ " + r.stdout.trim().ifEmpty { "Verbunden (Exit-Code ${r.exitCode})" }
                    }
                    is NextcloudConnection -> {
                        val n = WebDavFs(d, container.http).list("/").size
                        "✓ Verbunden, $n Einträge im Hauptordner"
                    }
                    is RdpConnection -> "RDP: mit „Öffnen“ verbinden."
                    is AiServerConnection -> {
                        val models = container.aiClient.listModels(d)
                        if (models.isEmpty()) "✓ Erreichbar, aber keine Modelle installiert"
                        else "✓ Erreichbar. Modelle: " + models.joinToString(", ") +
                            (if (d.model.isNotBlank() && d.model !in models) "\n⚠ '${d.model}' ist nicht dabei" else "")
                    }
                }
            } catch (e: Exception) {
                "✗ " + (e.message ?: e.javaClass.simpleName)
            }
            testing = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth().imePadding(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    (if (isNew) "Neue " else "") + when (draft) {
                        is SshConnection -> "SSH-Verbindung"
                        is NextcloudConnection -> "Nextcloud-Verbindung"
                        is RdpConnection -> "RDP-Verbindung"
                        is AiServerConnection -> "KI-Server"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                when (val d = draft) {
                    is SshConnection -> SshFields(d) { draft = it }
                    is NextcloudConnection -> NextcloudFields(d) { draft = it }
                    is RdpConnection -> RdpFields(d) { draft = it }
                    is AiServerConnection -> AiFields(d, container.connections.state.value.ssh) { draft = it }
                }

                val d = draft
                if (d is SshConnection && valid(d)) {
                    TextButton(onClick = { container.connections.save(d); onInstallClaude(d) }) { Text("Claude Code auf diesem Server installieren …") }
                }
                if (d is SshConnection && !isNew) {
                    TextButton(onClick = {
                        container.knownHosts.forget("${d.host}:${d.port}")
                        Toast.makeText(context, "Gespeicherter Host-Schlüssel gelöscht", Toast.LENGTH_SHORT).show()
                    }) { Text("Host-Schlüssel zurücksetzen") }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (d is RdpConnection) {
                        OutlinedButton(onClick = { launchRdp(context, d) }, enabled = d.host.isNotBlank()) { Text("Öffnen") }
                    } else {
                        OutlinedButton(onClick = ::test, enabled = !testing && valid(d)) { Text(if (testing) "Teste …" else "Verbindung testen") }
                    }
                }
                testResult?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (!isNew) TextButton(onClick = { confirmDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    TextButton(enabled = valid(d), onClick = {
                        container.connections.save(d)
                        container.ssh.disconnect(d.id)
                        onDismiss()
                    }) { Text("Speichern") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Verbindung löschen?") },
            text = { Text("„${draft.name}“ wird aus der App entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    container.connections.delete(draft.id); container.ssh.disconnect(draft.id); confirmDelete = false; onDismiss()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

private fun valid(c: Connection): Boolean = c.name.isNotBlank() && when (c) {
    is SshConnection -> c.host.isNotBlank() && c.username.isNotBlank() && c.port in 1..65535
    is NextcloudConnection -> c.baseUrl.startsWith("http") && c.username.isNotBlank() && c.appPassword.isNotBlank()
    is RdpConnection -> c.host.isNotBlank() && c.port in 1..65535
    is AiServerConnection -> c.baseUrl.startsWith("http") && c.model.isNotBlank()
}

@Composable
private fun AiFields(c: AiServerConnection, sshList: List<SshConnection>, update: (AiServerConnection) -> Unit) {
    var tunnelMenu by remember { mutableStateOf(false) }
    Field(c.name, "Name (z. B. Ollama Büro)") { update(c.copy(name = it)) }
    Field(c.baseUrl, "Adresse (z. B. http://127.0.0.1:11434)") { update(c.copy(baseUrl = it.trim())) }
    Field(c.model, "Modell (z. B. qwen2.5-coder:7b)") { update(c.copy(model = it.trim())) }
    Field(c.apiKey, "API-Key (optional)", password = true) { update(c.copy(apiKey = it.trim())) }
    Box {
        OutlinedButton(onClick = { tunnelMenu = true }) {
            Text("SSH-Tunnel: " + (sshList.firstOrNull { it.id == c.tunnelSshId }?.name ?: "keiner (direkt)"))
        }
        DropdownMenu(tunnelMenu, onDismissRequest = { tunnelMenu = false }) {
            DropdownMenuItem(text = { Text("Kein Tunnel – Adresse direkt aufrufen") }, onClick = { tunnelMenu = false; update(c.copy(tunnelSshId = null)) })
            sshList.forEach { s ->
                DropdownMenuItem(text = { Text("Über ${s.name} (${s.host})") }, onClick = { tunnelMenu = false; update(c.copy(tunnelSshId = s.id)) })
            }
        }
    }
    Text(
        "Mit SSH-Tunnel wird die Adresse vom Server aus aufgerufen – so muss der KI-Port nicht offen im Internet stehen " +
            "(für Ollama: http://127.0.0.1:11434). Funktioniert mit Ollama, LM Studio, vLLM, LocalAI, llama.cpp und anderen " +
            "OpenAI-kompatiblen Servern. „Verbindung testen“ zeigt die verfügbaren Modelle.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Field(value: String, label: String, password: Boolean = false, number: Boolean = false, mono: Boolean = false,
                  minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = minLines == 1,
        minLines = minLines,
        maxLines = if (minLines == 1) 1 else 8,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Uri },
            autoCorrectEnabled = false,
        ),
        textStyle = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFamily) else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().let { if (minLines > 1) it.heightIn(min = 100.dp) else it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SshFields(c: SshConnection, update: (SshConnection) -> Unit) {
    var useKey by remember { mutableStateOf(!c.privateKey.isNullOrBlank()) }
    Field(c.name, "Name (z. B. webserver)") { update(c.copy(name = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(3f)) { Field(c.host, "Host / IP") { update(c.copy(host = it.trim())) } }
        Box(Modifier.weight(1f)) { Field(c.port.toString(), "Port", number = true) { update(c.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0)) } }
    }
    Field(c.username, "Benutzer") { update(c.copy(username = it.trim())) }
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SegmentedButton(selected = !useKey, onClick = { useKey = false; update(c.copy(privateKey = null, keyPassphrase = null)) },
            shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Passwort") }
        SegmentedButton(selected = useKey, onClick = { useKey = true; update(c.copy(password = null)) },
            shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("SSH-Schlüssel") }
    }
    if (useKey) {
        Field(c.privateKey.orEmpty(), "Privater Schlüssel (-----BEGIN … KEY-----)", mono = true, minLines = 4) { update(c.copy(privateKey = it)) }
        Field(c.keyPassphrase.orEmpty(), "Passphrase (optional)", password = true) { update(c.copy(keyPassphrase = it.ifEmpty { null })) }
    } else {
        Field(c.password.orEmpty(), "Passwort", password = true) { update(c.copy(password = it)) }
    }
    Field(c.defaultDirectory.orEmpty(), "Startverzeichnis (optional, z. B. /root/projekte)") {
        update(c.copy(defaultDirectory = it.trim().ifEmpty { null }))
    }
}

@Composable
private fun NextcloudFields(c: NextcloudConnection, update: (NextcloudConnection) -> Unit) {
    Field(c.name, "Name (z. B. cloud)") { update(c.copy(name = it)) }
    Field(c.baseUrl, "Adresse (https://cloud.example.com)") { update(c.copy(baseUrl = it.trim())) }
    Field(c.username, "Benutzer") { update(c.copy(username = it.trim())) }
    Field(c.appPassword, "App-Passwort", password = true) { update(c.copy(appPassword = it.trim())) }
    Text(
        "App-Passwort in Nextcloud unter Einstellungen → Sicherheit → „Neues App-Passwort erstellen“ anlegen.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RdpFields(c: RdpConnection, update: (RdpConnection) -> Unit) {
    Field(c.name, "Name (z. B. Büro-PC)") { update(c.copy(name = it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(3f)) { Field(c.host, "Host / IP") { update(c.copy(host = it.trim())) } }
        Box(Modifier.weight(1f)) { Field(c.port.toString(), "Port", number = true) { update(c.copy(port = it.filter(Char::isDigit).toIntOrNull() ?: 0)) } }
    }
    Field(c.username, "Benutzer (optional)") { update(c.copy(username = it.trim())) }
    Field(c.password, "Passwort (optional)", password = true) { update(c.copy(password = it)) }
    Field(c.domain, "Domäne (optional)") { update(c.copy(domain = it.trim())) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(c.fitScreen, { update(c.copy(fitScreen = it)) }); Text("  An Bildschirm anpassen", style = MaterialTheme.typography.bodyMedium)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(c.console, { update(c.copy(console = it)) }); Text("  Konsolen-Sitzung (/admin)", style = MaterialTheme.typography.bodyMedium)
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Text(
        if (app.devdeck.rdp.RdpLauncher.available(ctx))
            "Der Remote Desktop öffnet direkt in dieser App. Ohne gespeichertes Passwort wirst du beim Verbinden gefragt. " +
                "Das Passwort wird verschlüsselt auf dem Gerät gespeichert."
        else "Auf diesem Gerät (Prozessor ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}) ist das eingebaute RDP nicht verfügbar – " +
            "die Verbindung wird in einer installierten Remote-Desktop-App geöffnet.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
