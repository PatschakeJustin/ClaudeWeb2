package app.devdeck.widget

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.devdeck.DevDeckApp
import app.devdeck.core.planner.PlannerData
import app.devdeck.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/** A reminder; [key] makes sure each one is shown only once. */
data class Reminder(val key: String, val title: String, val text: String, val dest: String = "LIFE")

object Reminders {
    private const val CHANNEL = "reminders"
    private const val PREFS = "reminders"

    /** Every 15 minutes (works offline, no network needed). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", true)
    fun setEnabled(context: Context, on: Boolean) = context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", on).apply()

    /** What is due right now. Daily summaries come from 08:00, medication within an hour of its time. */
    fun due(p: PlannerData, today: LocalDate, now: LocalTime): List<Reminder> {
        val l = p.life
        val out = mutableListOf<Reminder>()
        // Medication: from the scheduled time until 2 hours later, if not taken yet.
        l.dosesOn(today).filter { !it.third }.forEach { (m, t, _) ->
            val at = runCatching { LocalTime.parse(t) }.getOrNull() ?: return@forEach
            if (!now.isBefore(at) && now.isBefore(at.plusHours(2)))
                out += Reminder("med:${m.id}:$today $t", "💊 ${m.name}", "Fällig um $t" + if (m.dose.isNotBlank()) " – ${m.dose}" else "")
        }
        // Calendar: 30 minutes before timed events.
        p.eventsOn(today).forEach { e ->
            val at = e.start?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: return@forEach
            if (!now.isBefore(at.minusMinutes(30)) && now.isBefore(at))
                out += Reminder("event:${e.id}:$today", "📅 ${e.title}", "Um ${e.start}", dest = "PLANNER")
        }
        if (now.isBefore(LocalTime.of(8, 0))) return out
        l.upcomingBirthdays(today, 1).forEach { (person, d) ->
            out += Reminder("bday:${person.id}:$today", if (d == 0L) "🎂 ${person.name} hat heute Geburtstag" else "🎁 Morgen: ${person.name}",
                person.turningAge(today)?.let { "wird $it" } ?: (person.gifts.takeIf { it.isNotBlank() }?.let { "Ideen: $it" } ?: ""))
        }
        l.choresDue(today).forEach { c ->
            out += Reminder("chore:${c.id}:${c.nextDue(today)}", "🧹 ${c.title}", c.whoseTurn()?.let { "$it ist dran" } ?: "ist fällig")
        }
        l.expiringSoon(today, 1).forEach { item ->
            out += Reminder("pantry:${item.id}:${item.expires}", "🥫 ${item.name}", "läuft ${if ((item.daysLeft(today) ?: 0) <= 0) "heute" else "morgen"} ab")
        }
        l.activeContracts().forEach { c ->
            val by = c.cancelBy(today) ?: return@forEach
            val days = ChronoUnit.DAYS.between(today, by)
            listOf(30L, 7L, 1L).firstOrNull { days in 0..it }?.let { stage ->
                out += Reminder("contract:${c.id}:$by:$stage", "📄 ${c.name} kündigen?", "Kündigungsfrist endet in $days Tagen (${by.dayOfMonth}.${by.monthValue}.)")
            }
        }
        p.activeDeadlines(today).filter { it.daysLeft(today) in 0..1 }.forEach { d ->
            out += Reminder("deadline:${d.id}:${d.date}", "⏳ ${d.title}", if (d.daysLeft(today) == 0L) "ist heute" else "ist morgen", dest = "PLANNER")
        }
        l.activePeople().filter { it.contactOverdue(today) }.forEach { person ->
            out += Reminder("contact:${person.id}:${person.lastContact}", "☎ ${person.name}", "Mal wieder melden?")
        }
        if (!now.isBefore(LocalTime.of(20, 30)) && l.journal.any { !it.deleted } && l.journalOn(today) == null)
            out += Reminder("journal:$today", "📔 Wie war dein Tag?", "Kurz ins Tagebuch schreiben")
        return out
    }

    fun notifyDue(context: Context) {
        if (!enabled(context)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val c = (context.applicationContext as DevDeckApp).container
        val prefs = context.getSharedPreferences(PREFS, 0)
        val today = LocalDate.now().toEpochDay()
        // Entries "epochDay|key"; forget after 10 days.
        val entries = prefs.getStringSet("shown", emptySet())!!
            .filter { (it.substringBefore('|').toLongOrNull() ?: 0) > today - 10 }.toMutableSet()
        val shown = entries.map { it.substringAfter('|') }.toSet()
        val due = due(c.planner.data.value, LocalDate.now(), LocalTime.now()).filter { it.key !in shown }
        if (due.isEmpty()) { prefs.edit().putStringSet("shown", entries).apply(); return }
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Erinnerungen", NotificationManager.IMPORTANCE_DEFAULT))
        due.forEach { r ->
            val intent = PendingIntent.getActivity(context, r.key.hashCode(),
                Intent(context, MainActivity::class.java).putExtra(HomeWidgets.EXTRA_OPEN, r.dest)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(r.title).setContentText(r.text).setContentIntent(intent).setAutoCancel(true).build()
            runCatching { NotificationManagerCompat.from(context).notify(r.key.hashCode(), n) }
            entries += "$today|${r.key}"
        }
        prefs.edit().putStringSet("shown", entries).apply()
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        runCatching { Reminders.notifyDue(applicationContext) }
        // Planner widgets show "today" – keep them current even without changes.
        runCatching {
            val c = (applicationContext as DevDeckApp).container
            c.widgetRunner.refreshPlanner()
            HomeWidgets.updateAll(applicationContext)
        }
        return Result.success()
    }
}
