package app.devdeck.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.planner.Board
import app.devdeck.core.planner.BoardCard
import app.devdeck.core.planner.CalendarEvent
import app.devdeck.core.planner.Deadline
import app.devdeck.core.planner.Habit
import app.devdeck.core.planner.Note
import app.devdeck.core.planner.PlannerData
import app.devdeck.core.planner.PlannerItem
import app.devdeck.core.planner.Repeat
import app.devdeck.core.planner.Todo
import app.devdeck.core.planner.WeekItem
import app.devdeck.core.planner.now
import app.devdeck.core.planner.weekdayOf
import app.devdeck.core.planner.weekdayShort
import app.devdeck.data.PlannerRepository
import app.devdeck.ui.appContainer
import app.devdeck.ui.home.WidgetColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.util.Locale

private enum class PlannerTab(val label: String) {
    CALENDAR("Kalender"), TODOS("Todos"), WEEK("Woche"), HABITS("Gewohnheiten"), DEADLINES("Fristen"), NOTES("Notizen"), BOARD("Board")
}

internal val DE = Locale.GERMANY
internal val DATE_DE = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", DE)
internal fun today(): LocalDate = LocalDate.now()
internal fun colorOf(i: Int) = WidgetColors[i.mod(WidgetColors.size)]

/** Replaces the item with the same id or appends it. */
internal fun <T : PlannerItem> List<T>.upsert(item: T): List<T> =
    if (any { it.id == item.id }) map { if (it.id == item.id) item else it } else this + item

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen() {
    val repo = appContainer().planner
    val data by repo.data.collectAsState()
    var tab by rememberSaveable { mutableStateOf(PlannerTab.CALENDAR) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Planer") })
        PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
            PlannerTab.entries.forEach { t -> Tab(selected = tab == t, onClick = { tab = t }, text = { Text(t.label) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                PlannerTab.CALENDAR -> CalendarTab(repo, data)
                PlannerTab.TODOS -> TodosTab(repo, data)
                PlannerTab.WEEK -> WeekTab(repo, data)
                PlannerTab.HABITS -> HabitsTab(repo, data)
                PlannerTab.DEADLINES -> DeadlinesTab(repo, data)
                PlannerTab.NOTES -> NotesTab(repo, data)
                PlannerTab.BOARD -> BoardTab(repo, data)
            }
        }
    }
}

// ---------------------------------------------------------------- shared inputs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, date: String?, onChange: (String?) -> Unit, allowEmpty: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: " + (date?.let { LocalDate.parse(it).format(DATE_DE) } ?: "keins"))
        }
        if (allowEmpty && date != null) TextButton(onClick = { onChange(null) }) { Text("entfernen") }
    }
    if (open) {
        val init = (date?.let { LocalDate.parse(it) } ?: today()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = init)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }
}

internal val TIME = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")
internal fun normTime(s: String): String? = s.trim().takeIf { TIME.matches(it) }?.let { if (it.length == 4) "0$it" else it }

@Composable
internal fun TimeField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value, { onChange(it.filter { c -> c.isDigit() || c == ':' }.take(5)) }, label = { Text(label) }, singleLine = true,
        isError = value.isNotBlank() && normTime(value) == null, placeholder = { Text("08:30") }, modifier = modifier,
    )
}

@Composable
internal fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WidgetColors.forEachIndexed { i, c ->
            Box(Modifier.size(if (selected == i) 30.dp else 24.dp).clip(CircleShape).background(c).clickable { onSelect(i) })
        }
    }
}

@Composable
internal fun EditDialog(
    title: String, onDismiss: () -> Unit, onSave: (() -> Unit)?, onDelete: (() -> Unit)?, content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } },
        confirmButton = { TextButton(enabled = onSave != null, onClick = { onSave?.invoke(); onDismiss() }) { Text("Speichern") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { onDelete(); onDismiss() }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
internal fun AddFab(onClick: () -> Unit) = Box(Modifier.fillMaxSize()) {
    FloatingActionButton(onClick = onClick, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.Add, "Hinzufügen") }
}

@Composable
internal fun Empty(text: String) = Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------- calendar

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CalendarTab(repo: PlannerRepository, data: PlannerData) {
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf(today()) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, "Voriger Monat") }
            Text("${month.month.getDisplayName(JTextStyle.FULL, DE)} ${month.year}", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { month = YearMonth.now(); selected = today() }) { Text("Heute") }
            IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, "Nächster Monat") }
        }
        Row(Modifier.fillMaxWidth()) {
            (1..7).forEach { Text(weekdayShort(it), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val first = month.atDay(1)
        val offset = weekdayOf(first) - 1
        val cells = (0 until 42).map { first.minusDays(offset.toLong()).plusDays(it.toLong()) }
        cells.chunked(7).forEach { week ->
            if (week.first().month != month.month && week.first().isAfter(first)) return@forEach
            Row(Modifier.fillMaxWidth()) {
                week.forEach { d ->
                    val events = data.eventsOn(d)
                    val isSel = d == selected
                    Column(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                            .then(if (d == today()) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable { selected = d },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("${d.dayOfMonth}", style = MaterialTheme.typography.bodyMedium,
                            color = if (d.month == month.month) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            events.take(3).forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(colorOf(it.color))) }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected.format(DATE_DE), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = CalendarEvent(title = "", date = selected.toString()) }) { Text("+ Termin") }
        }
        val dayEvents = data.eventsOn(selected)
        if (dayEvents.isEmpty()) Text("Keine Termine", color = MaterialTheme.colorScheme.onSurfaceVariant)
        dayEvents.forEach { e ->
            Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { editing = e },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 4.dp, height = 32.dp).clip(RoundedCornerShape(2.dp)).background(colorOf(e.color)))
                    Column(Modifier.padding(start = 10.dp)) {
                        Text(e.title, style = MaterialTheme.typography.bodyLarge)
                        Text((e.start?.let { s -> s + (e.end?.let { " – $it" } ?: "") } ?: "ganztägig") +
                            (if (e.repeat != Repeat.NONE) " · ${e.repeat.label}" else ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(72.dp))
    }
    editing?.let { ev ->
        var e by remember(ev.id) { mutableStateOf(ev) }
        var allDay by remember(ev.id) { mutableStateOf(ev.start == null) }
        var start by remember(ev.id) { mutableStateOf(ev.start.orEmpty()) }
        var end by remember(ev.id) { mutableStateOf(ev.end.orEmpty()) }
        val isNew = data.events.none { it.id == ev.id }
        val valid = e.title.isNotBlank() && (allDay || normTime(start) != null) && (end.isBlank() || normTime(end) != null)
        EditDialog(if (isNew) "Neuer Termin" else "Termin", { editing = null },
            onSave = if (valid) ({
                repo.update { it.copy(events = it.events.upsert(e.copy(start = if (allDay) null else normTime(start),
                    end = if (allDay) null else normTime(end), updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.update { it.copy(events = it.events.upsert(ev.copy(deleted = true, updatedAt = now()))) } }),
        ) {
            OutlinedTextField(e.title, { e = e.copy(title = it) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Datum", e.date, { d -> d?.let { e = e.copy(date = it) } })
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(allDay, { allDay = it }); Text("  Ganztägig") }
            if (!allDay) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeField("Beginn", start, { start = it }, Modifier.weight(1f))
                TimeField("Ende (optional)", end, { end = it }, Modifier.weight(1f))
            }
            Text("Wiederholung", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r -> FilterChip(selected = e.repeat == r, onClick = { e = e.copy(repeat = r) }, label = { Text(r.label) }) }
            }
            ColorPicker(e.color) { e = e.copy(color = it) }
            OutlinedTextField(e.note, { e = e.copy(note = it) }, label = { Text("Notiz") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ---------------------------------------------------------------- todos

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodosTab(repo: PlannerRepository, data: PlannerData) {
    var quick by remember { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(0) } // 0 open, 1 done, 2 all
    var editing by remember { mutableStateOf<Todo?>(null) }
    fun save(t: Todo) = repo.update { it.copy(todos = it.todos.upsert(t.copy(updatedAt = now()))) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            OutlinedTextField(quick, { quick = it }, placeholder = { Text("Neues Todo …") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(enabled = quick.isNotBlank(), onClick = { save(Todo(title = quick.trim())); quick = "" }) { Icon(Icons.Filled.Add, "Hinzufügen") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf("Offen", "Erledigt", "Alle").forEachIndexed { i, l -> FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(l) }) }
        }
        val list = when (filter) {
            0 -> data.openTodos()
            1 -> data.todos.filter { !it.deleted && it.done }.sortedByDescending { it.updatedAt }
            else -> data.todos.filter { !it.deleted }.sortedWith(compareBy<Todo> { it.done }.thenByDescending { it.priority })
        }
        if (list.isEmpty()) Empty(if (filter == 0) "Nichts offen – oben ein Todo eintippen." else "Keine Einträge.")
        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(t.done, { d -> save(t.copy(done = d)) })
                    Column(Modifier.weight(1f)) {
                        Text((if (t.priority == 2) "‼ " else if (t.priority == 1) "! " else "") + t.title,
                            textDecoration = if (t.done) TextDecoration.LineThrough else null,
                            color = if (t.priority == 2 && !t.done) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        t.due?.let { d ->
                            val late = !t.done && LocalDate.parse(d).isBefore(today())
                            Text("fällig " + LocalDate.parse(d).format(DATE_DE), style = MaterialTheme.typography.bodySmall,
                                color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    editing?.let { orig ->
        var t by remember(orig.id) { mutableStateOf(orig) }
        EditDialog("Todo", { editing = null }, onSave = if (t.title.isNotBlank()) ({ save(t) }) else null,
            onDelete = { repo.update { it.copy(todos = it.todos.upsert(orig.copy(deleted = true, updatedAt = now()))) } }) {
            OutlinedTextField(t.title, { t = t.copy(title = it) }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
            DateField("Fällig", t.due, { t = t.copy(due = it) }, allowEmpty = true)
            Text("Priorität", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Normal", "Wichtig", "Dringend").forEachIndexed { i, l ->
                    FilterChip(selected = t.priority == i, onClick = { t = t.copy(priority = i) }, label = { Text(l) })
                }
            }
        }
    }
}

// ---------------------------------------------------------------- weekly plan

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekTab(repo: PlannerRepository, data: PlannerData) {
    var editing by remember { mutableStateOf<WeekItem?>(null) }
    var importing by remember { mutableStateOf(false) }
    val todayWd = weekdayOf(today())
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 80.dp)) {
            (1..7).forEach { wd ->
                item(key = "h$wd") {
                    Text(listOf("Montag", "Dienstag", "Mittwoch", "Donnerstag", "Freitag", "Samstag", "Sonntag")[wd - 1] +
                        if (wd == todayWd) "  · heute" else "",
                        style = MaterialTheme.typography.titleSmall,
                        color = if (wd == todayWd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (wd == todayWd) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                val items = data.weekPlan(wd)
                if (items.isEmpty()) item(key = "e$wd") { Text("–", color = MaterialTheme.colorScheme.outline) }
                items(items, key = { it.id }) { w ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { editing = w }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colorOf(w.color)))
                        Text(w.time ?: "–", Modifier.width(64.dp).padding(start = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(w.title)
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            androidx.compose.material3.ExtendedFloatingActionButton(onClick = { importing = true }) { Text("Importieren") }
        }
        AddFab { editing = WeekItem(weekday = todayWd, title = "") }
    }
    if (importing) WeekImportDialog(repo, data) { importing = false }
    editing?.let { orig ->
        var w by remember(orig.id) { mutableStateOf(orig) }
        var time by remember(orig.id) { mutableStateOf(orig.time.orEmpty()) }
        val isNew = data.week.none { it.id == orig.id }
        EditDialog(if (isNew) "Eintrag im Wochenplan" else "Wochenplan", { editing = null },
            onSave = if (w.title.isNotBlank() && (time.isBlank() || normTime(time) != null)) ({
                repo.update { it.copy(week = it.week.upsert(w.copy(time = normTime(time), updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.update { it.copy(week = it.week.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(w.title, { w = w.copy(title = it) }, label = { Text("Was?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (1..7).forEach { d -> FilterChip(selected = w.weekday == d, onClick = { w = w.copy(weekday = d) }, label = { Text(weekdayShort(d)) }) }
            }
            TimeField("Uhrzeit (optional)", time, { time = it })
            ColorPicker(w.color) { w = w.copy(color = it) }
        }
    }
}

// ---------------------------------------------------------------- habits

@Composable
private fun HabitsTab(repo: PlannerRepository, data: PlannerData) {
    var editing by remember { mutableStateOf<Habit?>(null) }
    val t = today()
    fun save(h: Habit) = repo.update { it.copy(habits = it.habits.upsert(h)) }
    Box(Modifier.fillMaxSize()) {
        val habits = data.activeHabits()
        if (habits.isEmpty()) Empty("Lege Gewohnheiten an, z. B. „10 Minuten lesen“, und hake sie täglich ab.")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(habits, key = { it.id }) { h ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().clickable { editing = h }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(h.isDone(t), { save(h.toggled(t)) })
                            Text(h.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("🔥 ${h.streak(t)}", style = MaterialTheme.typography.titleSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 12.dp)) {
                            (6 downTo 0).forEach { back ->
                                val d = t.minusDays(back.toLong())
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(weekdayShort(weekdayOf(d)), style = MaterialTheme.typography.labelSmall, fontSize = 10.sp)
                                    Box(Modifier.size(22.dp).clip(CircleShape)
                                        .background(if (h.isDone(d)) colorOf(h.color) else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { save(h.toggled(d)) })
                                }
                            }
                        }
                    }
                }
            }
        }
        AddFab { editing = Habit(title = "") }
    }
    editing?.let { orig ->
        var h by remember(orig.id) { mutableStateOf(orig) }
        val isNew = data.habits.none { it.id == orig.id }
        EditDialog(if (isNew) "Neue Gewohnheit" else "Gewohnheit", { editing = null },
            onSave = if (h.title.isNotBlank()) ({ save(h.copy(updatedAt = now())) }) else null,
            onDelete = if (isNew) null else ({ save(orig.copy(deleted = true, updatedAt = now())) })) {
            OutlinedTextField(h.title, { h = h.copy(title = it) }, label = { Text("Gewohnheit") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ColorPicker(h.color) { h = h.copy(color = it) }
        }
    }
}

// ---------------------------------------------------------------- deadlines

@Composable
private fun DeadlinesTab(repo: PlannerRepository, data: PlannerData) {
    var editing by remember { mutableStateOf<Deadline?>(null) }
    val t = today()
    Box(Modifier.fillMaxSize()) {
        val list = data.deadlines.filter { !it.deleted }.sortedBy { it.date }
        if (list.isEmpty()) Empty("Fristen und Termine zum Herunterzählen, z. B. Abgaben oder Urlaub.")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 80.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.id }) { d ->
                val n = d.daysLeft(t)
                val urgent = when { n < 0 -> MaterialTheme.colorScheme.outline; n <= 3 -> MaterialTheme.colorScheme.error
                    n <= 7 -> androidx.compose.ui.graphics.Color(0xFFF2A93B); else -> colorOf(d.color) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().clickable { editing = d }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(d.title, style = MaterialTheme.typography.bodyLarge)
                            Text(LocalDate.parse(d.date).format(DATE_DE), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (n < 0) "${-n}" else "$n", style = MaterialTheme.typography.headlineSmall, color = urgent, fontWeight = FontWeight.Bold)
                            Text(when { n < 0 -> "Tage vorbei"; n == 1L -> "Tag"; else -> "Tage" }, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        AddFab { editing = Deadline(title = "", date = t.plusDays(7).toString()) }
    }
    editing?.let { orig ->
        var d by remember(orig.id) { mutableStateOf(orig) }
        val isNew = data.deadlines.none { it.id == orig.id }
        EditDialog(if (isNew) "Neue Frist" else "Frist", { editing = null },
            onSave = if (d.title.isNotBlank()) ({ repo.update { it.copy(deadlines = it.deadlines.upsert(d.copy(updatedAt = now()))) } }) else null,
            onDelete = if (isNew) null else ({ repo.update { it.copy(deadlines = it.deadlines.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(d.title, { d = d.copy(title = it) }, label = { Text("Was?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            DateField("Datum", d.date, { v -> v?.let { d = d.copy(date = it) } })
            ColorPicker(d.color) { d = d.copy(color = it) }
        }
    }
}

// ---------------------------------------------------------------- notes

@Composable
private fun NotesTab(repo: PlannerRepository, data: PlannerData) {
    var editing by remember { mutableStateOf<Note?>(null) }
    Box(Modifier.fillMaxSize()) {
        val notes = data.activeNotes()
        if (notes.isEmpty()) Empty("Schnellnotizen – auch als Widget auf dem Startbildschirm.")
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(160.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 8.dp, 12.dp, 80.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(notes.size, key = { notes[it].id }) { i ->
                val n = notes[i]
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).clickable { editing = n }) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(colorOf(n.color)))
                            Text(n.title.ifBlank { "Notiz" }, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
                            if (n.pinned) Icon(Icons.Filled.PushPin, "Angeheftet", Modifier.size(14.dp))
                        }
                        Text(n.text, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        AddFab { editing = Note(title = "") }
    }
    editing?.let { orig ->
        var n by remember(orig.id) { mutableStateOf(orig) }
        val isNew = data.notes.none { it.id == orig.id }
        EditDialog(if (isNew) "Neue Notiz" else "Notiz", { editing = null },
            onSave = if (n.title.isNotBlank() || n.text.isNotBlank()) ({ repo.update { it.copy(notes = it.notes.upsert(n.copy(updatedAt = now()))) } }) else null,
            onDelete = if (isNew) null else ({ repo.update { it.copy(notes = it.notes.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(n.title, { n = n.copy(title = it) }, label = { Text("Titel") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(n.text, { n = n.copy(text = it) }, label = { Text("Text") }, minLines = 5, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(n.pinned, { n = n.copy(pinned = it) }); Text("  Anheften") }
            ColorPicker(n.color) { n = n.copy(color = it) }
        }
    }
}

// ---------------------------------------------------------------- kanban board

@Composable
private fun BoardTab(repo: PlannerRepository, data: PlannerData) {
    val boards = data.activeBoards()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val board = boards.firstOrNull { it.id == selectedId } ?: boards.firstOrNull()
    var newBoard by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf<Pair<Board, BoardCard>?>(null) }
    fun saveBoard(b: Board) = repo.update { it.copy(boards = it.boards.upsert(b.copy(updatedAt = now()))) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            boards.forEach { b -> FilterChip(selected = b.id == board?.id, onClick = { selectedId = b.id }, label = { Text(b.name) }) }
            TextButton(onClick = { newBoard = true }) { Text("+ Board") }
        }
        if (board == null) { Empty("Lege ein Board an, z. B. für ein Projekt."); return@Column }
        Row(Modifier.weight(1f).fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            board.columns.forEachIndexed { col, name ->
                val cards = board.cards.filter { it.column == col }
                Column(Modifier.width(250.dp).fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(8.dp)) {
                    Text("$name  ${cards.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(4.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(cards, key = { it.id }) { card ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                                modifier = Modifier.fillMaxWidth().clickable { editCard = board to card }) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(card.title)
                                    Row {
                                        if (col > 0) IconButton(onClick = {
                                            saveBoard(board.copy(cards = board.cards.map { if (it.id == card.id) it.copy(column = col - 1) else it }))
                                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Nach links") }
                                        Spacer(Modifier.weight(1f))
                                        if (col < board.columns.lastIndex) IconButton(onClick = {
                                            saveBoard(board.copy(cards = board.cards.map { if (it.id == card.id) it.copy(column = col + 1) else it }))
                                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Nach rechts") }
                                    }
                                }
                            }
                        }
                    }
                    TextButton(onClick = { editCard = board to BoardCard(title = "", column = col) }) { Text("+ Karte") }
                }
            }
        }
    }

    if (newBoard) {
        var name by remember { mutableStateOf("") }
        EditDialog("Neues Board", { newBoard = false }, onSave = if (name.isNotBlank()) ({
            val b = Board(name = name.trim()); saveBoard(b); selectedId = b.id
        }) else null, onDelete = null) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    editCard?.let { (b, orig) ->
        var c by remember(orig.id) { mutableStateOf(orig) }
        val isNew = b.cards.none { it.id == orig.id }
        EditDialog(if (isNew) "Neue Karte" else "Karte", { editCard = null },
            onSave = if (c.title.isNotBlank()) ({
                saveBoard(b.copy(cards = if (isNew) b.cards + c else b.cards.map { if (it.id == c.id) c else it }))
            }) else null,
            onDelete = if (isNew) null else ({ saveBoard(b.copy(cards = b.cards.filterNot { it.id == orig.id })) })) {
            OutlinedTextField(c.title, { c = c.copy(title = it) }, label = { Text("Titel") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.note, { c = c.copy(note = it) }, label = { Text("Notiz") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            if (!isNew) TextButton(onClick = { saveBoard(b.copy(deleted = true)); editCard = null }) {
                Text("Ganzes Board „${b.name}“ löschen", color = MaterialTheme.colorScheme.error)
            }
        }
    }

}


/** Paste or load a weekly plan as text ("Mo 07:30 Frühstück", "Mo-Fr 07:10 Frühsport", "Sa/So …"). */
@Composable
private fun WeekImportDialog(repo: PlannerRepository, data: PlannerData, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(true) }
    var skip by remember { mutableStateOf("") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            text = context.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
        }
    }
    val words = skip.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val (parsed, bad) = remember(text) { app.devdeck.core.planner.WeekPlanImport.parse(text) }
    val items = parsed.filter { item -> words.none { item.title.contains(it, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wochenplan importieren") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Eine Zeile pro Termin: Tag(e) Uhrzeit Was – z. B. „Mo 07:30 Frühstück“, „Mo-Fr 07:10 Frühsport“, „Sa/So 11:30 Mittagessen“.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { picker.launch(arrayOf("text/*")) }) { Text("Textdatei wählen …") }
                OutlinedTextField(text, { text = it }, label = { Text("…oder hier einfügen") }, minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(skip, { skip = it }, label = { Text("Ausblenden, wenn enthalten (z. B. Gr. 2B)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(replace, { replace = it }); Text("  Bisherigen Wochenplan ersetzen") }
                Text("${items.size} Einträge erkannt" + if (bad.isNotEmpty()) ", ${bad.size} Zeilen nicht verstanden:" else "",
                    style = MaterialTheme.typography.labelLarge)
                bad.take(5).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = items.isNotEmpty(), onClick = {
                repo.update { p ->
                    val old = if (replace) p.week.map { if (it.deleted) it else it.copy(deleted = true, updatedAt = now()) } else p.week
                    p.copy(week = old + items)
                }
                onDismiss()
            }) { Text("Importieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
