package app.devdeck.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.hub.HubClient
import app.devdeck.core.hub.HubException
import app.devdeck.ui.appContainer
import app.devdeck.ui.theme.MonoFamily
import kotlinx.coroutines.launch

/** Login to the Humantize Hub: all servers, settings and widgets are synced end-to-end encrypted. */
@Composable
fun AccountSection() {
    val hub = appContainer().hub
    val account by hub.account.collectAsState()
    val status by hub.status.collectAsState()
    val known by hub.knownHub.collectAsState()
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmFp by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // fingerprint, register?
    LaunchedEffect(known) { if (url.isBlank()) url = known?.url.orEmpty() }

    fun go(register: Boolean, fingerprint: String) {
        busy = true; error = null
        scope.launch {
            try {
                hub.login(url.trim(), fingerprint, email.trim(), password, register)
                password = ""
            } catch (e: HubException) {
                error = e.message
            } catch (e: Exception) {
                error = "Hub nicht erreichbar: ${e.message ?: e.javaClass.simpleName}"
            }
            busy = false
        }
    }

    fun start(register: Boolean) {
        val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        url = u
        val pinned = known?.takeIf { it.url.trimEnd('/') == u.trimEnd('/') }?.fingerprint
        if (pinned != null) return go(register, pinned)
        busy = true; error = null
        scope.launch {
            try { confirmFp = HubClient.fetchFingerprint(u) to register } catch (e: Exception) { error = "Hub nicht erreichbar: ${e.message}" }
            busy = false
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Konto & Sync", style = MaterialTheme.typography.titleMedium)
            val acc = account
            if (acc != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CloudDone, null, tint = MaterialTheme.colorScheme.secondary)
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(acc.email, style = MaterialTheme.typography.bodyLarge)
                        Text(acc.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(status.ifBlank { "Server, Einstellungen und Widgets werden Ende-zu-Ende-verschlüsselt synchronisiert." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { hub.sync() } }) { Text("Jetzt synchronisieren") }
                    OutlinedButton(onClick = hub::logout) { Text("Abmelden") }
                }
            } else {
                Text(
                    "Mit einem Konto auf deinem Humantize Hub liegen alle Server, Einstellungen und Widgets verschlüsselt im Login " +
                        "und sind auf jedem Gerät da. Den Hub installierst du über Server → Server einrichten.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(url, { url = it }, label = { Text("Hub-Adresse (https://server:8443)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("E-Mail") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Passwort") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Text("Das Passwort verlässt das Gerät nie im Klartext – daraus wird der Schlüssel für die Verschlüsselung abgeleitet. " +
                    "Vergessenes Passwort = Daten im Konto nicht wiederherstellbar.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ready = !busy && url.isNotBlank() && email.contains("@") && password.length >= 8
                    Button(onClick = { start(false) }, enabled = ready) { Text("Anmelden") }
                    OutlinedButton(onClick = { start(true) }, enabled = ready) { Text("Konto erstellen") }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (password.isNotEmpty() && password.length < 8) Text("Mindestens 8 Zeichen.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    confirmFp?.let { (fp, register) ->
        AlertDialog(
            onDismissRequest = { confirmFp = null },
            title = { Text("Hub-Zertifikat bestätigen") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Erste Verbindung zu diesem Hub. Stimmt der Fingerabdruck mit dem aus der Installation überein?")
                    Text(fp, fontFamily = MonoFamily, fontSize = 11.sp)
                }
            },
            confirmButton = { TextButton(onClick = { confirmFp = null; go(register, fp) }) { Text("Vertrauen") } },
            dismissButton = { TextButton(onClick = { confirmFp = null }) { Text("Abbrechen") } },
        )
    }
}
