package app.devdeck.core

import app.devdeck.core.planner.Board
import app.devdeck.core.planner.CalendarEvent
import app.devdeck.core.planner.Deadline
import app.devdeck.core.planner.Habit
import app.devdeck.core.planner.PlannerData
import app.devdeck.core.planner.PlannerText
import app.devdeck.core.planner.Repeat
import app.devdeck.core.planner.Todo
import app.devdeck.core.planner.WeekItem
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerTest {
    private val today = LocalDate.parse("2026-11-17") // a Tuesday

    @Test fun repeatingEvents() {
        val weekly = CalendarEvent(title = "Team", date = "2026-11-03", repeat = Repeat.WEEKLY)
        assertTrue(weekly.occursOn(today)); assertFalse(weekly.occursOn(today.plusDays(1)))
        assertFalse(weekly.occursOn(LocalDate.parse("2026-10-27"))) // before start
        val monthly31 = CalendarEvent(title = "Miete", date = "2026-10-31", repeat = Repeat.MONTHLY)
        assertTrue(monthly31.occursOn(LocalDate.parse("2026-11-30")))   // moved to the last day
        assertTrue(monthly31.occursOn(LocalDate.parse("2027-02-28")))
        val birthday = CalendarEvent(title = "Geburtstag", date = "2000-02-29", repeat = Repeat.YEARLY)
        assertTrue(birthday.occursOn(LocalDate.parse("2027-02-28"))); assertTrue(birthday.occursOn(LocalDate.parse("2028-02-29")))
    }

    @Test fun upcomingAndTexts() {
        val p = PlannerData(
            events = listOf(
                CalendarEvent(title = "Zahnarzt", date = "2026-11-18", start = "09:30"),
                CalendarEvent(title = "Sport", date = "2026-11-17", start = "18:00", repeat = Repeat.DAILY),
                CalendarEvent(title = "Gelöscht", date = "2026-11-17", deleted = true),
            ),
            todos = listOf(Todo(title = "Steuer", priority = 2, due = "2026-11-20"), Todo(title = "Milch"), Todo(title = "Erledigt", done = true)),
            week = listOf(WeekItem(weekday = 2, time = "08:00", title = "Standup"), WeekItem(weekday = 3, title = "Mittwoch")),
            deadlines = listOf(Deadline(title = "Abgabe", date = "2026-11-27"), Deadline(title = "Uralt", date = "2026-01-01")),
        )
        val up = p.upcoming(today, limit = 3)
        assertEquals(listOf("Sport", "Zahnarzt", "Sport"), up.map { it.second.title })
        assertContains(PlannerText.upcoming(p, today), "Heute 18:00  Sport")
        assertContains(PlannerText.upcoming(p, today), "Morgen 09:30  Zahnarzt")
        assertEquals("‼ Steuer  (11-20)\n• Milch", PlannerText.todos(p))
        assertEquals("08:00  Standup", PlannerText.weekToday(p, today))
        assertEquals("Abgabe: noch 10 Tage", PlannerText.deadlines(p, today))
    }

    @Test fun habitStreaks() {
        var h = Habit(title = "Lesen", doneDates = setOf("2026-11-14", "2026-11-15", "2026-11-16"))
        assertEquals(3, h.streak(today))            // today not yet done: counts up to yesterday
        h = h.toggled(today)
        assertEquals(4, h.streak(today))
        assertEquals(0, h.toggled(today).toggled(today.minusDays(1)).streak(today))
    }

    @Test fun mergeKeepsNewestAndPropagatesDeletions() {
        val base = Todo(id = "t1", title = "Alt", updatedAt = 100)
        val phone = PlannerData(todos = listOf(base.copy(title = "Neu vom Handy", updatedAt = 200), Todo(id = "t2", title = "Nur Handy", updatedAt = 150)))
        val tablet = PlannerData(todos = listOf(base, Todo(id = "t3", title = "Nur Tablet", updatedAt = 120)),
            boards = listOf(Board(id = "b", name = "Projekt", updatedAt = 50)))
        val m = phone.merge(tablet)
        assertEquals(setOf("Neu vom Handy", "Nur Handy", "Nur Tablet"), m.todos.map { it.title }.toSet())
        assertEquals(1, m.boards.size)
        // deletion on the tablet later wins and stays as tombstone
        val del = m.merge(PlannerData(todos = listOf(base.copy(deleted = true, updatedAt = 300))))
        assertTrue(del.todos.first { it.id == "t1" }.deleted)
        assertFalse(del.openTodos().any { it.id == "t1" })
        assertEquals(2, del.pruned(nowMs = 300 + 91L * 24 * 3600 * 1000).todos.size)
        // round trip through JSON (what goes into the encrypted vault)
        val json = Json.encodeToString(PlannerData.serializer(), del)
        assertEquals(del, Json.decodeFromString(PlannerData.serializer(), json))
    }
}
