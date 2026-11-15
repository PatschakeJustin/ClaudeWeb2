package app.devdeck.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.devdeck.AppContainer
import app.devdeck.TerminalRequest
import app.devdeck.core.claudecode.INSTALL_SCRIPT
import app.devdeck.core.claudecode.STATUS_SCRIPT
import app.devdeck.core.claudecode.parseStatus
import app.devdeck.core.model.SshConnection
import app.devdeck.data.AppSettings
import app.devdeck.ui.Dest
import app.devdeck.ui.theme.MonoFamily
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private enum class Phase { FORM, RUNNING, DONE, FAILED }

/**
 * Wizard: enter server credentials -> install Claude Code over SSH (live log) -> log in with the
 * Claude subscription in the terminal.
 */
@Composable
fun ServerSetupDialog(container: AppContainer, initial: SshConnection?, navigate: (Dest) -> Unit, onDismiss: () -> Unit) {
    var conn by remember { mutableStateOf(initial ?: SshConnection(name = "", host = "", username = "root")) }
    var phase by remember { mutableStateOf(Phase.FORM) }
    val log = remember { mutableStateListOf<String>() }
    var version by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }

    fun start() {
        val c = conn.copy(name = conn.name.ifBlank { conn.host })
        conn = c
        container.connections.save(c)
        container.ssh.disconnect(c.id)
        log.clear()
        phase = Phase.RUNNING
        job = scope.launch {
            try {
                log += "Verbinde mit ${c.username}@${c.host}:${c.port} …"
                val status = parseStatus(container.ssh.exec(c, STATUS_SCRIPT, timeoutSeconds = 30).stdout)
                if (status.installed) log += "Claude Code ist bereits installiert (${status.version}) – wird aktualisiert."
                var ok = false
                val exit = container.ssh.execStreaming(c, "sh -s", stdin = INSTALL_SCRIPT, onStdout = { line ->
                    when (line.trim()) {
                        "DEVDECK_INSTALL_OK" -> ok = true
                        "DEVDECK_LOGGED_IN" -> loggedIn = true
                        else -> scope.launch { log += line }
                    }
                }, onStderr = { line -> scope.launch { log += line } })
                if (ok) {
                    val after = parseStatus(container.ssh.exec(c, STATUS_SCRIPT, timeoutSeconds = 30).stdout)
                    version = after.version
                    loggedIn = loggedIn || after.loggedIn
                    container.settings.update {
                        it.copy(agentMode = AppSettings.MODE_CLAUDE_CODE, ccConnectionId = c.id, ccWorkDir = "")
                    }
                    phase = Phase.DONE
                } else {
                    log += "Installation fehlgeschlagen (Exit-Code ${exit ?: "?"})."
                    phase = Phase.FAILED
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log += "Fehler: ${e.message ?: e.javaClass.simpleName}"
                phase = Phase.FAILED
            }
        }
    }

    Dialog(
        onDismissRequest = { if (phase != Phase.RUNNING) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.large, modifier = Modifier.padding(16.dp).widthIn(max = 640.dp).fillMaxWidth().imePadding()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Server einrichten", style = MaterialTheme.typography.titleLarge)
                when (phase) {
                    Phase.FORM -> {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Trag die Zugangsdaten deines Servers ein. DevDeck verbindet sich per SSH, installiert Claude Code " +
                                    "(sowie curl, git und tmux, falls sie fehlen) und hilft dir danach bei der Anmeldung mit deinem Claude-Abo.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            SshFields(conn) { conn = it }
                            Text(
                                "Tipp: Claude Code läuft als der hier eingetragene Benutzer. Für Arbeiten mit vollen Rechten " +
                                    "ist ein normaler Benutzer mit sudo sicherer als root.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text("Abbrechen") }
                            Button(onClick = ::start, enabled = conn.host.isNotBlank() && conn.username.isNotBlank() &&
                                (!conn.password.isNullOrEmpty() || !conn.privateKey.isNullOrBlank())) { Text("Installieren") }
                        }
                    }
                    Phase.RUNNING, Phase.DONE, Phase.FAILED -> {
                        if (phase == Phase.RUNNING) Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Installiere auf ${conn.name} … (kann 1–3 Minuten dauern)", Modifier.padding(start = 10.dp))
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                        ) {
                            items(log) { line ->
                                Text(line, fontFamily = MonoFamily, fontSize = 11.sp, lineHeight = 14.sp,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()))
                            }
                        }
                        when (phase) {
                            Phase.RUNNING -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { job?.cancel(); phase = Phase.FAILED; log += "Abgebrochen." }) { Text("Abbrechen") }
                            }
                            Phase.FAILED -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { phase = Phase.FORM }) { Text("Zurück") }
                                TextButton(onClick = onDismiss) { Text("Schließen") }
                                Button(onClick = ::start) { Text("Erneut versuchen") }
                            }
                            else -> DoneSection(container, conn, version, loggedIn, navigate, onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoneSection(
    container: AppContainer, conn: SshConnection, version: String?, loggedIn: Boolean,
    navigate: (Dest) -> Unit, onDismiss: () -> Unit,
) {
    Text("✓ Claude Code ist installiert" + (version?.let { " ($it)" } ?: "") + ".", color = MaterialTheme.colorScheme.primary)
    if (loggedIn) {
        Text("Auf dem Server ist bereits eine Anmeldung vorhanden. Du kannst direkt im Claude-Tab loslegen.",
            style = MaterialTheme.typography.bodyMedium)
    } else {
        Text("Jetzt mit deinem Claude-Abo anmelden:", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. „Anmelden“ öffnet das Terminal und startet Claude Code.\n" +
                "2. Farbschema mit Enter bestätigen, dann „Claude account with subscription“ wählen.\n" +
                "3. Oben auf das Link-Symbol tippen → „Öffnen“ → im Browser bei Claude anmelden und „Autorisieren“.\n" +
                "4. Den angezeigten Code kopieren, im Terminal „Einfügen“ tippen und Enter drücken.\n" +
                "5. Fertig – Claude Code mit /exit beenden. Danach im Claude-Tab „Abo“ nutzen.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        if (!loggedIn) {
            OutlinedButton(onClick = { onDismiss(); navigate(Dest.AGENT) }) { Text("Später") }
            Button(onClick = {
                container.terminalRequests.value = TerminalRequest(conn.id, "claude\r")
                onDismiss(); navigate(Dest.TERMINAL)
            }) { Text("Anmelden") }
        } else {
            Button(onClick = { onDismiss(); navigate(Dest.AGENT) }) { Text("Zum Claude-Tab") }
        }
    }
}
