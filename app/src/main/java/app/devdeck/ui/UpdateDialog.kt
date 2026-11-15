package app.devdeck.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
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
                    Text("Darf die App das Update jetzt herunterladen und installieren?", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { updater.download(s.info) }) { Text("Aktualisieren") } },
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Später") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Update wird geladen …") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()} %", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = updater::dismiss) { Text("Abbrechen") } },
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Installation erlauben") },
            text = {
                Text(
                    "Android muss einmalig erlauben, dass Humantize DevApp Updates installiert. " +
                        "Tippe auf „Einstellungen“, aktiviere „Aus dieser Quelle zulassen“, komm zurück und tippe auf „Installieren“."
                )
            },
            confirmButton = { TextButton(onClick = { updater.install(s.info, s.file) }) { Text("Installieren") } },
            dismissButton = { TextButton(onClick = updater::openInstallPermissionSettings) { Text("Einstellungen") } },
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
