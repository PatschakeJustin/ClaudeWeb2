package app.devdeck.core.prayer

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

/**
 * Islamic prayer times, calculated on the device from the sun's position (no internet needed).
 * Astronomical formulas after the well-known PrayTimes algorithm (Hamid Zarrabi-Zadeh).
 */
enum class Prayer(val label: String, val arabic: String) {
    FAJR("Fadschr", "الفجر"), SUNRISE("Sonnenaufgang", "الشروق"), DHUHR("Dhuhr", "الظهر"),
    ASR("Asr", "العصر"), MAGHRIB("Maghrib", "المغرب"), ISHA("Ischa", "العشاء"),
}

/**
 * Calculation conventions. [ishaMinutes] > 0 = Isha a fixed time after Maghrib.
 * [tune] = minutes added per prayer (as the institutions publish them).
 */
enum class PrayerMethod(
    val label: String, val fajrAngle: Double, val ishaAngle: Double, val ishaMinutes: Int = 0,
    val tune: Map<Prayer, Int> = emptyMap(),
) {
    DIYANET("Diyanet (Türkei, viele Moscheen in DE)", 18.0, 17.0,
        tune = mapOf(Prayer.SUNRISE to -7, Prayer.DHUHR to 5, Prayer.ASR to 4, Prayer.MAGHRIB to 7)),
    MWL("Muslim World League", 18.0, 17.0),
    ISNA("ISNA (Nordamerika)", 15.0, 15.0),
    EGYPT("Ägypten", 19.5, 17.5),
    UMM_AL_QURA("Umm al-Qura (Mekka)", 18.5, 0.0, ishaMinutes = 90),
    KARACHI("Karachi", 18.0, 18.0),
    UOIF("UOIF (Frankreich)", 12.0, 12.0),
}

enum class AsrMethod(val label: String, val shadow: Int) { STANDARD("Standard (Schafi'i, Maliki, Hanbali)", 1), HANAFI("Hanafi", 2) }

@Serializable
data class PrayerPlace(val name: String, val latitude: Double, val longitude: Double, val zone: String)

object PrayerCalc {
    private fun dsin(d: Double) = sin(Math.toRadians(d))
    private fun dcos(d: Double) = cos(Math.toRadians(d))
    private fun dtan(d: Double) = tan(Math.toRadians(d))
    private fun darcsin(x: Double) = Math.toDegrees(asin(x))
    private fun darccos(x: Double) = Math.toDegrees(acos(x))
    private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
    private fun darccot(x: Double) = Math.toDegrees(atan(1 / x))
    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }
    private fun fixAngle(a: Double) = fix(a, 360.0)
    private fun fixHour(h: Double) = fix(h, 24.0)

    private fun julian(date: LocalDate): Double {
        var y = date.year; var m = date.monthValue; val d = date.dayOfMonth
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0); val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    /** (declination, equation of time) */
    private fun sun(jd: Double): Pair<Double, Double> {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15
        val eqt = q / 15 - fixHour(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return decl to eqt
    }

    /**
     * Prayer times for [date] at [place], as local date-times in the place's time zone.
     * At high latitudes (summer in northern Germany) Fajr/Isha follow the "angle-based" rule.
     */
    fun times(date: LocalDate, place: PrayerPlace, method: PrayerMethod, asr: AsrMethod = AsrMethod.STANDARD): Map<Prayer, LocalDateTime> {
        val lat = place.latitude; val lng = place.longitude
        val jd = julian(date) - lng / (15 * 24.0)
        fun midDay(t: Double) = fixHour(12 - sun(jd + t).second)
        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sun(jd + t).first
            val noon = midDay(t)
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            if (x < -1 || x > 1) return Double.NaN
            val tt = darccos(x) / 15
            return noon + if (ccw) -tt else tt
        }
        fun asrTime(factor: Int, t: Double): Double {
            val decl = sun(jd + t).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return angleTime(angle, t, false)
        }
        // Two passes: first with rough guesses, then refined with the results.
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0)
        repeat(2) {
            val p = t.map { it / 24 }
            t = doubleArrayOf(
                angleTime(method.fajrAngle, p[0], true),
                angleTime(0.833, p[1], true),
                midDay(p[2]),
                asrTime(asr.shadow, p[3]),
                angleTime(0.833, p[4], false),
                angleTime(0.833, p[5], false),
                if (method.ishaMinutes > 0) Double.NaN else angleTime(method.ishaAngle, p[6], false),
            ).let { arr -> DoubleArray(7) { i -> if (arr[i].isNaN() && i != 0 && i != 6) t[i] else arr[i] } }
        }
        val zone = ZoneId.of(place.zone)
        val offset = zone.rules.getOffset(date.atTime(12, 0)).totalSeconds / 3600.0
        val adj = t.map { it + offset - lng / 15 }.toDoubleArray()
        var (fajr, sunrise, dhuhr, asrT, sunset, maghrib, isha) = listOf(adj[0], adj[1], adj[2], adj[3], adj[4], adj[5], adj[6]).let {
            Septuple(it[0], it[1], it[2], it[3], it[4], it[5], it[6])
        }
        if (method.ishaMinutes > 0) isha = maghrib + method.ishaMinutes / 60.0
        // High latitudes: limit Fajr/Isha to a portion of the night.
        val night = fixHour(sunrise - sunset)
        val fajrPortion = method.fajrAngle / 60 * night
        if (fajr.isNaN() || fixHour(sunrise - fajr) > fajrPortion) fajr = sunrise - fajrPortion
        if (method.ishaMinutes == 0) {
            val ishaPortion = method.ishaAngle / 60 * night
            if (isha.isNaN() || fixHour(isha - sunset) > ishaPortion) isha = sunset + ishaPortion
        }
        val hours = mapOf(Prayer.FAJR to fajr, Prayer.SUNRISE to sunrise, Prayer.DHUHR to dhuhr, Prayer.ASR to asrT, Prayer.MAGHRIB to maghrib, Prayer.ISHA to isha)
        return hours.mapValues { (prayer, h) ->
            val minutes = (h * 60 + (method.tune[prayer] ?: 0)).roundToLong()
            date.atStartOfDay().plusMinutes(minutes)
        }
    }

    /** Direction to the Kaaba in degrees from north (clockwise). */
    fun qibla(lat: Double, lng: Double): Double {
        val kLat = 21.4225; val kLng = 39.8262
        val y = dsin(kLng - lng)
        val x = dcos(lat) * dtan(kLat) - dsin(lat) * dcos(kLng - lng)
        return fixAngle(darctan2(y, x))
    }

    /** Next prayer (not sunrise) after [now], possibly tomorrow's Fajr. */
    fun next(now: LocalDateTime, place: PrayerPlace, method: PrayerMethod, asr: AsrMethod): Pair<Prayer, LocalDateTime> {
        val today = times(now.toLocalDate(), place, method, asr)
        return today.entries.filter { it.key != Prayer.SUNRISE }.sortedBy { it.value }.firstOrNull { it.value.isAfter(now) }?.toPair()
            ?: (Prayer.FAJR to times(now.toLocalDate().plusDays(1), place, method, asr).getValue(Prayer.FAJR))
    }

    private data class Septuple(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double, val g: Double)
}

/** Islamic (Hijri) date, tabular calculation – may differ by ±1 day from the officially announced date. */
object Hijri {
    private val MONTHS = listOf("Muharram", "Safar", "Rabi' al-awwal", "Rabi' al-thani", "Dschumada al-ula", "Dschumada al-achira",
        "Radschab", "Scha'ban", "Ramadan", "Schawwal", "Dhu l-qa'da", "Dhu l-hiddscha")

    /** (year, month 1-12, day) */
    fun fromGregorian(date: LocalDate): Triple<Int, Int, Int> {
        val jd = date.toEpochDay() + 2440588 // Julian day number
        val l0 = jd - 1948440 + 10632
        val n = (l0 - 1) / 10631
        val l1 = l0 - 10631 * n + 354
        val j = ((10985 - l1) / 5316) * ((50 * l1) / 17719) + (l1 / 5670) * ((43 * l1) / 15238)
        val l2 = l1 - ((30 - j) / 15) * ((17719 * j) / 50) - (j / 16) * ((15238 * j) / 43) + 29
        val m = (24 * l2) / 709
        val d = l2 - (709 * m) / 24
        val y = 30 * n + j - 30
        return Triple(y.toInt(), m.toInt(), d.toInt())
    }

    fun format(date: LocalDate): String = fromGregorian(date).let { (y, m, d) -> "$d. ${MONTHS[m - 1]} $y" }
}

/** Place search via the free Open-Meteo geocoding service (no key; returns coordinates and time zone). */
object PlaceSearch {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    @Serializable private data class Result(
        val name: String, val latitude: Double, val longitude: Double, val timezone: String? = null,
        val country: String? = null, val admin1: String? = null,
    )
    @Serializable private data class Response(val results: List<Result> = emptyList())

    fun parse(body: String): List<PrayerPlace> = json.decodeFromString(Response.serializer(), body).results
        .filter { it.timezone != null }
        .map { r -> PrayerPlace(listOfNotNull(r.name, r.admin1, r.country).distinct().joinToString(", "), r.latitude, r.longitude, r.timezone!!) }

    fun search(http: okhttp3.OkHttpClient, query: String): List<PrayerPlace> {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("geocoding-api.open-meteo.com").addPathSegments("v1/search")
            .addQueryParameter("name", query.trim()).addQueryParameter("count", "10").addQueryParameter("language", "de")
            .addQueryParameter("format", "json").build()
        http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Ortssuche fehlgeschlagen (HTTP ${r.code})")
            return parse(r.body?.string().orEmpty())
        }
    }
}

/** Built-in places for when the online search is not reachable. */
object OfflinePlaces {
    private const val B = "Europe/Berlin"
    private val raw = listOf(
        "Berlin" to (52.520 to 13.405), "Hamburg" to (53.551 to 9.994), "München" to (48.137 to 11.575), "Köln" to (50.938 to 6.960),
        "Frankfurt am Main" to (50.110 to 8.682), "Stuttgart" to (48.776 to 9.183), "Düsseldorf" to (51.228 to 6.774), "Leipzig" to (51.340 to 12.375),
        "Dortmund" to (51.514 to 7.465), "Essen" to (51.456 to 7.012), "Bremen" to (53.079 to 8.802), "Dresden" to (51.050 to 13.738),
        "Hannover" to (52.376 to 9.732), "Nürnberg" to (49.452 to 11.077), "Duisburg" to (51.435 to 6.763), "Bochum" to (51.481 to 7.216),
        "Wuppertal" to (51.256 to 7.150), "Bielefeld" to (52.030 to 8.532), "Bonn" to (50.737 to 7.098), "Münster" to (51.961 to 7.626),
        "Mannheim" to (49.488 to 8.466), "Karlsruhe" to (49.007 to 8.404), "Augsburg" to (48.371 to 10.898), "Wiesbaden" to (50.078 to 8.240),
        "Mönchengladbach" to (51.185 to 6.442), "Gelsenkirchen" to (51.517 to 7.086), "Aachen" to (50.775 to 6.084), "Braunschweig" to (52.269 to 10.521),
        "Kiel" to (54.323 to 10.123), "Chemnitz" to (50.828 to 12.921), "Halle (Saale)" to (51.482 to 11.970), "Magdeburg" to (52.121 to 11.628),
        "Freiburg im Breisgau" to (47.999 to 7.842), "Krefeld" to (51.339 to 6.586), "Mainz" to (49.993 to 8.247), "Lübeck" to (53.866 to 10.687),
        "Erfurt" to (50.978 to 11.029), "Oberhausen" to (51.470 to 6.852), "Rostock" to (54.092 to 12.099), "Kassel" to (51.313 to 9.480),
        "Hagen" to (51.367 to 7.463), "Potsdam" to (52.391 to 13.064), "Saarbrücken" to (49.240 to 6.997), "Hamm" to (51.681 to 7.815),
        "Ludwigshafen" to (49.477 to 8.445), "Oldenburg" to (53.144 to 8.214), "Osnabrück" to (52.279 to 8.047), "Leverkusen" to (51.046 to 7.019),
        "Heidelberg" to (49.398 to 8.672), "Darmstadt" to (49.873 to 8.651), "Solingen" to (51.171 to 7.083), "Regensburg" to (49.013 to 12.102),
        "Paderborn" to (51.719 to 8.754), "Ingolstadt" to (48.766 to 11.426), "Würzburg" to (49.792 to 9.953), "Ulm" to (48.401 to 9.988),
        "Wolfsburg" to (52.423 to 10.787), "Göttingen" to (51.541 to 9.916), "Offenbach am Main" to (50.096 to 8.776), "Heilbronn" to (49.142 to 9.219),
        "Pforzheim" to (48.892 to 8.695), "Bremerhaven" to (53.540 to 8.581), "Recklinghausen" to (51.614 to 7.197), "Bottrop" to (51.524 to 6.928),
        "Trier" to (49.750 to 6.637), "Jena" to (50.927 to 11.586), "Siegen" to (50.875 to 8.024), "Cottbus" to (51.756 to 14.333),
        "Schwerin" to (53.636 to 11.401), "Greifswald" to (54.097 to 13.387), "Stralsund" to (54.315 to 13.089), "Wismar" to (53.893 to 11.465),
        "Neubrandenburg" to (53.557 to 13.261), "Flensburg" to (54.794 to 9.436), "Koblenz" to (50.356 to 7.589), "Gießen" to (50.584 to 8.678),
    ).map { (n, c) -> PrayerPlace("$n, Deutschland", c.first, c.second, B) } + listOf(
        PrayerPlace("Wien, Österreich", 48.208, 16.373, "Europe/Vienna"), PrayerPlace("Zürich, Schweiz", 47.377, 8.541, "Europe/Zurich"),
        PrayerPlace("Basel, Schweiz", 47.560, 7.589, "Europe/Zurich"), PrayerPlace("Amsterdam, Niederlande", 52.370, 4.895, "Europe/Amsterdam"),
        PrayerPlace("Brüssel, Belgien", 50.850, 4.352, "Europe/Brussels"), PrayerPlace("Paris, Frankreich", 48.857, 2.352, "Europe/Paris"),
        PrayerPlace("London, Vereinigtes Königreich", 51.507, -0.128, "Europe/London"), PrayerPlace("Istanbul, Türkei", 41.008, 28.978, "Europe/Istanbul"),
        PrayerPlace("Ankara, Türkei", 39.934, 32.860, "Europe/Istanbul"), PrayerPlace("Izmir, Türkei", 38.424, 27.143, "Europe/Istanbul"),
        PrayerPlace("Sarajevo, Bosnien und Herzegowina", 43.856, 18.413, "Europe/Sarajevo"), PrayerPlace("Pristina, Kosovo", 42.663, 21.164, "Europe/Belgrade"),
        PrayerPlace("Mekka, Saudi-Arabien", 21.4225, 39.8262, "Asia/Riyadh"), PrayerPlace("Medina, Saudi-Arabien", 24.467, 39.611, "Asia/Riyadh"),
        PrayerPlace("Kairo, Ägypten", 30.044, 31.236, "Africa/Cairo"), PrayerPlace("Damaskus, Syrien", 33.513, 36.292, "Asia/Damascus"),
        PrayerPlace("Beirut, Libanon", 33.894, 35.502, "Asia/Beirut"), PrayerPlace("Bagdad, Irak", 33.315, 44.366, "Asia/Baghdad"),
        PrayerPlace("Erbil, Irak", 36.191, 44.009, "Asia/Baghdad"), PrayerPlace("Teheran, Iran", 35.689, 51.389, "Asia/Tehran"),
        PrayerPlace("Kabul, Afghanistan", 34.555, 69.207, "Asia/Kabul"), PrayerPlace("Casablanca, Marokko", 33.573, -7.590, "Africa/Casablanca"),
        PrayerPlace("Tunis, Tunesien", 36.806, 10.181, "Africa/Tunis"), PrayerPlace("Dubai, VAE", 25.205, 55.271, "Asia/Dubai"),
    )

    fun search(query: String): List<PrayerPlace> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return raw.filter { it.name.lowercase().contains(q) }.sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }.take(10)
    }
}
