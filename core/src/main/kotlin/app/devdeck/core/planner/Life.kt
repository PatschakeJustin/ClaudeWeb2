package app.devdeck.core.planner

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.MonthDay
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

// =====================================================================================================
// Alltags-Module. Same sync rules as the planner: per item newest change wins, deletions are tombstones.
// Amounts are stored in cents to avoid rounding errors.
// =====================================================================================================

/** 1 – Einkaufsliste */
@Serializable
data class ShoppingItem(
    override val id: String = newPlannerId(),
    val name: String,
    val amount: String = "",
    /** Laden / Liste, e.g. "Supermarkt", "Drogerie". */
    val shop: String = "Allgemein",
    val done: Boolean = false,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** 2 – Haushaltsbuch */
@Serializable
data class Expense(
    override val id: String = newPlannerId(),
    val title: String,
    val cents: Long,
    val date: String,
    val category: String = "Sonstiges",
    val income: Boolean = false,
    /** Repeats every month from [date] on (Abo, Miete, Gehalt …). */
    val monthly: Boolean = false,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    /** Does this entry count in [month]? */
    fun countsIn(month: YearMonth): Boolean {
        val d = YearMonth.from(LocalDate.parse(date))
        return if (monthly) !month.isBefore(d) else d == month
    }
}

@Serializable
data class Budget(
    override val id: String = newPlannerId(),
    /** "" = Gesamtbudget */
    val category: String = "",
    val monthlyCents: Long,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** 3 – Rezepte & Essensplan */
@Serializable
data class Recipe(
    override val id: String = newPlannerId(),
    val title: String,
    val servings: Int = 2,
    /** One ingredient per entry, e.g. "200 g Nudeln". */
    val ingredients: List<String> = emptyList(),
    val steps: String = "",
    val tags: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

enum class Meal(val label: String) { BREAKFAST("Frühstück"), LUNCH("Mittag"), DINNER("Abend") }

@Serializable
data class MealPlan(
    override val id: String = newPlannerId(),
    val date: String,
    val meal: Meal = Meal.DINNER,
    val recipeId: String? = null,
    /** Free text when no recipe is linked. */
    val title: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** 4 – Vorräte */
@Serializable
data class PantryItem(
    override val id: String = newPlannerId(),
    val name: String,
    val amount: String = "",
    val place: String = "Kühlschrank",
    val expires: String? = null,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun daysLeft(today: LocalDate): Long? = expires?.let { ChronoUnit.DAYS.between(today, LocalDate.parse(it)) }
}

/** 5 – Verträge & Dokumente */
@Serializable
data class Contract(
    override val id: String = newPlannerId(),
    val name: String,
    val provider: String = "",
    val category: String = "Sonstiges",
    val cents: Long = 0,
    /** Payment interval in months (1 = monatlich, 12 = jährlich). */
    val intervalMonths: Int = 1,
    /** End of the current term; null = unbefristet / jederzeit kündbar. */
    val termEnd: String? = null,
    /** Automatic renewal in months after [termEnd] (0 = ends). */
    val renewMonths: Int = 12,
    val noticeDays: Int = 30,
    /** Path of the contract document (Nextcloud / WebDAV) or a link. */
    val document: String = "",
    val note: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    /** The current term end, rolled forward by automatic renewals. */
    fun currentTermEnd(today: LocalDate): LocalDate? {
        var end = termEnd?.let { LocalDate.parse(it) } ?: return null
        if (renewMonths <= 0) return end
        while (end.isBefore(today)) end = end.plusMonths(renewMonths.toLong())
        return end
    }

    /** Last day to cancel for the current term. */
    fun cancelBy(today: LocalDate): LocalDate? = currentTermEnd(today)?.minusDays(noticeDays.toLong())

    /** Cost per month in cents. */
    fun monthlyCents(): Long = if (intervalMonths <= 0) cents else cents / intervalMonths
}

/** 6 – Gesundheit */
@Serializable
data class Medication(
    override val id: String = newPlannerId(),
    val name: String,
    val dose: String = "",
    /** Times "HH:mm". */
    val times: List<String> = listOf("08:00"),
    /** Taken doses as "yyyy-MM-dd HH:mm" (time = scheduled time). */
    val taken: Set<String> = emptySet(),
    val active: Boolean = true,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun isTaken(day: LocalDate, time: String) = "$day $time" in taken
    fun toggled(day: LocalDate, time: String) = copy(
        taken = ("$day $time").let { if (it in taken) taken - it else taken + it }
            // keep 60 days of history
            .filter { it.take(10) >= day.minusDays(60).toString() }.toSet(),
        updatedAt = now(),
    )
}

enum class MeasureKind(val label: String, val unit: String) {
    WEIGHT("Gewicht", "kg"), BLOOD_PRESSURE("Blutdruck", "mmHg"), PULSE("Puls", "bpm"),
    SUGAR("Blutzucker", "mg/dl"), SLEEP("Schlaf", "h"), WATER("Wasser", "Glas"),
}

@Serializable
data class Measurement(
    override val id: String = newPlannerId(),
    val kind: MeasureKind,
    /** e.g. "82.4" or "120/80". */
    val value: String,
    val date: String,
    val time: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** 7 – Zeiterfassung */
@Serializable
data class TimeEntry(
    override val id: String = newPlannerId(),
    val project: String,
    val note: String = "",
    val start: Long,
    /** null while running. */
    val end: Long? = null,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun durationMs(nowMs: Long = now()) = (end ?: nowMs) - start
    val running get() = end == null
}

/** 8 – Geburtstage & Kontakte */
@Serializable
data class Person(
    override val id: String = newPlannerId(),
    val name: String,
    /** "yyyy-MM-dd" or "--MM-dd" when the year is unknown. */
    val birthday: String? = null,
    val gifts: String = "",
    val note: String = "",
    val lastContact: String? = null,
    /** Remind after this many days without contact (0 = off). */
    val contactEveryDays: Int = 0,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    private fun monthDay(): MonthDay? = birthday?.let {
        runCatching { if (it.startsWith("--")) MonthDay.parse(it) else MonthDay.from(LocalDate.parse(it)) }.getOrNull()
    }

    /** Next birthday on/after [today]. */
    fun nextBirthday(today: LocalDate): LocalDate? {
        val md = monthDay() ?: return null
        var y = today.year
        fun at(year: Int) = if (md.isValidYear(year)) md.atYear(year) else LocalDate.of(year, 2, 28)
        if (at(y).isBefore(today)) y++
        return at(y)
    }

    fun daysToBirthday(today: LocalDate) = nextBirthday(today)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Age reached on the next birthday (null if the year is unknown). */
    fun turningAge(today: LocalDate): Int? {
        val b = birthday?.takeUnless { it.startsWith("--") }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return nextBirthday(today)?.year?.minus(b.year)
    }

    fun contactOverdue(today: LocalDate): Boolean = contactEveryDays > 0 &&
        (lastContact == null || ChronoUnit.DAYS.between(LocalDate.parse(lastContact), today) >= contactEveryDays)
}

/** 9 – Haushalt & Putzplan */
@Serializable
data class Chore(
    override val id: String = newPlannerId(),
    val title: String,
    val everyDays: Int = 7,
    val lastDone: String? = null,
    /** People taking turns; empty = nobody assigned. */
    val people: List<String> = emptyList(),
    val turn: Int = 0,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    fun nextDue(today: LocalDate): LocalDate = lastDone?.let { LocalDate.parse(it).plusDays(everyDays.toLong()) } ?: today
    fun daysLeft(today: LocalDate) = ChronoUnit.DAYS.between(today, nextDue(today))
    fun whoseTurn(): String? = people.takeIf { it.isNotEmpty() }?.let { it[turn.mod(it.size)] }
    /** Marks it done today and passes it on to the next person. */
    fun done(today: LocalDate) = copy(lastDone = today.toString(), turn = if (people.isEmpty()) 0 else (turn + 1).mod(people.size), updatedAt = now())
}

/** 10 – Tagebuch */
@Serializable
data class JournalEntry(
    override val id: String = newPlannerId(),
    val date: String,
    /** 1 (schlecht) … 5 (super) */
    val mood: Int = 3,
    val text: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** 11 – Gebetszeiten */
@Serializable
data class PrayerConfig(
    val place: app.devdeck.core.prayer.PrayerPlace? = null,
    val method: app.devdeck.core.prayer.PrayerMethod = app.devdeck.core.prayer.PrayerMethod.DIYANET,
    val asr: app.devdeck.core.prayer.AsrMethod = app.devdeck.core.prayer.AsrMethod.STANDARD,
    /** Prayers with a notification. */
    val notify: Set<app.devdeck.core.prayer.Prayer> = emptySet(),
    val updatedAt: Long = now(),
) {
    fun times(day: LocalDate) = place?.let { app.devdeck.core.prayer.PrayerCalc.times(day, it, method, asr) }
}

/** Browser bookmark (synced with the account). */
@Serializable
data class Bookmark(
    override val id: String = newPlannerId(),
    val title: String,
    val url: String,
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem

/** Turns what was typed into the address bar into a URL: a web address as is, anything else as a search. */
object AddressBar {
    enum class Engine(val label: String, val pattern: String) {
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        GOOGLE("Google", "https://www.google.com/search?q=%s"),
        STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s"),
        ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s"),
    }

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
    private val HOST = Regex("^(localhost|\\d{1,3}(\\.\\d{1,3}){3}|[\\p{L}0-9-]+(\\.[\\p{L}0-9-]+)*\\.[\\p{L}]{2,})(:\\d+)?(/.*)?$")

    fun toUrl(input: String, engine: Engine = Engine.DUCKDUCKGO): String {
        val t = input.trim()
        if (t.isEmpty()) return "about:blank"
        if (SCHEME.containsMatchIn(t) || t.startsWith("about:")) return t
        if (!t.contains(' ') && HOST.matches(t)) {
            val local = t.startsWith("localhost") || Regex("^(10|127|192\\.168|172\\.(1[6-9]|2\\d|3[01]))\\.").containsMatchIn(t)
            return (if (local) "http://" else "https://") + t
        }
        return engine.pattern.format(java.net.URLEncoder.encode(t, "UTF-8"))
    }
}

/** 12 – Schlaf: one entry per night, filed under the date of waking up. */
@Serializable
data class SleepEntry(
    override val id: String = newPlannerId(),
    /** Date of the morning (yyyy-MM-dd). */
    val date: String,
    /** "HH:mm" */
    val bed: String,
    val wake: String,
    /** Minutes until falling asleep (estimate). */
    val latency: Int = 0,
    val wakeups: Int = 0,
    /** 1 (schlecht) … 5 (sehr gut) */
    val quality: Int = 3,
    /** Evening routine items that were kept (ids from SLEEP_CHECKS). */
    val checks: Set<String> = emptySet(),
    val note: String = "",
    override val updatedAt: Long = now(),
    override val deleted: Boolean = false,
) : PlannerItem {
    /** Time in bed in minutes (over midnight). */
    fun inBedMinutes(): Int {
        val b = java.time.LocalTime.parse(bed); val w = java.time.LocalTime.parse(wake)
        val m = java.time.Duration.between(b, w).toMinutes().toInt()
        return if (m <= 0) m + 24 * 60 else m
    }
    /** Estimated sleep: time in bed minus time to fall asleep. */
    fun sleepMinutes() = (inBedMinutes() - latency).coerceAtLeast(0)
}

@Serializable
data class SleepConfig(
    val targetBed: String = "22:30",
    val targetWake: String = "06:30",
    /** No caffeine after this time. */
    val caffeineCutoff: String = "15:00",
    /** Reminder to wind down this many minutes before bedtime (0 = off). */
    val windDownMinutes: Int = 60,
    val updatedAt: Long = now(),
)

/** Evening routine checklist (own wording of common sleep-hygiene advice). */
val SLEEP_CHECKS = listOf(
    "regular" to "Zu meiner festen Zeit ins Bett / aufgestanden",
    "caffeine" to "Nachmittags/abends kein Kaffee, Cola, Energy oder schwarzer Tee",
    "alcohol" to "Kein Alkohol als Einschlafhilfe",
    "meal" to "Weder hungrig noch übervoll ins Bett",
    "drink" to "Abends nur noch wenig getrunken",
    "air" to "Schlafzimmer gelüftet, angenehm kühl und ruhig",
    "winddown" to "Ruhiger Abend / Abendritual",
    "nonap" to "Nicht vor dem Fernseher eingenickt",
    "bedonly" to "Bett nur zum Schlafen (kein Handy, TV, Lesen im Bett)",
    "notes" to "Gedanken für morgen aufgeschrieben",
)

/** Short tips, one per day (paraphrased sleep-hygiene rules). */
val SLEEP_TIPS = listOf(
    "Halte deine Aktivitäten bei – Treffen, Ausgehen, Hobbys. Richte den Tag nicht nach dem schlechten Schlaf aus.",
    "Feste Zeiten fürs Zubettgehen und Aufstehen stabilisieren deinen Schlaf-Wach-Rhythmus – auch am Wochenende.",
    "Geh ins Bett, wenn du müde bist – nicht nur, weil die Uhr es sagt.",
    "Kannst du nicht einschlafen? Steh lieber auf und mach etwas Ruhiges. Erst wenn du wieder müde wirst, zurück ins Bett.",
    "Nicht vor dem Fernseher einnicken – das baut den Schlafdruck ab, der dir später beim Einschlafen fehlt.",
    "Das Bett ist zum Schlafen da. Lesen, Radio, Fernsehen und Handy besser woanders.",
    "Lüfte das Schlafzimmer vor dem Zubettgehen gut durch.",
    "Angenehme Bedingungen: nicht zu warm, nicht zu kalt, ruhig und eine gute Matratze.",
    "Eine volle Blase weckt nachts am häufigsten. Trink abends weniger – aber nicht gar nichts, sonst weckt dich der Durst.",
    "Weder hungrig noch mit übervollem Magen ins Bett gehen.",
    "Kaffee, Cola, Energy Drinks und schwarzer Tee können am Abend wach halten – achte darauf, wie sie bei dir wirken.",
    "Alkohol lässt schneller einschlafen, aber du wachst öfter auf und schläfst flacher. Kein Alkohol als Schlafmittel.",
    "Gestalte den Abend entspannt, um vom Tag abzuschalten.",
    "Feste Rituale helfen: ein kurzer Spaziergang, eine ruhige Viertelstunde auf dem Balkon, Lesen im Sessel.",
    "Belastendes vom Tag nicht im Bett durchgehen – lieber vorher mit geschlossenen Augen im Sessel.",
    "Leichte Bewegung und Entspannungsübungen (z. B. autogenes Training, Atemübungen) erleichtern das Einschlafen.",
    "Ein warmes Bad, eine Wechseldusche oder ein warmes Fußbad können beim Umschalten in den Schlaf helfen.",
    "Grübelst du nachts über Aufgaben? Schreib sie auf – dann darf der Kopf sie loslassen.",
)

val MOODS = listOf("😞", "🙁", "😐", "🙂", "😄")

@Serializable
data class LifeData(
    val shopping: List<ShoppingItem> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val budgets: List<Budget> = emptyList(),
    val recipes: List<Recipe> = emptyList(),
    val meals: List<MealPlan> = emptyList(),
    val pantry: List<PantryItem> = emptyList(),
    val contracts: List<Contract> = emptyList(),
    val medications: List<Medication> = emptyList(),
    val measurements: List<Measurement> = emptyList(),
    val time: List<TimeEntry> = emptyList(),
    val people: List<Person> = emptyList(),
    val chores: List<Chore> = emptyList(),
    val journal: List<JournalEntry> = emptyList(),
    val prayer: PrayerConfig? = null,
    val sleep: List<SleepEntry> = emptyList(),
    val sleepConfig: SleepConfig? = null,
    val bookmarks: List<Bookmark> = emptyList(),
) {
    fun merge(o: LifeData) = LifeData(
        mergeLists(shopping, o.shopping), mergeLists(expenses, o.expenses), mergeLists(budgets, o.budgets),
        mergeLists(recipes, o.recipes), mergeLists(meals, o.meals), mergeLists(pantry, o.pantry),
        mergeLists(contracts, o.contracts), mergeLists(medications, o.medications), mergeLists(measurements, o.measurements),
        mergeLists(time, o.time), mergeLists(people, o.people), mergeLists(chores, o.chores), mergeLists(journal, o.journal),
        prayer = listOfNotNull(prayer, o.prayer).maxByOrNull { it.updatedAt },
        sleep = mergeLists(sleep, o.sleep),
        sleepConfig = listOfNotNull(sleepConfig, o.sleepConfig).maxByOrNull { it.updatedAt },
        bookmarks = mergeLists(bookmarks, o.bookmarks),
    )

    fun pruned(nowMs: Long, maxAgeMs: Long): LifeData {
        fun <T : PlannerItem> List<T>.p() = filter { !it.deleted || nowMs - it.updatedAt < maxAgeMs }
        return LifeData(shopping.p(), expenses.p(), budgets.p(), recipes.p(), meals.p(), pantry.p(), contracts.p(),
            medications.p(), measurements.p(), time.p(), people.p(), chores.p(), journal.p(), prayer, sleep.p(), sleepConfig, bookmarks.p())
    }

    // ---- 1 Einkauf
    fun openShopping() = shopping.filter { !it.deleted && !it.done }.sortedWith(compareBy({ it.shop }, { it.name.lowercase() }))
    fun shops() = shopping.filter { !it.deleted }.map { it.shop }.distinct().sorted()

    /** Frequently bought items that are not on the list right now. */
    fun suggestions(limit: Int = 8): List<String> {
        val open = openShopping().map { it.name.lowercase() }.toSet()
        return shopping.filter { it.done || it.deleted }.groupingBy { it.name.trim() }.eachCount()
            .filterKeys { it.isNotBlank() && it.lowercase() !in open }.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** Adds items (merging with open entries of the same name). */
    fun withShopping(names: List<String>, shop: String = "Allgemein"): LifeData {
        var list = shopping
        names.map { it.trim() }.filter { it.isNotEmpty() }.forEach { n ->
            if (list.none { !it.deleted && !it.done && it.name.equals(n, true) }) list = list + ShoppingItem(name = n, shop = shop)
        }
        return copy(shopping = list)
    }

    // ---- 2 Ausgaben
    fun entriesIn(month: YearMonth) = expenses.filter { !it.deleted && it.countsIn(month) }
    fun spent(month: YearMonth, category: String? = null) =
        entriesIn(month).filter { !it.income && (category == null || it.category == category) }.sumOf { it.cents }
    fun earned(month: YearMonth) = entriesIn(month).filter { it.income }.sumOf { it.cents }
    fun byCategory(month: YearMonth) = entriesIn(month).filter { !it.income }.groupBy { it.category }
        .mapValues { (_, v) -> v.sumOf { it.cents } }.entries.sortedByDescending { it.value }.map { it.key to it.value }
    fun totalBudget() = budgets.firstOrNull { !it.deleted && it.category.isBlank() }?.monthlyCents
    fun categoryBudget(c: String) = budgets.firstOrNull { !it.deleted && it.category == c }?.monthlyCents

    // ---- 3 Rezepte
    fun activeRecipes() = recipes.filter { !it.deleted }.sortedBy { it.title.lowercase() }
    fun recipe(id: String?) = recipes.firstOrNull { it.id == id && !it.deleted }
    fun mealsOn(day: LocalDate) = meals.filter { !it.deleted && it.date == day.toString() }.sortedBy { it.meal.ordinal }
    fun mealTitle(m: MealPlan) = recipe(m.recipeId)?.title ?: m.title

    // ---- 4 Vorräte
    fun activePantry(today: LocalDate) = pantry.filter { !it.deleted }
        .sortedWith(compareBy<PantryItem> { it.daysLeft(today) ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
    fun expiringSoon(today: LocalDate, days: Long = 3) = activePantry(today).filter { (it.daysLeft(today) ?: Long.MAX_VALUE) <= days }

    // ---- 5 Verträge
    fun activeContracts() = contracts.filter { !it.deleted }
    fun contractsMonthlyCents() = activeContracts().sumOf { it.monthlyCents() }
    fun cancelSoon(today: LocalDate, days: Long = 30) = activeContracts()
        .mapNotNull { c -> c.cancelBy(today)?.let { c to ChronoUnit.DAYS.between(today, it) } }
        .filter { it.second in 0..days }.sortedBy { it.second }

    // ---- 6 Gesundheit
    fun activeMeds() = medications.filter { !it.deleted && it.active }
    /** Doses of today as (medication, time, taken). */
    fun dosesOn(day: LocalDate) = activeMeds().flatMap { m -> m.times.map { t -> Triple(m, t, m.isTaken(day, t)) } }.sortedBy { it.second }
    fun measurementsOf(kind: MeasureKind) = measurements.filter { !it.deleted && it.kind == kind }.sortedWith(compareBy({ it.date }, { it.time }))
    fun waterOn(day: LocalDate) = measurements.filter { !it.deleted && it.kind == MeasureKind.WATER && it.date == day.toString() }
        .sumOf { it.value.toIntOrNull() ?: 1 }

    // ---- 7 Zeit
    fun runningTimer() = time.firstOrNull { !it.deleted && it.running }
    fun timeEntries() = time.filter { !it.deleted }.sortedByDescending { it.start }
    fun projects() = time.filter { !it.deleted }.map { it.project }.distinct()
    fun durationBetween(fromMs: Long, toMs: Long, project: String? = null, nowMs: Long = now()) =
        time.filter { !it.deleted && (project == null || it.project == project) }.sumOf { e ->
            val s = maxOf(e.start, fromMs); val en = minOf(e.end ?: nowMs, toMs)
            if (en > s) en - s else 0L
        }

    /** CSV export (Semikolon-getrennt, für Excel). */
    fun timeCsv(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
        return "Projekt;Notiz;Start;Ende;Minuten\n" + timeEntries().reversed().joinToString("\n") { e ->
            listOf(q(e.project), q(e.note), f.format(java.time.Instant.ofEpochMilli(e.start).atZone(zone)),
                e.end?.let { f.format(java.time.Instant.ofEpochMilli(it).atZone(zone)) } ?: "läuft",
                (e.durationMs() / 60_000).toString()).joinToString(";")
        }
    }

    // ---- 8 Personen
    fun activePeople() = people.filter { !it.deleted }.sortedBy { it.name.lowercase() }
    fun upcomingBirthdays(today: LocalDate, days: Long = 30) = activePeople()
        .mapNotNull { p -> p.daysToBirthday(today)?.let { p to it } }.filter { it.second <= days }.sortedBy { it.second }

    // ---- 9 Haushalt
    fun activeChores(today: LocalDate) = chores.filter { !it.deleted }.sortedBy { it.daysLeft(today) }
    fun choresDue(today: LocalDate) = activeChores(today).filter { it.daysLeft(today) <= 0 }

    // ---- 12 Schlaf
    fun activeSleep() = sleep.filter { !it.deleted }.sortedByDescending { it.date }
    fun lastSleep() = activeSleep().firstOrNull()
    fun sleepOn(day: LocalDate) = sleep.firstOrNull { !it.deleted && it.date == day.toString() }
    /** Average sleep minutes over the last [days] nights, null if none. */
    fun sleepAverage(today: LocalDate, days: Long) = activeSleep().filter { LocalDate.parse(it.date) > today.minusDays(days) }
        .takeIf { it.isNotEmpty() }?.map { it.sleepMinutes() }?.average()?.toLong()
    /** How much the bedtime varies (standard deviation in minutes) over the last 14 nights; smaller = more regular. */
    fun bedtimeSpread(today: LocalDate): Int? {
        val mins = activeSleep().filter { LocalDate.parse(it.date) > today.minusDays(14) }.map {
            val t = java.time.LocalTime.parse(it.bed); val m = t.hour * 60 + t.minute
            if (m < 12 * 60) m + 24 * 60 else m // after midnight counts as late evening
        }
        if (mins.size < 3) return null
        val mean = mins.average()
        return kotlin.math.sqrt(mins.map { (it - mean) * (it - mean) }.average()).toInt()
    }

    // ---- 10 Tagebuch
    fun journalOn(day: LocalDate) = journal.firstOrNull { !it.deleted && it.date == day.toString() }
    fun activeJournal() = journal.filter { !it.deleted }.sortedByDescending { it.date }
    /** Entries from the same day in earlier years. */
    fun onThisDay(today: LocalDate) = activeJournal().filter { e ->
        val d = LocalDate.parse(e.date); d.year < today.year && d.monthValue == today.monthValue && d.dayOfMonth == today.dayOfMonth
    }
}

fun euro(cents: Long): String {
    val neg = cents < 0; val c = kotlin.math.abs(cents)
    val whole = String.format(Locale.GERMANY, "%,d", c / 100)
    return (if (neg) "-" else "") + whole + "," + (c % 100).toString().padStart(2, '0') + " €"
}

/** Parses "12,50", "12.50" or "1.234,56" into cents. */
fun parseEuro(s: String): Long? {
    val t = s.trim().replace("€", "").replace(" ", "")
    if (t.isEmpty()) return null
    val normalized = if (t.contains(',')) t.replace(".", "").replace(',', '.') else t
    return normalized.toBigDecimalOrNull()?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toLong()
}

fun formatDuration(ms: Long): String { val m = ms / 60_000; return "${m / 60}:${(m % 60).toString().padStart(2, '0')} h" }

/** Widget / briefing texts of the everyday modules. */
object LifeText {
    fun shopping(l: LifeData, limit: Int = 9): String {
        val open = l.openShopping()
        if (open.isEmpty()) return "Einkaufsliste ist leer ✓"
        return open.take(limit).joinToString("\n") { "• ${it.name}" + if (it.amount.isNotBlank()) " (${it.amount})" else "" } +
            if (open.size > limit) "\n… +${open.size - limit}" else ""
    }

    fun budget(l: LifeData, today: LocalDate): String {
        val m = YearMonth.from(today)
        val spent = l.spent(m)
        val budget = l.totalBudget()
        return buildString {
            append("Ausgaben ${m.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.GERMANY)}: ${euro(spent)}")
            if (budget != null) {
                val left = budget - spent
                append("\n${if (left >= 0) "Übrig" else "Überzogen"}: ${euro(kotlin.math.abs(left))} von ${euro(budget)}")
                val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
                if (left > 0) append("\n≈ ${euro(left / daysLeft)} pro Tag")
            }
            l.byCategory(m).take(3).forEach { (c, v) -> append("\n$c: ${euro(v)}") }
        }
    }

    fun meals(l: LifeData, today: LocalDate): String =
        (0..1).flatMap { off -> val d = today.plusDays(off.toLong()); l.mealsOn(d).map { (if (off == 0) "Heute" else "Morgen") + " ${it.meal.label}: ${l.mealTitle(it)}" } }
            .joinToString("\n").ifEmpty { "Noch nichts geplant" }

    fun pantry(l: LifeData, today: LocalDate): String =
        l.expiringSoon(today, 5).joinToString("\n") { p ->
            val n = p.daysLeft(today)!!
            "${p.name}: " + when { n < 0 -> "abgelaufen"; n == 0L -> "heute"; n == 1L -> "morgen"; else -> "in $n Tagen" }
        }.ifEmpty { "Nichts läuft bald ab" }

    fun contracts(l: LifeData, today: LocalDate): String = buildString {
        append("Fixkosten: ${euro(l.contractsMonthlyCents())} / Monat")
        l.cancelSoon(today, 45).take(4).forEach { (c, d) -> append("\n⚠ ${c.name}: kündbar noch $d Tage") }
    }

    fun health(l: LifeData, today: LocalDate): String {
        val doses = l.dosesOn(today)
        val lines = doses.map { (m, t, done) -> "${if (done) "✓" else "○"} $t ${m.name}" + if (m.dose.isNotBlank()) " (${m.dose})" else "" }
        return (lines + "💧 ${l.waterOn(today)} Gläser Wasser").joinToString("\n")
    }

    fun time(l: LifeData, today: LocalDate, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = today.minusDays((weekdayOf(today) - 1).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val run = l.runningTimer()
        return (run?.let { "▶ ${it.project}: ${formatDuration(it.durationMs())}\n" } ?: "⏸ Kein Timer\n") +
            "Heute: ${formatDuration(l.durationBetween(dayStart, dayStart + 86_400_000))}\n" +
            "Woche: ${formatDuration(l.durationBetween(weekStart, now()))}"
    }

    fun birthdays(l: LifeData, today: LocalDate): String {
        val b = l.upcomingBirthdays(today, 30).take(5).map { (p, d) ->
            val age = p.turningAge(today)?.let { " wird $it" } ?: ""
            when (d) { 0L -> "🎂 Heute: ${p.name}$age"; 1L -> "Morgen: ${p.name}$age"; else -> "in $d T: ${p.name}$age" }
        }
        val c = l.activePeople().filter { it.contactOverdue(today) }.take(3).map { "☎ ${it.name} mal melden" }
        return (b + c).joinToString("\n").ifEmpty { "Keine Geburtstage in den nächsten 30 Tagen" }
    }

    fun chores(l: LifeData, today: LocalDate): String =
        l.activeChores(today).take(6).joinToString("\n") { c ->
            val d = c.daysLeft(today)
            val who = c.whoseTurn()?.let { " – $it" } ?: ""
            (if (d <= 0) "❗ " else "") + c.title + who + when { d < 0 -> " (seit ${-d} T)"; d == 0L -> " (heute)"; else -> " (in $d T)" }
        }.ifEmpty { "Keine Aufgaben angelegt" }

    fun journal(l: LifeData, today: LocalDate): String {
        val t = l.journalOn(today)
        val head = t?.let { "${MOODS[(it.mood - 1).coerceIn(0, 4)]} ${it.text.take(120)}" } ?: "Heute noch kein Eintrag"
        val past = l.onThisDay(today).firstOrNull()?.let { "\nHeute vor ${today.year - LocalDate.parse(it.date).year} J.: ${it.text.take(80)}" } ?: ""
        return head + past
    }

    fun prayer(l: LifeData, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
        val cfg = l.prayer ?: return "Ort für Gebetszeiten wählen"
        val place = cfg.place ?: return "Ort für Gebetszeiten wählen"
        val times = cfg.times(now.toLocalDate())!!
        val (next, at) = app.devdeck.core.prayer.PrayerCalc.next(now, place, cfg.method, cfg.asr)
        val mins = java.time.Duration.between(now, at).toMinutes()
        val f = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        return "${place.name.substringBefore(',')} · nächstes: ${next.label} ${at.format(f)} (in ${mins / 60}:${(mins % 60).toString().padStart(2, '0')})\n" +
            times.entries.joinToString("\n") { (p, t) -> (if (p == next && t == at) "▶ " else "   ") + "${p.label}  ${t.format(f)}" }
    }

    fun sleepTip(today: LocalDate) = SLEEP_TIPS[(today.toEpochDay() % SLEEP_TIPS.size).toInt()]

    fun sleep(l: LifeData, today: LocalDate): String {
        val last = l.lastSleep()
        val avg = l.sleepAverage(today, 7)
        val cfg = l.sleepConfig ?: SleepConfig()
        return buildString {
            append(last?.let { "Letzte Nacht: ${formatDuration(it.sleepMinutes() * 60_000L)} · ${MOODS[(it.quality - 1).coerceIn(0, 4)]}" } ?: "Noch keine Nacht eingetragen")
            avg?.let { append("\nØ 7 Tage: ${formatDuration(it * 60_000L)}") }
            append("\nZiel: ${cfg.targetBed} – ${cfg.targetWake}")
            append("\n💡 ").append(sleepTip(today))
        }
    }

    /** Morning briefing over everything. */
    fun briefing(p: PlannerData, today: LocalDate): String {
        val l = p.life
        val parts = mutableListOf<String>()
        val ev = p.eventsOn(today)
        parts += if (ev.isEmpty()) "📅 Keine Termine" else "📅 " + ev.joinToString(", ") { (it.start?.let { s -> "$s " } ?: "") + it.title }
        p.openTodos().let { if (it.isNotEmpty()) parts += "✅ ${it.size} Todos offen, zuerst: ${it.first().title}" }
        p.activeDeadlines(today).filter { it.daysLeft(today) in 0..3 }.forEach { parts += "⏳ ${it.title} in ${it.daysLeft(today)} T" }
        l.upcomingBirthdays(today, 1).forEach { (pp, d) -> parts += if (d == 0L) "🎂 ${pp.name} hat Geburtstag" else "🎂 Morgen: ${pp.name}" }
        l.choresDue(today).take(3).forEach { parts += "🧹 ${it.title}" + (it.whoseTurn()?.let { w -> " ($w)" } ?: "") }
        l.dosesOn(today).filter { !it.third }.take(3).forEach { parts += "💊 ${it.second} ${it.first.name}" }
        l.expiringSoon(today, 1).take(3).forEach { parts += "🥫 ${it.name} läuft ab" }
        l.cancelSoon(today, 14).take(2).forEach { (c, d) -> parts += "📄 ${c.name}: noch $d T kündbar" }
        l.mealsOn(today).lastOrNull()?.let { parts += "🍽 ${l.mealTitle(it)}" }
        l.sleepOn(today)?.let { parts += "😴 Geschlafen: ${formatDuration(it.sleepMinutes() * 60_000L)}" }
        l.sleepConfig?.let { parts += "🛏 Heute ins Bett: ${it.targetBed}" }
        l.totalBudget()?.let { b -> parts += "💶 Budget übrig: ${euro(b - l.spent(YearMonth.from(today)))}" }
        l.prayer?.times(today)?.let { t ->
            val f = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            parts += "🕌 " + listOf(app.devdeck.core.prayer.Prayer.FAJR, app.devdeck.core.prayer.Prayer.DHUHR, app.devdeck.core.prayer.Prayer.ASR,
                app.devdeck.core.prayer.Prayer.MAGHRIB, app.devdeck.core.prayer.Prayer.ISHA).joinToString(" · ") { "${it.label} ${t.getValue(it).format(f)}" }
        }
        return parts.joinToString("\n")
    }
}
