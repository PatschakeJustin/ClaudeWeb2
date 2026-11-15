package app.devdeck.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import app.devdeck.ui.appContainer

private val MODELS = listOf(
    "claude-opus-5-5" to "Opus 5.5 (Standard)",
    "claude-sonnet-5-5" to "Sonnet 5.5 (schneller, günstiger)",
    "claude-haiku-5-5" to "Haiku 5.5 (sehr günstig)",
    "claude-fable-5-2" to "Fable 5.2 (stärkstes Modell, teuer)",
)
private val EFFORTS = listOf("low", "medium", "high", "xhigh", "max")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val repo = appContainer().settings
    val s by repo.state.collectAsState()
    var showKey by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Einstellungen") })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).widthIn(max = 640.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude-API", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = s.apiKey,
                onValueChange = { v -> repo.update { it.copy(apiKey = v.trim()) } },
                label = { Text("Anthropic-API-Key (sk-ant-…)") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Verbergen" else "Zeigen") } },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Den Key erstellst du unter console.anthropic.com → API Keys. Er wird verschlüsselt nur auf diesem Gerät gespeichert. " +
                    "Die Nutzung wird über dein API-Konto abgerechnet.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("Modell", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MODELS.forEach { (id, label) ->
                    FilterChip(selected = s.model == id, onClick = { repo.update { it.copy(model = id) } }, label = { Text(label) })
                }
            }
            Text("Gründlichkeit (effort)", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EFFORTS.forEach { e ->
                    FilterChip(selected = s.effort == e, onClick = { repo.update { it.copy(effort = e) } }, label = { Text(e) })
                }
            }
            Text(
                "Höher = gründlicher, aber langsamer und teurer. „high“ ist für Programmieraufgaben ein guter Standard.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SwitchRow(
                "Befehle und Dateiänderungen ohne Nachfrage",
                "Claude führt Befehle aus und schreibt Dateien, ohne jedes Mal zu fragen. Nur einschalten, wenn du dem Server-Zustand vertraust.",
                s.autoApprove,
            ) { v -> repo.update { it.copy(autoApprove = v) } }
            SwitchRow(
                "Ausweichmodell bei Ablehnung",
                "Lehnt das Modell eine Anfrage ab, beantwortet die API sie automatisch mit einem passenden anderen Modell (Beta-Funktion).",
                s.refusalFallback,
            ) { v -> repo.update { it.copy(refusalFallback = v) } }

            HorizontalDivider()
            Text("Humantize DevApp Mobile 0.2.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
