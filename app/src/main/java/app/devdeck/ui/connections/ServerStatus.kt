package app.devdeck.ui.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.devdeck.AppContainer
import app.devdeck.TerminalRequest
import app.devdeck.core.claudecode.LOGIN_STATUS_SCRIPT
import app.devdeck.core.claudecode.ToolStatus
import app.devdeck.core.claudecode.loginStepFor
import app.devdeck.core.claudecode.toolStatus
import app.devdeck.core.model.SshConnection
import app.devdeck.ui.Dest

/** Shows which AI tools are installed and logged in on a server, with a (re-)login button per tool. */
@Composable
fun ServerStatusDialog(
    container: AppContainer, conn: SshConnection, navigate: (Dest) -> Unit,
    onInstall: () -> Unit, onDismiss: () -> Unit,
) {
    var status by remember { mutableStateOf<List<ToolStatus>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        status = null; error = null
        try {
            status = toolStatus(container.ssh.exec(conn, LOGIN_STATUS_SCRIPT, timeoutSeconds = 30).stdout)
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }

    fun login(list: List<ToolStatus>) {
        val steps = list.mapNotNull { loginStepFor(it) }
        if (steps.isEmpty()) return
        container.terminalRequests.value = TerminalRequest(conn.id, logins = steps)
        onDismiss(); navigate(Dest.TERMINAL)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anmeldestatus · ${conn.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val st = status
                when {
                    error != null -> Text("Verbindung fehlgeschlagen: $error", color = MaterialTheme.colorScheme.error)
                    st == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Prüfe den Server …", Modifier.padding(start = 10.dp))
                    }
                    else -> {
                        st.forEach { t ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(t.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        when {
                                            !t.installed -> "nicht installiert"
                                            t.loggedIn -> "✓ angemeldet"
                                            else -> "✗ nicht angemeldet"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = when {
                                            !t.installed -> MaterialTheme.colorScheme.onSurfaceVariant
                                            t.loggedIn -> Color(0xFF4CC38A)
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                    )
                                }
                                if (t.installed) OutlinedButton(onClick = { login(listOf(t)) }) {
                                    Text(if (t.loggedIn) "Neu anmelden" else "Anmelden")
                                }
                            }
                        }
                        if (st.none { it.installed }) TextButton(onClick = { onDismiss(); onInstall() }) { Text("Werkzeuge installieren …") }
                    }
                }
            }
        },
        confirmButton = {
            val st = status
            if (st != null && st.count { it.installed } > 1) {
                TextButton(onClick = { login(st.filter { it.installed }) }) { Text("Alle nacheinander") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { reload++ }) { Text("Aktualisieren") }
                TextButton(onClick = onDismiss) { Text("Schließen") }
            }
        },
    )
}
