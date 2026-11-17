package app.devdeck.widget

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.devdeck.DevDeckApp
import app.devdeck.core.prayer.PrayerCalc
import app.devdeck.ui.MainActivity
import java.time.LocalDateTime
import java.time.ZoneId

/** One alarm for the next prayer with a notification; after it fires the following one is scheduled. */
object PrayerAlarms {
    private const val CHANNEL = "prayer"

    fun schedule(context: Context, skipMinutes: Long = 0) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(context, 7001, Intent(context, PrayerAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        am.cancel(pi)
        val cfg = (context.applicationContext as DevDeckApp).container.planner.data.value.life.prayer ?: return
        val place = cfg.place ?: return
        if (cfg.notify.isEmpty()) return
        var from = LocalDateTime.now(ZoneId.of(place.zone)).plusMinutes(skipMinutes)
        // Find the next prayer that has a notification switched on.
        repeat(12) {
            val (p, at) = PrayerCalc.next(from, place, cfg.method, cfg.asr)
            if (p in cfg.notify) {
                val ms = at.atZone(ZoneId.of(place.zone)).toInstant().toEpochMilli()
                context.getSharedPreferences("prayer", 0).edit().putString("next", "${p.name}|$ms").apply()
                // Inexact-while-idle needs no special permission; Android may delay it by a few minutes in deep sleep.
                if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) else am.set(AlarmManager.RTC_WAKEUP, ms, pi)
                return
            }
            from = at.plusMinutes(1)
        }
    }

    fun show(context: Context) {
        val raw = context.getSharedPreferences("prayer", 0).getString("next", null) ?: return
        val prayer = runCatching { app.devdeck.core.prayer.Prayer.valueOf(raw.substringBefore('|')) }.getOrNull() ?: return
        val at = raw.substringAfter('|').toLongOrNull() ?: return
        // Ignore stale alarms (e.g. phone was off for hours).
        if (System.currentTimeMillis() - at > 30 * 60_000) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Gebetszeiten", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 7002, Intent(context, MainActivity::class.java).putExtra(HomeWidgets.EXTRA_OPEN, "LIFE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("🕌 ${prayer.label} ${prayer.arabic}").setContentText("Zeit für das ${prayer.label}-Gebet")
            .setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER).build()
        runCatching { NotificationManagerCompat.from(context).notify(7000 + prayer.ordinal, n) }
    }
}

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            runCatching { PrayerAlarms.schedule(context) }; return
        }
        runCatching { PrayerAlarms.show(context) }
        // Skip ahead so the prayer that just fired is not picked again.
        runCatching { PrayerAlarms.schedule(context, skipMinutes = 2) }
    }
}
