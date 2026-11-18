package app.devdeck.core

import app.devdeck.core.planner.*
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LifeTest {
    private val today = LocalDate.of(2026, 11, 17)

    @Test fun euroParsingAndFormatting() {
        assertEquals(1250, parseEuro("12,50"))
        assertEquals(1250, parseEuro("12.50 €"))
        assertEquals(123456, parseEuro("1.234,56"))
        assertNull(parseEuro("abc"))
        assertEquals("1.234,56 €", euro(123456))
        assertEquals("-0,05 €", euro(-5))
    }

    @Test fun monthlyExpensesAndBudget() {
        val l = LifeData(
            expenses = listOf(
                Expense(title = "Miete", cents = 80000, date = "2026-01-01", category = "Wohnen", monthly = true),
                Expense(title = "Essen", cents = 4550, date = "2026-11-03", category = "Lebensmittel"),
                Expense(title = "Altes", cents = 999, date = "2026-10-03"),
                Expense(title = "Gehalt", cents = 300000, date = "2026-11-01", income = true),
            ),
            budgets = listOf(Budget(monthlyCents = 100000)),
        )
        val m = YearMonth.of(2026, 11)
        assertEquals(84550, l.spent(m))
        assertEquals(300000, l.earned(m))
        assertEquals("Wohnen", l.byCategory(m).first().first)
        assertTrue(LifeText.budget(l, today).contains("Übrig: 154,50 €"))
    }

    @Test fun contractRenewalAndCancelDeadline() {
        val c = Contract(name = "Handy", cents = 2000, termEnd = "2025-12-31", renewMonths = 12, noticeDays = 30)
        assertEquals(LocalDate.of(2026, 12, 31), c.currentTermEnd(today))
        assertEquals(LocalDate.of(2026, 12, 1), c.cancelBy(today))
        val l = LifeData(contracts = listOf(c, Contract(name = "Jahr", cents = 12000, intervalMonths = 12)))
        assertEquals(2000 + 1000, l.contractsMonthlyCents())
        assertEquals(14L, l.cancelSoon(today).single().second)
    }

    @Test fun birthdaysIncludingLeapDayAndUnknownYear() {
        val a = Person(name = "Anna", birthday = "1990-11-18")
        val b = Person(name = "Ben", birthday = "--01-05")
        val leap = Person(name = "Lea", birthday = "2000-02-29")
        assertEquals(1, a.daysToBirthday(today)); assertEquals(36, a.turningAge(today))
        assertEquals(LocalDate.of(2027, 1, 5), b.nextBirthday(today)); assertNull(b.turningAge(today))
        assertEquals(LocalDate.of(2027, 2, 28), leap.nextBirthday(today))
        assertTrue(LifeText.birthdays(LifeData(people = listOf(a, b)), today).startsWith("Morgen: Anna wird 36"))
    }

    @Test fun choresRotate() {
        val c = Chore(title = "Bad", everyDays = 14, people = listOf("Justin", "Sam"))
        assertEquals(0, c.daysLeft(today))
        assertEquals("Justin", c.whoseTurn())
        val d = c.done(today)
        assertEquals("Sam", d.whoseTurn())
        assertEquals(14, d.daysLeft(today))
    }

    @Test fun shoppingSuggestionsAndDedup() {
        var l = LifeData(shopping = listOf(
            ShoppingItem(name = "Milch", done = true), ShoppingItem(name = "Milch", done = true), ShoppingItem(name = "Brot", done = true),
        ))
        assertEquals(listOf("Milch", "Brot"), l.suggestions())
        l = l.withShopping(listOf("Milch", "milch", " Eier "))
        assertEquals(listOf("Eier", "Milch"), l.openShopping().map { it.name })
        assertEquals(listOf("Brot"), l.suggestions())
    }

    @Test fun timeTrackingAndCsv() {
        val start = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() + 8 * 3_600_000
        val l = LifeData(time = listOf(TimeEntry(project = "App", note = "a \"b\"", start = start, end = start + 90 * 60_000)))
        assertEquals(90 * 60_000L, l.durationBetween(start - 1, start + 10 * 3_600_000))
        val csv = l.timeCsv(java.time.ZoneOffset.UTC)
        assertTrue(csv.contains("\"App\";\"a \"\"b\"\"\";2026-11-17 08:00;2026-11-17 09:30;90"), csv)
        assertEquals("1:30 h", formatDuration(90 * 60_000))
    }

    @Test fun medicationPantryJournal() {
        val m = Medication(name = "Vitamin D", times = listOf("20:00", "08:00"))
        val l = LifeData(
            medications = listOf(m.toggled(today, "08:00")),
            pantry = listOf(PantryItem(name = "Joghurt", expires = "2026-11-18"), PantryItem(name = "Reis")),
            journal = listOf(JournalEntry(date = "2025-11-17", mood = 5, text = "Toller Tag")),
            measurements = listOf(Measurement(kind = MeasureKind.WATER, value = "1", date = today.toString()),
                Measurement(kind = MeasureKind.WATER, value = "2", date = today.toString())),
        )
        assertEquals(listOf("08:00" to true, "20:00" to false), l.dosesOn(today).map { it.second to it.third })
        assertEquals(3, l.waterOn(today))
        assertEquals("Joghurt: morgen", LifeText.pantry(l, today))
        assertTrue(LifeText.journal(l, today).contains("Heute vor 1 J.: Toller Tag"))
    }

    @Test fun lifeMergesInsidePlannerAndBriefing() {
        val a = PlannerData(life = LifeData(shopping = listOf(ShoppingItem(id = "1", name = "Alt", updatedAt = 1))))
        val b = PlannerData(life = LifeData(shopping = listOf(ShoppingItem(id = "1", name = "Neu", updatedAt = 2)),
            chores = listOf(Chore(title = "Müll", everyDays = 7))))
        val m = a.merge(b)
        assertEquals("Neu", m.life.shopping.single().name)
        assertTrue(LifeText.briefing(m, today).contains("🧹 Müll"))
        // old JSON without "life" still loads
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(PlannerData.serializer(), "{\"todos\":[]}")
        assertEquals(LifeData(), old.life)
    }
}

class WeekPlanImportTest {
    @Test fun parsesDaysRangesAndTimes() {
        val (items, bad) = WeekPlanImport.parse("""
            # Kommentar
            Mo-Fr 07:10 Frühsport (bis 07:30)
            Sa/So 7.20 Frühstück
            Di Ausflug
            Mi 08:00 Medikation
            Quatsch ohne Tag
        """.trimIndent())
        assertEquals(listOf("Quatsch ohne Tag"), bad)
        assertEquals(5 + 2 + 1 + 1, items.size)
        assertEquals((1..5).toList(), items.filter { it.title.startsWith("Frühsport") }.map { it.weekday })
        assertEquals(listOf("07:20", "07:20"), items.filter { it.title == "Frühstück" }.map { it.time })
        assertEquals(null, items.single { it.title == "Ausflug" }.time)
        assertEquals(2, items.single { it.title == "Medikation" }.color)
    }

    @Test fun includedTherapyPlanHasNo2bEntries() {
        // Personal plan file (not in git) – only checked when present.
        val f = java.io.File("../wochenplan-gruppe2a.txt")
        if (!f.exists()) return
        val text = f.readText()
        val (items, bad) = WeekPlanImport.parse(text)
        assertTrue(bad.isEmpty(), bad.toString())
        assertTrue(items.none { "2B" in it.title })
        assertTrue(items.size > 70, "${items.size}")
    }
}

class SleepTest {
    private val today = java.time.LocalDate.of(2026, 11, 18)
    @Test fun durationsOverMidnightAndAverages() {
        val a = SleepEntry(date = "2026-11-18", bed = "23:00", wake = "06:30", latency = 30)
        assertEquals(450, a.inBedMinutes()); assertEquals(420, a.sleepMinutes())
        val b = SleepEntry(date = "2026-11-17", bed = "00:30", wake = "07:00")
        assertEquals(390, b.inBedMinutes())
        val l = LifeData(sleep = listOf(a, b, SleepEntry(date = "2026-11-16", bed = "22:30", wake = "06:30")))
        assertEquals((420 + 390 + 480) / 3L, l.sleepAverage(today, 7))
        assertEquals(a.id, l.lastSleep()!!.id)
        assertTrue(l.bedtimeSpread(today)!! in 30..50, "${l.bedtimeSpread(today)}")
        assertTrue(LifeText.sleep(l, today).startsWith("Letzte Nacht: 7:00 h"))
        assertTrue(SLEEP_TIPS.size >= 18)
    }
}
