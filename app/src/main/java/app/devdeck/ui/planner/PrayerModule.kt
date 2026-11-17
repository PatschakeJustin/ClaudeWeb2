package app.devdeck.ui.planner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.devdeck.core.planner.LifeData
import app.devdeck.core.planner.PrayerConfig
import app.devdeck.core.planner.now
import app.devdeck.core.prayer.*
import app.devdeck.data.PlannerRepository
import app.devdeck.ui.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrayerModule(repo: PlannerRepository, l: LifeData, today: LocalDate, tick: Long) {
    val c = appContainer()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = l.prayer ?: PrayerConfig()
    fun save(n: PrayerConfig) {
        repo.life { it.copy(prayer = n.copy(updatedAt = now())) }
        app.devdeck.widget.PrayerAlarms.schedule(context)
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PrayerPlace>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(cfg.place == null) }
    var day by remember { mutableStateOf(today) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            if (showSearch || cfg.place == null) {
                Text("Ort suchen", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, placeholder = { Text("Stadt, z. B. Duisburg") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(enabled = query.isNotBlank() && !searching, onClick = {
                        searching = true; error = null
                        scope.launch {
                            try {
                                results = withContext(Dispatchers.IO) { PlaceSearch.search(c.http, query) }
                                if (results.isEmpty()) { results = OfflinePlaces.search(query); if (results.isEmpty()) error = "Nichts gefunden." }
                            } catch (e: Exception) {
                                // No internet / service not reachable: use the built-in list.
                                results = OfflinePlaces.search(query)
                                error = if (results.isEmpty()) "Online-Suche nicht erreichbar (${e.message}). Versuche eine größere Stadt in der Nähe."
                                    else "Online-Suche nicht erreichbar – Treffer aus der eingebauten Städteliste:"
                            }
                            searching = false
                        }
                    }) { if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Filled.Search, "Suchen") }
                }
                error?.let { Text(it, color = if (results.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall) }
                results.forEach { p ->
                    Text(p.name, Modifier.fillMaxWidth().clickable {
                        save(cfg.copy(place = p, notify = if (cfg.place == null) Prayer.entries.filter { it != Prayer.SUNRISE }.toSet() else cfg.notify))
                        results = emptyList(); query = ""; showSearch = false
                    }.padding(vertical = 10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (cfg.place != null) TextButton(onClick = { showSearch = false }) { Text("Abbrechen") }
            }
        }
        val place = cfg.place ?: return@LazyColumn
        item {
            @Suppress("UNUSED_EXPRESSION") tick
            val nowDt = LocalDateTime.now()
            val (next, at) = PrayerCalc.next(nowDt, place, cfg.method, cfg.asr)
            val left = Duration.between(nowDt, at)
            ItemCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(place.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nächstes Gebet", style = MaterialTheme.typography.labelLarge)
                        Text("${next.label}  ${at.format(HHMM)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("in ${left.toHours()} h ${(left.toMinutes() % 60)} min", color = MaterialTheme.colorScheme.primary)
                    }
                    Text(next.arabic, style = MaterialTheme.typography.headlineSmall)
                }
                TextButton(onClick = { showSearch = true }) { Text("Ort ändern") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { day = day.minusDays(1) }) { Text("‹") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.format(DATE_DE), style = MaterialTheme.typography.titleSmall)
                    Text(Hijri.format(day), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { day = day.plusDays(1) }) { Text("›") }
            }
            val times = PrayerCalc.times(day, place, cfg.method, cfg.asr)
            times.forEach { (p, t) ->
                val isNext = p == next && t == at
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.label, Modifier.weight(1f), fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                        color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(p.arabic, Modifier.padding(end = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.format(HHMM), style = MaterialTheme.typography.titleMedium, fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal)
                    if (p != Prayer.SUNRISE) IconButton(onClick = {
                        save(cfg.copy(notify = if (p in cfg.notify) cfg.notify - p else cfg.notify + p))
                    }) { Icon(if (p in cfg.notify) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                        if (p in cfg.notify) "Erinnerung an" else "Erinnerung aus") }
                    else Spacer(Modifier.width(48.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            SectionTitle("Qibla-Richtung")
            val q = PrayerCalc.qibla(place.latitude, place.longitude)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val color = MaterialTheme.colorScheme.primary; val ring = MaterialTheme.colorScheme.outlineVariant
                Canvas(Modifier.size(90.dp)) {
                    drawCircle(ring, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                    rotate(q.toFloat()) {
                        drawLine(color, center, Offset(center.x, size.height * 0.08f), strokeWidth = 8f)
                    }
                }
                Text("  ${"%.0f".format(q)}° von Norden (im Uhrzeigersinn).\n  Handy-Oberkante nach Norden halten,\n  der Strich zeigt Richtung Kaaba.",
                    style = MaterialTheme.typography.bodySmall)
            }

            SectionTitle("Berechnungsmethode")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrayerMethod.entries.forEach { m -> FilterChip(selected = cfg.method == m, onClick = { save(cfg.copy(method = m)) }, label = { Text(m.label) }) }
            }
            SectionTitle("Asr")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AsrMethod.entries.forEach { m -> FilterChip(selected = cfg.asr == m, onClick = { save(cfg.copy(asr = m)) }, label = { Text(m.label) }) }
            }
            Text("Die Zeiten werden auf dem Gerät berechnet und können je nach Moschee um einige Minuten abweichen. " +
                "Das Hijri-Datum ist berechnet und kann vom offiziell verkündeten um einen Tag abweichen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
