package app.devdeck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.devdeck.data.UpdateState

/** Asks before updating; checks once per app start if enabled in the settings. */
@Composable
fun UpdateDialog() {
    val container = appContainer()
    val updater = container.updater
    val state by updater.state.collectAsState()

    LaunchedEffect(Unit) {
        if (container.settings.state.value.autoUpdateCheck) updater.check(manual = false)
    }

    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Update verfügbar") },
            text = {
                Column {
                    Text("Version ${s.info.versionName} ist da (installiert: ${updater.installedVersion}).")
                    if (s.info.notes.isNotBlank()) Text("Neu: ${s.info.notes}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
                    Text("Jetzt aktualisieren? Der Download startet im Browser; danach die Datei öffnen und „Installieren“ (bzw. „Aktualisieren“) tippen.",
                        modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { updater.openDownload(s.info) }) { Text("Aktualisieren") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Später") } },
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Alles aktuell") },
            text = { Text("Du hast die neueste Version (${updater.installedVersion}).") },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("OK") } },
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Update") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("OK") } },
        )
        UpdateState.Checking, UpdateState.Idle -> Unit
    }
}
