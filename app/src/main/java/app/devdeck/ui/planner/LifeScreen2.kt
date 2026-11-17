package app.devdeck.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.planner.*
import app.devdeck.data.PlannerRepository
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

// ===================================================================== 6 Gesundheit

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HealthModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var editMed by remember { mutableStateOf<Medication?>(null) }
    var kind by rememberSaveable { mutableStateOf(MeasureKind.WEIGHT) }
    var value by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            SectionTitle("Medikamente heute")
            val doses = l.dosesOn(today)
            if (doses.isEmpty()) Text("Keine Medikamente angelegt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            doses.forEach { (m, t, done) ->
                Row(Modifier.fillMaxWidth().clickable { editMed = m }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(done, { repo.life { it.copy(medications = it.medications.upsert(m.toggled(today, t))) } })
                    Text(t, Modifier.width(56.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(m.name + if (m.dose.isNotBlank()) " · ${m.dose}" else "")
                }
            }
            TextButton(onClick = { editMed = Medication(name = "") }) { Text("+ Medikament") }

            SectionTitle("Wasser heute")
            val water = l.waterOn(today)
            Row(verticalAlignment = Alignment.CenterVertically) {
                (1..8).forEach { i ->
                    Box(Modifier.padding(2.dp).size(26.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (i <= water) colorOf(0) else MaterialTheme.colorScheme.surfaceVariant))
                }
                Spacer(Modifier.width(8.dp))
                Text("$water", style = MaterialTheme.typography.titleMedium)
            }
            Row {
                FilledTonalButton(onClick = { repo.life { it.copy(measurements = it.measurements + Measurement(kind = MeasureKind.WATER, value = "1", date = today.toString(), time = LocalTime.now().format(HM))) } }) {
                    Text("+ 1 Glas")
                }
                Spacer(Modifier.width(8.dp))
                if (water > 0) TextButton(onClick = {
                    val last = l.measurements.filter { !it.deleted && it.kind == MeasureKind.WATER && it.date == today.toString() }.maxByOrNull { it.updatedAt }
                    if (last != null) repo.life { it.copy(measurements = it.measurements.upsert(last.copy(deleted = true, updatedAt = now()))) }
                }) { Text("− 1") }
            }

            SectionTitle("Messwerte")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MeasureKind.entries.filter { it != MeasureKind.WATER }.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value, { value = it }, label = { Text("${kind.label} (${kind.unit})") }, singleLine = true,
                    placeholder = { Text(if (kind == MeasureKind.BLOOD_PRESSURE) "120/80" else "") }, modifier = Modifier.weight(1f))
                IconButton(enabled = value.isNotBlank(), onClick = {
                    repo.life { it.copy(measurements = it.measurements + Measurement(kind = kind, value = value.trim().replace(',', '.'), date = today.toString(), time = LocalTime.now().format(HM))) }
                    value = ""
                }) { Icon(Icons.Filled.Add, "Speichern") }
            }
            val values = l.measurementsOf(kind)
            val numbers = values.mapNotNull { it.value.substringBefore('/').toFloatOrNull() }
            if (numbers.size >= 2) Sparkline(numbers.takeLast(30), Modifier.fillMaxWidth().height(70.dp).padding(vertical = 8.dp))
        }
        items(l.measurementsOf(kind).reversed().take(20), key = { it.id }) { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(LocalDate.parse(m.date).format(DATE_DE) + " " + m.time, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text("${m.value} ${kind.unit}", fontWeight = FontWeight.Medium)
                IconButton(onClick = { repo.life { it.copy(measurements = it.measurements.upsert(m.copy(deleted = true, updatedAt = now()))) } }) {
                    Icon(Icons.Filled.Close, "Löschen", Modifier.size(16.dp))
                }
            }
        }
        item {
            Text("Arzttermine trägst du im Planer-Kalender ein – sie erscheinen dann auch im Tagesbriefing.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
    editMed?.let { orig ->
        var m by remember(orig.id) { mutableStateOf(orig) }
        var times by remember(orig.id) { mutableStateOf(orig.times.joinToString(", ")) }
        val parsed = times.split(',', ' ', ';').filter { it.isNotBlank() }.map { normTime(it) }
        val isNew = l.medications.none { it.id == orig.id }
        EditDialog(if (isNew) "Neues Medikament" else m.name, { editMed = null },
            onSave = if (m.name.isNotBlank() && parsed.isNotEmpty() && parsed.all { it != null }) ({
                repo.life { it.copy(medications = it.medications.upsert(m.copy(times = parsed.filterNotNull().distinct().sorted(), updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(medications = it.medications.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(m.name, { m = m.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(m.dose, { m = m.copy(dose = it) }, label = { Text("Dosis (z. B. 1 Tablette)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(times, { times = it }, label = { Text("Uhrzeiten (z. B. 08:00, 20:00)") }, singleLine = true,
                isError = parsed.any { it == null }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(m.active, { m = m.copy(active = it) }); Text("  Aktiv (mit Erinnerung)") }
        }
    }
}

@Composable
private fun Sparkline(values: List<Float>, modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = values.min(); val max = values.max(); val span = (max - min).takeIf { it > 0f } ?: 1f
        val step = size.width / (values.size - 1)
        val pts = values.mapIndexed { i, v -> Offset(i * step, size.height - (v - min) / span * size.height) }
        pts.zipWithNext().forEach { (a, b) -> drawLine(color, a, b, strokeWidth = 4f) }
        pts.lastOrNull()?.let { drawCircle(color, 7f, it) }
    }
}

// ===================================================================== 7 Zeiterfassung

@Composable
internal fun TimeModule(repo: PlannerRepository, l: LifeData, today: LocalDate, tick: Long) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    var project by rememberSaveable { mutableStateOf(l.projects().firstOrNull() ?: "") }
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<TimeEntry?>(null) }
    val running = l.runningTimer()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            ItemCard {
                if (running != null) {
                    @Suppress("UNUSED_EXPRESSION") tick
                    Text("Läuft: ${running.project}", style = MaterialTheme.typography.titleMedium)
                    if (running.note.isNotBlank()) Text(running.note, style = MaterialTheme.typography.bodySmall)
                    Text(formatDuration(running.durationMs()), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Button(onClick = { repo.life { it.copy(time = it.time.upsert(running.copy(end = now(), updatedAt = now()))) } },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Stop, null); Text("  Stopp")
                    }
                } else {
                    OutlinedTextField(project, { project = it }, label = { Text("Projekt / Kunde") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val known = l.projects().filter { it != project }.take(6)
                    if (known.isNotEmpty()) ChoiceChips(known, "") { project = it }
                    OutlinedTextField(note, { note = it }, label = { Text("Tätigkeit (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(enabled = project.isNotBlank(), onClick = {
                        repo.life { it.copy(time = it.time + TimeEntry(project = project.trim(), note = note.trim(), start = now())) }; note = ""
                    }, modifier = Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.PlayArrow, null); Text("  Start") }
                }
            }
            val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val weekStart = today.minusDays((weekdayOf(today) - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
            val monthStart = today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("Heute" to dayStart, "Woche" to weekStart, "Monat" to monthStart).forEach { (label, from) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(formatDuration(l.durationBetween(from, now())), style = MaterialTheme.typography.titleMedium)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val perProject = l.projects().map { it to l.durationBetween(weekStart, now(), it) }.filter { it.second > 0 }.sortedByDescending { it.second }
            if (perProject.isNotEmpty()) {
                SectionTitle("Diese Woche nach Projekt")
                perProject.forEach { (p, ms) -> Row { Text(p, Modifier.weight(1f)); Text(formatDuration(ms)) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Einträge")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { shareText(context, "Zeiterfassung.csv", l.timeCsv(zone)) }) { Text("CSV exportieren") }
            }
        }
        items(l.timeEntries().take(100), key = { it.id }) { e ->
            val s = Instant.ofEpochMilli(e.start).atZone(zone)
            Row(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.project + if (e.note.isNotBlank()) " · ${e.note}" else "")
                    Text(s.toLocalDate().format(DATE_DE) + "  " + s.format(HM) + " – " + (e.end?.let { Instant.ofEpochMilli(it).atZone(zone).format(HM) } ?: "läuft"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatDuration(e.durationMs()))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    editing?.let { orig ->
        var e by remember(orig.id) { mutableStateOf(orig) }
        val zs = Instant.ofEpochMilli(orig.start).atZone(zone)
        var date by remember(orig.id) { mutableStateOf(zs.toLocalDate().toString()) }
        var from by remember(orig.id) { mutableStateOf(zs.format(HM)) }
        var to by remember(orig.id) { mutableStateOf(orig.end?.let { Instant.ofEpochMilli(it).atZone(zone).format(HM) } ?: "") }
        val f = normTime(from); val t = if (orig.end == null && to.isBlank()) "" else normTime(to)
        EditDialog("Eintrag", { editing = null },
            onSave = if (e.project.isNotBlank() && f != null && t != null) ({
                val d = LocalDate.parse(date)
                val start = d.atTime(LocalTime.parse(f)).atZone(zone).toInstant().toEpochMilli()
                var end = if (t.isEmpty()) null else d.atTime(LocalTime.parse(t)).atZone(zone).toInstant().toEpochMilli()
                if (end != null && end < start) end += 86_400_000 // over midnight
                repo.life { it.copy(time = it.time.upsert(e.copy(start = start, end = end, updatedAt = now()))) }
            }) else null,
            onDelete = { repo.life { it.copy(time = it.time.upsert(orig.copy(deleted = true, updatedAt = now()))) } }) {
            OutlinedTextField(e.project, { e = e.copy(project = it) }, label = { Text("Projekt") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(e.note, { e = e.copy(note = it) }, label = { Text("Tätigkeit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Datum", date, { d -> d?.let { date = it } })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Von", from, { from = it }, Modifier.weight(1f))
                TimeField("Bis", to, { to = it }, Modifier.weight(1f))
            }
        }
    }
}

// ===================================================================== 8 Geburtstage & Kontakte

@Composable
internal fun PeopleModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var editing by remember { mutableStateOf<Person?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp)) {
            val soon = l.upcomingBirthdays(today, 60)
            if (soon.isNotEmpty()) item { SectionTitle("Demnächst") }
            items(soon, key = { "s" + it.first.id }) { (p, d) ->
                ItemCard(onClick = { editing = p }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (d == 0L) "🎂" else "🎁", fontSize = 22.sp)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(p.name + (p.turningAge(today)?.let { " wird $it" } ?: ""), style = MaterialTheme.typography.bodyLarge)
                            Text(when (d) { 0L -> "Heute!"; 1L -> "Morgen"; else -> "in $d Tagen · " + p.nextBirthday(today)!!.format(DATE_DE) },
                                style = MaterialTheme.typography.bodySmall, color = if (d <= 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (p.gifts.isNotBlank()) Text("Ideen: ${p.gifts}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            val overdue = l.activePeople().filter { it.contactOverdue(today) }
            if (overdue.isNotEmpty()) item { SectionTitle("Mal wieder melden") }
            items(overdue, key = { "o" + it.id }) { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("☎ ${p.name}", Modifier.weight(1f))
                    TextButton(onClick = { repo.life { it.copy(people = it.people.upsert(p.copy(lastContact = today.toString(), updatedAt = now()))) } }) { Text("Erledigt") }
                }
            }
            item { SectionTitle("Alle (${l.activePeople().size})") }
            items(l.activePeople(), key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 8.dp)) {
                    Text(p.name, Modifier.weight(1f))
                    p.nextBirthday(today)?.let { Text("${it.dayOfMonth}.${it.monthValue}.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        AddFab { editing = Person(name = "") }
    }
    editing?.let { orig ->
        var p by remember(orig.id) { mutableStateOf(orig) }
        var yearUnknown by remember(orig.id) { mutableStateOf(orig.birthday?.startsWith("--") == true) }
        val isNew = l.people.none { it.id == orig.id }
        EditDialog(if (isNew) "Neue Person" else p.name, { editing = null },
            onSave = if (p.name.isNotBlank()) ({
                val b = p.birthday?.let { if (yearUnknown) "--" + it.takeLast(5) else if (it.startsWith("--")) "2000" + it.drop(1) else it }
                repo.life { it.copy(people = it.people.upsert(p.copy(birthday = b, updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(people = it.people.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Geburtstag", p.birthday?.let { if (it.startsWith("--")) "2000" + it.drop(1) else it }, { p = p.copy(birthday = it) }, allowEmpty = true)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(yearUnknown, { yearUnknown = it }); Text("  Jahr unbekannt") }
            OutlinedTextField(p.gifts, { p = p.copy(gifts = it) }, label = { Text("Geschenkideen") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.note, { p = p.copy(note = it) }, label = { Text("Notiz") }, modifier = Modifier.fillMaxWidth())
            Text("Erinnern, wenn länger kein Kontakt", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("nie", "2 Wochen", "1 Monat", "3 Monate"), when (p.contactEveryDays) { 14 -> "2 Wochen"; 30 -> "1 Monat"; 90 -> "3 Monate"; else -> "nie" }) {
                p = p.copy(contactEveryDays = when (it) { "2 Wochen" -> 14; "1 Monat" -> 30; "3 Monate" -> 90; else -> 0 })
            }
            if (!isNew) TextButton(onClick = { p = p.copy(lastContact = today.toString()) }) {
                Text("Heute Kontakt gehabt" + (p.lastContact?.let { " (zuletzt ${LocalDate.parse(it).format(DATE_DE)})" } ?: ""))
            }
        }
    }
}

// ===================================================================== 9 Haushalt & Putzplan

@Composable
internal fun ChoresModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var editing by remember { mutableStateOf<Chore?>(null) }
    Box(Modifier.fillMaxSize()) {
        val list = l.activeChores(today)
        if (list.isEmpty()) Empty("Wiederkehrende Aufgaben wie „Bad putzen alle 14 Tage“ oder „Wasserfilter wechseln“ – auf Wunsch abwechselnd verteilt.")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp)) {
            items(list, key = { it.id }) { c ->
                val d = c.daysLeft(today)
                ItemCard(onClick = { editing = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.bodyLarge)
                            Text(when { d < 0 -> "seit ${-d} Tagen fällig"; d == 0L -> "heute fällig"; d == 1L -> "morgen"; else -> "in $d Tagen" } +
                                " · alle ${c.everyDays} T" + (c.whoseTurn()?.let { " · dran: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall, color = if (d <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        FilledTonalButton(onClick = { repo.life { it.copy(chores = it.chores.upsert(c.done(today))) } }) { Text("Erledigt") }
                    }
                }
            }
        }
        AddFab { editing = Chore(title = "") }
    }
    editing?.let { orig ->
        var c by remember(orig.id) { mutableStateOf(orig) }
        var people by remember(orig.id) { mutableStateOf(orig.people.joinToString(", ")) }
        var every by remember(orig.id) { mutableStateOf(orig.everyDays.toString()) }
        val isNew = l.chores.none { it.id == orig.id }
        val n = every.toIntOrNull()?.takeIf { it in 1..3650 }
        EditDialog(if (isNew) "Neue Aufgabe" else c.title, { editing = null },
            onSave = if (c.title.isNotBlank() && n != null) ({
                repo.life { it.copy(chores = it.chores.upsert(c.copy(everyDays = n, people = people.split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(chores = it.chores.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(c.title, { c = c.copy(title = it) }, label = { Text("Aufgabe") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(listOf("1", "3", "7", "14", "30", "90"), every) { every = it }
            OutlinedTextField(every, { every = it.filter { ch -> ch.isDigit() } }, label = { Text("Alle … Tage") }, singleLine = true, isError = n == null)
            OutlinedTextField(people, { people = it }, label = { Text("Wer? (abwechselnd, mit Komma)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Zuletzt erledigt", c.lastDone, { c = c.copy(lastDone = it) }, allowEmpty = true)
        }
    }
}

// ===================================================================== 10 Tagebuch

@Composable
internal fun JournalModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var day by remember { mutableStateOf(today) }
    val existing = l.journalOn(day)
    var mood by remember(day, existing?.id) { mutableStateOf(existing?.mood ?: 3) }
    var text by remember(day, existing?.id) { mutableStateOf(existing?.text.orEmpty()) }
    var saved by remember(day) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            DateField("Tag", day.toString(), { d -> d?.let { day = LocalDate.parse(it) } })
            Text("Wie war der Tag?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MOODS.forEachIndexed { i, e ->
                    Box(Modifier.size(48.dp).clip(CircleShape)
                        .background(if (mood == i + 1) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { mood = i + 1; saved = false }, contentAlignment = Alignment.Center) { Text(e, fontSize = 24.sp) }
                }
            }
            OutlinedTextField(text, { text = it; saved = false }, label = { Text("Was war los? Wofür bist du dankbar?") }, minLines = 5,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    repo.life { it.copy(journal = it.journal.upsert((existing ?: JournalEntry(date = day.toString())).copy(mood = mood, text = text, updatedAt = now()))) }
                    saved = true
                }) { Text("Speichern") }
                if (saved) Text("  Gespeichert ✓", color = MaterialTheme.colorScheme.primary)
            }
            Text("Ende-zu-Ende verschlüsselt – der Hub sieht nur Datensalat.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            val past = l.onThisDay(today)
            if (past.isNotEmpty()) {
                SectionTitle("Heute vor …")
                past.forEach { e -> ItemCard { Text("${today.year - LocalDate.parse(e.date).year} Jahr(en) ${MOODS[(e.mood - 1).coerceIn(0, 4)]}", fontWeight = FontWeight.Medium); Text(e.text) } }
            }
            val last30 = l.activeJournal().filter { LocalDate.parse(it.date) > today.minusDays(30) }
            if (last30.isNotEmpty()) {
                SectionTitle("Stimmung (30 Tage) Ø " + String.format(DE, "%.1f", last30.map { it.mood }.average()))
            }
            SectionTitle("Einträge")
        }
        items(l.activeJournal().take(60), key = { it.id }) { e ->
            Row(Modifier.fillMaxWidth().clickable { day = LocalDate.parse(e.date) }.padding(vertical = 6.dp)) {
                Text(MOODS[(e.mood - 1).coerceIn(0, 4)], fontSize = 20.sp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text(LocalDate.parse(e.date).format(DATE_DE), style = MaterialTheme.typography.labelMedium)
                    Text(e.text, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
