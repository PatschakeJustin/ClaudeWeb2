package app.devdeck.core.planner

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Every planner item: an id, a modification time for sync merging and a deletion marker (tombstone). */
interface PlannerItem {
    val id: String
    val updatedAt: Long
    val deleted: Boolean
}

fun newPlannerId(): String = UUID.randomUUID().toString()
fun now(): Long = System.currentTimeMillis()

enum class Repeat(val label: String) { NONE("Einmalig"), DAILY("Täglich"), WEEKLY("Wöchentlich"), MONTHLY("Monatlich"), YEARLY("Jährlich") }

@Serializable
data class CalendarEvent(
    override val id: String = newPlannerId(),
    val title: String,
    /** ISO date, e.g. 2026-11-17. */
    val date: String,
    /** "HH:mm" or null for all-day events. */
    val start: String? = null,
    val end: String? = null,
    val repeat: Repeat = Repeat.NONE,
    val color: Int = 0,
    val note: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    /** Does this (possibly repeating) event happen on [day]? */
    fun occursOn(day: LocalDate): Boolean {
        val first = LocalDate.parse(date)
        if (day.isBefore(first)) return false
        return when (repeat) {
            Repeat.NONE -> day == first
            Repeat.DAILY -> true
            Repeat.WEEKLY -> ChronoUnit.DAYS.between(first, day) % 7 == 0L
            // On months without that day (e.g. the 31st) the event moves to the last day of the month.
            Repeat.MONTHLY -> day.dayOfMonth == minOf(first.dayOfMonth, day.lengthOfMonth())
            Repeat.YEARLY -> day.month == first.month && day.dayOfMonth == minOf(first.dayOfMonth, day.lengthOfMonth())
        }
    }
}

@Serializable
data class Todo(
    override val id: String = newPlannerId(),
    val title: String,
    val done: Boolean = false,
    val due: String? = null,
    /** 0 = normal, 1 = wichtig, 2 = dringend */
    val priority: Int = 0,
    val list: String = "Allgemein",
    val createdAt: Long = now(),
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** A recurring entry of the weekly plan (e.g. "Mo 08:00 Sport"). */
@Serializable
data class WeekItem(
    override val id: String = newPlannerId(),
    /** 1 = Monday … 7 = Sunday */
    val weekday: Int,
    val time: String? = null,
    val title: String,
    val color: Int = 0,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

@Serializable
data class Habit(
    override val id: String = newPlannerId(),
    val title: String,
    val color: Int = 0,
    /** ISO dates on which the habit was done. */
    val doneDates: Set<String> = emptySet(),
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun isDone(day: LocalDate) = day.toString() in doneDates

    /** Consecutive days up to today (or up to yesterday if today is not done yet). */
    fun streak(today: LocalDate): Int {
        var d = if (isDone(today)) today else today.minusDays(1)
        var n = 0
        while (isDone(d)) { n++; d = d.minusDays(1) }
        return n
    }

    fun toggled(day: LocalDate) = copy(
        doneDates = if (isDone(day)) doneDates - day.toString() else doneDates + day.toString(),
        updatedAt = now(),
    )
}

@Serializable
data class Deadline(
    override val id: String = newPlannerId(),
    val title: String,
    val date: String,
    val color: Int = 0,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun daysLeft(today: LocalDate): Long = ChronoUnit.DAYS.between(today, LocalDate.parse(date))
}

@Serializable
data class Note(
    override val id: String = newPlannerId(),
    val title: String,
    val text: String = "",
    val color: Int = 0,
    val pinned: Boolean = false,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

@Serializable
data class BoardCard(val id: String = newPlannerId(), val title: String, val column: Int = 0, val note: String = "")

@Serializable
data class Board(
    override val id: String = newPlannerId(),
    val name: String,
    val columns: List<String> = listOf("Offen", "In Arbeit", "Fertig"),
    val cards: List<BoardCard> = emptyList(),
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

@Serializable
data class PlannerData(
    val events: List<CalendarEvent> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val week: List<WeekItem> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val deadlines: List<Deadline> = emptyList(),
    val notes: List<Note> = emptyList(),
    val boards: List<Board> = emptyList(),
    /** Alltags-Module (Einkauf, Ausgaben, Rezepte, …). */
    val life: LifeData = LifeData(),
) {
    /**
     * Combines two copies (this device and the account): per item the newer change wins; deletions are kept
     * as tombstones so they also reach other devices.
     */
    fun merge(other: PlannerData) = PlannerData(
        events = mergeLists(events, other.events),
        todos = mergeLists(todos, other.todos),
        week = mergeLists(week, other.week),
        habits = mergeLists(habits, other.habits),
        deadlines = mergeLists(deadlines, other.deadlines),
        notes = mergeLists(notes, other.notes),
        boards = mergeLists(boards, other.boards),
        life = life.merge(other.life),
    )

    /** Removes tombstones older than [maxAgeMs] (default 90 days) to keep the data small. */
    fun pruned(nowMs: Long = now(), maxAgeMs: Long = 90L * 24 * 3600 * 1000): PlannerData {
        fun <T : PlannerItem> List<T>.p() = filter { !it.deleted || nowMs - it.updatedAt < maxAgeMs }
        return PlannerData(events.p(), todos.p(), week.p(), habits.p(), deadlines.p(), notes.p(), boards.p(), life.pruned(nowMs, maxAgeMs))
    }

    // ---- views (deleted items hidden) ----
    fun eventsOn(day: LocalDate) = events.filter { !it.deleted && it.occursOn(day) }.sortedBy { it.start ?: "" }

    /** Upcoming occurrences from [from] on, as (date, event), at most [limit]. */
    fun upcoming(from: LocalDate, days: Int = 60, limit: Int = 10): List<Pair<LocalDate, CalendarEvent>> =
        (0 until days).asSequence().map { from.plusDays(it.toLong()) }
            .flatMap { d -> eventsOn(d).map { d to it } }.take(limit).toList()

    fun openTodos() = todos.filter { !it.deleted && !it.done }
        .sortedWith(compareByDescending<Todo> { it.priority }.thenBy { it.due ?: "9999" }.thenBy { it.createdAt })

    fun weekPlan(weekday: Int) = week.filter { !it.deleted && it.weekday == weekday }.sortedBy { it.time ?: "" }
    fun activeHabits() = habits.filter { !it.deleted }
    fun activeDeadlines(today: LocalDate) = deadlines.filter { !it.deleted }.sortedBy { it.date }
        .filter { it.daysLeft(today) >= -7 }
    fun activeNotes() = notes.filter { !it.deleted }.sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    fun activeBoards() = boards.filter { !it.deleted }
}

internal fun <T : PlannerItem> mergeLists(a: List<T>, b: List<T>): List<T> {
    val byId = LinkedHashMap<String, T>()
    (a + b).forEach { item ->
        val cur = byId[item.id]
        if (cur == null || item.updatedAt > cur.updatedAt) byId[item.id] = item
    }
    return byId.values.toList()
}

private val WEEKDAYS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
fun weekdayShort(d: Int) = WEEKDAYS[(d - 1).coerceIn(0, 6)]
fun weekdayOf(day: LocalDate) = day.dayOfWeek.value

/** Short texts for widgets. */
object PlannerText {
    fun dayLabel(day: LocalDate, today: LocalDate): String = when (ChronoUnit.DAYS.between(today, day)) {
        0L -> "Heute"
        1L -> "Morgen"
        else -> "${weekdayShort(weekdayOf(day))} ${day.dayOfMonth}.${day.monthValue}."
    }

    fun upcoming(p: PlannerData, today: LocalDate, limit: Int = 6): String =
        p.upcoming(today, limit = limit).joinToString("\n") { (d, e) ->
            "${dayLabel(d, today)} ${e.start ?: "ganztägig"}  ${e.title}"
        }.ifEmpty { "Keine Termine in den nächsten 60 Tagen" }

    fun todos(p: PlannerData, limit: Int = 8): String {
        val open = p.openTodos()
        if (open.isEmpty()) return "Alles erledigt ✓"
        return open.take(limit).joinToString("\n") { t ->
            (if (t.priority >= 2) "‼ " else if (t.priority == 1) "! " else "• ") + t.title + (t.due?.let { "  (${it.substring(5)})" } ?: "")
        } + if (open.size > limit) "\n… +${open.size - limit}" else ""
    }

    fun weekToday(p: PlannerData, today: LocalDate): String =
        p.weekPlan(weekdayOf(today)).joinToString("\n") { "${it.time ?: "–"}  ${it.title}" }
            .ifEmpty { "Heute steht nichts im Wochenplan" }

    fun habits(p: PlannerData, today: LocalDate): String =
        p.activeHabits().joinToString("\n") { h -> "${if (h.isDone(today)) "✓" else "○"} ${h.title}  🔥${h.streak(today)}" }
            .ifEmpty { "Noch keine Gewohnheiten" }

    fun deadlines(p: PlannerData, today: LocalDate, limit: Int = 6): String =
        p.activeDeadlines(today).take(limit).joinToString("\n") { d ->
            val n = d.daysLeft(today)
            val left = when { n < 0 -> "seit ${-n} T vorbei"; n == 0L -> "heute"; n == 1L -> "morgen"; else -> "noch $n Tage" }
            "${d.title}: $left"
        }.ifEmpty { "Keine Fristen" }

    @Suppress("unused") private val unused = DayOfWeek.MONDAY
}

/**
 * Imports a weekly plan from simple text lines, e.g.
 * "Mo 07:30 Frühstück", "Mo-Fr 07:10 Frühsport", "Sa/So 11:30 Mittagessen", "Di Ausflug" (no time).
 * Lines starting with # and empty lines are ignored.
 */
object WeekPlanImport {
    private val DAYS = listOf("mo", "di", "mi", "do", "fr", "sa", "so")
    private val LINE = Regex("""^\s*([A-Za-zÄÖÜäöü]{2}(?:\s*[-/,+]\s*[A-Za-zÄÖÜäöü]{2})*)\s*:?\s+(?:(\d{1,2})[:.](\d{2})\s+)?(.+)$""")

    private fun days(spec: String): List<Int>? {
        val s = spec.lowercase().replace(" ", "")
        val out = mutableListOf<Int>()
        for (part in s.split(',', '/', '+')) {
            if ('-' in part) {
                val (a, b) = part.split('-').map { DAYS.indexOf(it.take(2)) }
                if (a < 0 || b < 0) return null
                var i = a; while (true) { out += i + 1; if (i == b) break; i = (i + 1) % 7 }
            } else {
                val i = DAYS.indexOf(part.take(2)); if (i < 0) return null; out += i + 1
            }
        }
        return out.distinct()
    }

    /** Colour by kind of entry (index into the app's colour list). */
    fun colorFor(title: String): Int {
        val t = title.lowercase()
        return when {
            "medikation" in t -> 2
            listOf("frühstück", "mittag", "abendessen", "kaffee").any { it in t } -> 3
            listOf("therap", "training", "entspannung", "gespräch", "visite", "pantomime", "gruppe").any { it in t } -> 0
            else -> 4
        }
    }

    /** Parsed items and the lines that could not be read. */
    fun parse(text: String): Pair<List<WeekItem>, List<String>> {
        val items = mutableListOf<WeekItem>(); val bad = mutableListOf<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val m = LINE.matchEntire(line); val d = m?.let { days(it.groupValues[1]) }
            if (m == null || d == null) { bad += line; return@forEach }
            val h = m.groupValues[2]; val min = m.groupValues[3]
            val time = if (h.isEmpty()) null else "${h.padStart(2, '0')}:$min"
            val title = m.groupValues[4].trim()
            d.forEach { wd -> items += WeekItem(weekday = wd, time = time, title = title, color = colorFor(title)) }
        }
        return items to bad
    }
}
