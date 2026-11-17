package app.devdeck.core

import app.devdeck.core.prayer.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayerTest {
    private val berlin = PrayerPlace("Berlin", 52.52, 13.405, "Europe/Berlin")
    private val mecca = PrayerPlace("Mekka", 21.4225, 39.8262, "Asia/Riyadh")

    private fun near(expected: String, actual: LocalTime, minutes: Long = 3) =
        assertTrue(abs(ChronoUnit.MINUTES.between(LocalTime.parse(expected), actual)) <= minutes, "expected ~$expected, got $actual")

    @Test fun berlinSummerSolsticeSunriseSunset() {
        val t = PrayerCalc.times(LocalDate.of(2026, 6, 21), berlin, PrayerMethod.MWL)
        // Known sunrise/sunset in Berlin on 21 June: about 04:43 and 21:33 (CEST).
        near("04:43", t.getValue(Prayer.SUNRISE).toLocalTime())
        near("21:33", t.getValue(Prayer.MAGHRIB).toLocalTime())
        near("13:08", t.getValue(Prayer.DHUHR).toLocalTime())
        // At 52.5° N the sun never gets 18° below the horizon in June: Fajr/Isha come from the high-latitude rule.
        val order = listOf(Prayer.FAJR, Prayer.SUNRISE, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).map { t.getValue(it) }
        assertEquals(order.sorted(), order)
    }

    @Test fun berlinWinter() {
        val t = PrayerCalc.times(LocalDate.of(2026, 12, 21), berlin, PrayerMethod.MWL)
        near("08:15", t.getValue(Prayer.SUNRISE).toLocalTime())
        near("15:54", t.getValue(Prayer.MAGHRIB).toLocalTime())
        near("12:00", t.getValue(Prayer.DHUHR).toLocalTime(), 5)
    }

    @Test fun meccaUmmAlQuraIshaIs90MinutesAfterMaghrib() {
        val t = PrayerCalc.times(LocalDate.of(2026, 3, 20), mecca, PrayerMethod.UMM_AL_QURA)
        near("12:27", t.getValue(Prayer.DHUHR).toLocalTime())
        assertEquals(90, ChronoUnit.MINUTES.between(t.getValue(Prayer.MAGHRIB), t.getValue(Prayer.ISHA)))
    }

    @Test fun hanafiAsrIsLater() {
        val d = LocalDate.of(2026, 11, 17)
        val std = PrayerCalc.times(d, berlin, PrayerMethod.MWL, AsrMethod.STANDARD).getValue(Prayer.ASR)
        val han = PrayerCalc.times(d, berlin, PrayerMethod.MWL, AsrMethod.HANAFI).getValue(Prayer.ASR)
        assertTrue(han.isAfter(std.plusMinutes(20)))
    }

    @Test fun diyanetTuning() {
        val d = LocalDate.of(2026, 11, 17)
        val mwl = PrayerCalc.times(d, berlin, PrayerMethod.MWL)
        val diy = PrayerCalc.times(d, berlin, PrayerMethod.DIYANET)
        assertEquals(7, ChronoUnit.MINUTES.between(mwl.getValue(Prayer.MAGHRIB), diy.getValue(Prayer.MAGHRIB)))
    }

    @Test fun qiblaFromBerlinIsSouthEast() {
        val q = PrayerCalc.qibla(52.52, 13.405)
        assertTrue(q in 134.0..138.0, "qibla $q")
    }

    @Test fun nextPrayerWrapsToTomorrow() {
        val (p, at) = PrayerCalc.next(LocalDate.of(2026, 11, 17).atTime(23, 30), berlin, PrayerMethod.MWL, AsrMethod.STANDARD)
        assertEquals(Prayer.FAJR, p); assertEquals(LocalDate.of(2026, 11, 18), at.toLocalDate())
    }

    @Test fun hijriDate() {
        // 1 Ramadan 1447 ≈ 18/19 Feb 2026 (tabular calendar may differ by a day).
        val (y, m, d) = Hijri.fromGregorian(LocalDate.of(2026, 2, 19))
        assertEquals(1447, y); assertEquals(9, m); assertTrue(d in 1..2, "day $d")
    }
}

class PrayerPlaceTest {
    @Test fun parsesGeocodingAnswerAndSyncsConfig() {
        val body = """{"results":[{"id":1,"name":"Köln","latitude":50.93,"longitude":6.95,"timezone":"Europe/Berlin","country":"Deutschland","admin1":"Nordrhein-Westfalen"},
            {"id":2,"name":"Nowhere","latitude":1.0,"longitude":2.0}],"generationtime_ms":0.5}"""
        val places = PlaceSearch.parse(body)
        assertEquals(1, places.size)
        assertEquals("Köln, Nordrhein-Westfalen, Deutschland", places[0].name)
        assertEquals("{}".let { PlaceSearch.parse(it) }, emptyList())

        val a = app.devdeck.core.planner.LifeData(prayer = app.devdeck.core.planner.PrayerConfig(place = places[0], updatedAt = 1))
        val b = app.devdeck.core.planner.LifeData(prayer = app.devdeck.core.planner.PrayerConfig(place = null, method = PrayerMethod.MWL, updatedAt = 2))
        assertEquals(PrayerMethod.MWL, a.merge(b).prayer!!.method)
        assertEquals(places[0], b.merge(a.copy(prayer = a.prayer!!.copy(updatedAt = 3))).prayer!!.place)
        val text = app.devdeck.core.planner.LifeText.prayer(a, LocalDate.of(2026, 11, 17).atTime(10, 0))
        assertTrue(text.startsWith("Köln · nächstes: Dhuhr"), text)
    }
}

class OfflinePlacesTest {
    @Test fun findsRostockOffline() {
        val r = OfflinePlaces.search("rostock")
        assertEquals("Rostock, Deutschland", r.first().name)
        assertTrue(OfflinePlaces.search("köln").isNotEmpty())
        assertTrue(OfflinePlaces.search("").isEmpty())
        // Every built-in zone must exist.
        listOf("a", "e", "i", "o", "u").flatMap { OfflinePlaces.search(it) }.forEach { java.time.ZoneId.of(it.zone) }
    }
}
