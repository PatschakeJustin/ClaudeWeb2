package app.devdeck.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.planner.*
import app.devdeck.data.PlannerRepository
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SleepModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    val cfg = l.sleepConfig ?: SleepConfig()
    var editing by remember { mutableStateOf<SleepEntry?>(null) }
    var settings by remember { mutableStateOf(false) }
    var thought by remember { mutableStateOf("") }
    var noted by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            // Last night + quick entry
            val last = l.sleepOn(today)
            ItemCard(onClick = { editing = last ?: SleepEntry(date = today.toString(), bed = cfg.targetBed, wake = cfg.targetWake) }) {
                Text("Letzte Nacht", style = MaterialTheme.typography.labelLarge)
                if (last == null) {
                    Text("Wie hast du geschlafen? Tippen zum Eintragen.", style = MaterialTheme.typography.bodyLarge)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDuration(last.sleepMinutes() * 60_000L), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("  ${MOODS[(last.quality - 1).coerceIn(0, 4)]}", fontSize = 28.sp)
                    }
                    Text("${last.bed} – ${last.wake} · ${last.wakeups}× wach · ${last.checks.size}/${SLEEP_CHECKS.size} Regeln",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Stats
            val avg = l.sleepAverage(today, 7)
            val spread = l.bedtimeSpread(today)
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat(avg?.let { formatDuration(it * 60_000L) } ?: "–", "Ø 7 Nächte")
                Stat(spread?.let { "±$it min" } ?: "–", "Zubettgeh-Zeit")
                Stat("${cfg.targetBed}", "Ziel ins Bett")
            }
            if (spread != null) Text(
                if (spread <= 30) "Sehr regelmäßig – gut für deinen Rhythmus." else "Deine Zubettgeh-Zeit schwankt stark. Feste Zeiten helfen dem Schlaf-Wach-Rhythmus.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SleepChart(l.activeSleep().filter { LocalDate.parse(it.date) > today.minusDays(14) }.reversed(), Modifier.fillMaxWidth().height(110.dp).padding(vertical = 8.dp))

            // Tip of the day
            ItemCard {
                Text("💡 Tipp des Tages", style = MaterialTheme.typography.labelLarge)
                Text(LifeText.sleepTip(today), style = MaterialTheme.typography.bodyMedium)
            }

            // Night notebook
            SectionTitle("Gedanken-Notizblock")
            Text("Kreisen Gedanken um Erledigungen? Kurz aufschreiben – landet in deinen Todos, der Kopf darf loslassen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(thought, { thought = it; noted = false }, placeholder = { Text("Was dir durch den Kopf geht …") }, modifier = Modifier.weight(1f))
                TextButton(enabled = thought.isNotBlank(), onClick = {
                    val t = thought.trim()
                    repo.update { it.copy(todos = it.todos + Todo(title = t, list = "Nachts notiert")) }
                    thought = ""; noted = true
                }) { Text("Notieren") }
            }
            if (noted) Text("Notiert ✓ – gute Nacht.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Schlaftagebuch")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { settings = true }) { Text("Ziele & Erinnerungen") }
            }
        }
        items(l.activeSleep().take(60), key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(MOODS[(e.quality - 1).coerceIn(0, 4)], fontSize = 20.sp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(LocalDate.parse(e.date).format(DATE_DE), style = MaterialTheme.typography.bodyMedium)
                    Text("${e.bed} – ${e.wake}" + (if (e.wakeups > 0) " · ${e.wakeups}× wach" else "") + (if (e.note.isNotBlank()) " · ${e.note}" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Text(formatDuration(e.sleepMinutes() * 60_000L), fontWeight = FontWeight.Medium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item {
            Text("Die Tipps sind allgemeine Schlafhygiene-Regeln und ersetzen keine ärztliche Beratung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }

    editing?.let { orig ->
        var e by remember(orig.id) { mutableStateOf(orig) }
        var bed by remember(orig.id) { mutableStateOf(orig.bed) }
        var wake by remember(orig.id) { mutableStateOf(orig.wake) }
        val isNew = l.sleep.none { it.id == orig.id }
        val ok = normTime(bed) != null && normTime(wake) != null
        EditDialog(if (isNew) "Nacht eintragen" else "Nacht", { editing = null },
            onSave = if (ok) ({
                repo.life { it.copy(sleep = it.sleep.upsert(e.copy(bed = normTime(bed)!!, wake = normTime(wake)!!, updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(sleep = it.sleep.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            DateField("Morgen vom", e.date, { d -> d?.let { e = e.copy(date = it) } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Ins Bett", bed, { bed = it }, Modifier.weight(1f))
                TimeField("Aufgestanden", wake, { wake = it }, Modifier.weight(1f))
            }
            Text("Bis zum Einschlafen", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("< 15 min", "30 min", "1 h", "> 1 h"), when { e.latency >= 90 -> "> 1 h"; e.latency >= 60 -> "1 h"; e.latency >= 30 -> "30 min"; else -> "< 15 min" }) {
                e = e.copy(latency = when (it) { "30 min" -> 30; "1 h" -> 60; "> 1 h" -> 90; else -> 10 })
            }
            Text("Nachts aufgewacht", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("0", "1", "2", "3", "4+"), if (e.wakeups >= 4) "4+" else e.wakeups.toString()) {
                e = e.copy(wakeups = if (it == "4+") 4 else it.toInt())
            }
            Text("Wie erholt fühlst du dich?", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MOODS.forEachIndexed { i, m ->
                    Box(Modifier.size(42.dp).clip(CircleShape)
                        .background(if (e.quality == i + 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { e = e.copy(quality = i + 1) }, contentAlignment = Alignment.Center) { Text(m, fontSize = 20.sp) }
                }
            }
            Text("Gestern Abend eingehalten", style = MaterialTheme.typography.labelLarge)
            SLEEP_CHECKS.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clickable { e = e.copy(checks = if (id in e.checks) e.checks - id else e.checks + id) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(id in e.checks, { e = e.copy(checks = if (it) e.checks + id else e.checks - id) })
                    Text(label, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(e.note, { e = e.copy(note = it) }, label = { Text("Notiz (Träume, Störungen …)") }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (settings) {
        var c by remember { mutableStateOf(cfg) }
        val ok = listOf(c.targetBed, c.targetWake, c.caffeineCutoff).all { normTime(it) != null }
        EditDialog("Ziele & Erinnerungen", { settings = false }, onSave = if (ok) ({
            repo.life { it.copy(sleepConfig = c.copy(targetBed = normTime(c.targetBed)!!, targetWake = normTime(c.targetWake)!!,
                caffeineCutoff = normTime(c.caffeineCutoff)!!, updatedAt = now())) }
        }) else null, onDelete = null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Ins Bett", c.targetBed, { c = c.copy(targetBed = it) }, Modifier.weight(1f))
                TimeField("Aufstehen", c.targetWake, { c = c.copy(targetWake = it) }, Modifier.weight(1f))
            }
            TimeField("Kein Koffein mehr ab", c.caffeineCutoff, { c = c.copy(caffeineCutoff = it) })
            Text("Erinnerung zum Runterfahren vor dem Zubettgehen", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("aus", "30 min", "60 min", "90 min"), if (c.windDownMinutes == 0) "aus" else "${c.windDownMinutes} min") {
                c = c.copy(windDownMinutes = if (it == "aus") 0 else it.removeSuffix(" min").toInt())
            }
            Text("Erinnerungen: Koffein-Stopp, Runterfahren (inkl. Lüften) und morgens „Wie hast du geschlafen?“.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Stat(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(value, style = MaterialTheme.typography.titleMedium)
    Text(label, style = MaterialTheme.typography.labelSmall)
}

/** Bars of the last nights; the line marks 8 hours. */
@Composable
private fun SleepChart(nights: List<SleepEntry>, modifier: Modifier) {
    if (nights.size < 2) return
    val bar = MaterialTheme.colorScheme.primary
    val bad = MaterialTheme.colorScheme.error
    val line = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val max = 10 * 60f
        val w = size.width / nights.size
        nights.forEachIndexed { i, n ->
            val h = (n.sleepMinutes() / max).coerceIn(0f, 1f) * size.height
            drawRect(if (n.sleepMinutes() < 6 * 60) bad else bar, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
        }
        val y = size.height - 8 * 60 / max * size.height
        drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
    }
}
