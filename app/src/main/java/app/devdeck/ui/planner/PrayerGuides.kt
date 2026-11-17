package app.devdeck.ui.planner

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.prayer.PrayerCalc
import app.devdeck.core.prayer.PrayerPlace
import kotlin.math.abs

// =========================================================================== Qibla compass

/** Live compass heading (degrees from true north) from the rotation sensor; null if the device has none. */
@Composable
private fun rememberHeading(place: PrayerPlace): Triple<Float?, Boolean, Boolean> {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    var lowAccuracy by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf(true) }
    DisposableEffect(place) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        // Magnetic north → true north.
        val declination = GeomagneticField(place.latitude.toFloat(), place.longitude.toFloat(), 0f, System.currentTimeMillis()).declination
        val r = FloatArray(9); val o = FloatArray(3)
        var g: FloatArray? = null; var m: FloatArray? = null
        fun publish() {
            SensorManager.getOrientation(r, o)
            val az = Math.toDegrees(o[0].toDouble()).toFloat() + declination
            heading = ((az % 360f) + 360f) % 360f
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR -> { SensorManager.getRotationMatrixFromVector(r, e.values); publish() }
                    Sensor.TYPE_ACCELEROMETER -> { g = e.values.clone(); if (m != null && SensorManager.getRotationMatrix(r, null, g, m)) publish() }
                    Sensor.TYPE_MAGNETIC_FIELD -> { m = e.values.clone(); if (g != null && SensorManager.getRotationMatrix(r, null, g, m)) publish() }
                }
            }
            override fun onAccuracyChanged(s: Sensor, accuracy: Int) {
                if (s.type == Sensor.TYPE_MAGNETIC_FIELD || s.type == Sensor.TYPE_ROTATION_VECTOR)
                    lowAccuracy = accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW
            }
        }
        when {
            rotation != null -> {
                sm.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_UI)
                magnet?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) } // accuracy updates only
            }
            accel != null && magnet != null -> {
                sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_UI)
                sm.registerListener(listener, magnet, SensorManager.SENSOR_DELAY_UI)
            }
            else -> available = false
        }
        onDispose { sm.unregisterListener(listener) }
    }
    return Triple(heading, lowAccuracy, available)
}

@Composable
internal fun QiblaCompass(place: PrayerPlace) {
    val qibla = PrayerCalc.qibla(place.latitude, place.longitude).toFloat()
    val (heading, lowAccuracy, available) = rememberHeading(place)
    val haptic = LocalHapticFeedback.current
    // Shortest-way rotation so the dial does not spin around at 359° → 0°.
    var dial by remember { mutableStateOf(0f) }
    LaunchedEffect(heading) {
        heading?.let { h -> var d = -h - dial; while (d > 180) d -= 360; while (d < -180) d += 360; dial += d }
    }
    val animated by animateFloatAsState(dial, label = "dial")
    val diff = heading?.let { ((qibla - it + 540f) % 360f) - 180f }
    val aligned = diff != null && abs(diff) < 4f
    LaunchedEffect(aligned) { if (aligned) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.outlineVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val gold = Color(0xFFD4A537)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!available) {
            Text("Dieses Gerät hat keinen Kompass-Sensor. Qibla: ${"%.0f".format(qibla)}° von Norden (im Uhrzeigersinn).",
                textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Text(when {
            heading == null -> "Kompass startet …"
            aligned -> "✓ Du schaust Richtung Kaaba"
            diff!! > 0 -> "Nach rechts drehen (${"%.0f".format(diff)}°)"
            else -> "Nach links drehen (${"%.0f".format(-diff!!)}°)"
        }, style = MaterialTheme.typography.titleMedium, color = if (aligned) gold else onSurface, fontWeight = FontWeight.Bold)
        Box(Modifier.padding(16.dp).size(280.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center; val rad = size.minDimension / 2
                drawCircle(if (aligned) gold else ring, rad - 4f, style = Stroke(if (aligned) 10f else 5f))
                rotate(animated, c) {
                    // Ticks every 30°, north marker red.
                    for (deg in 0 until 360 step 15) rotate(deg.toFloat(), c) {
                        val len = if (deg % 90 == 0) 34f else if (deg % 30 == 0) 22f else 12f
                        drawLine(if (deg == 0) Color(0xFFE5484D) else onSurface, Offset(c.x, c.y - rad + 10f), Offset(c.x, c.y - rad + 10f + len),
                            strokeWidth = if (deg % 90 == 0) 6f else 3f)
                    }
                    // Qibla needle with Kaaba marker.
                    rotate(qibla, c) {
                        drawLine(gold, c, Offset(c.x, c.y - rad * 0.78f), strokeWidth = 12f)
                        val p = Path().apply {
                            moveTo(c.x, c.y - rad * 0.92f); lineTo(c.x - 26f, c.y - rad * 0.74f); lineTo(c.x + 26f, c.y - rad * 0.74f); close()
                        }
                        drawPath(p, gold)
                        drawRect(Color.Black, topLeft = Offset(c.x - 16f, c.y - rad * 0.70f), size = androidx.compose.ui.geometry.Size(32f, 32f))
                    }
                }
                // Fixed marker: the top of the phone.
                drawLine(primary, Offset(c.x, 0f), Offset(c.x, 36f), strokeWidth = 8f)
                drawCircle(onSurface, 10f, c)
            }
        }
        Text("Qibla: ${"%.0f".format(qibla)}° · Blickrichtung: ${heading?.let { "%.0f".format(it) } ?: "–"}°",
            style = MaterialTheme.typography.bodyMedium)
        Text("Handy flach halten, Oberkante zeigt nach vorne. Fern von Metall, Magneten und Lautsprechern messen.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp))
        if (lowAccuracy) Text("Kompass ungenau – bewege das Handy einige Male in Form einer 8, um ihn zu kalibrieren.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

// =========================================================================== Guides

internal data class GuideStep(val title: String, val text: String, val arabic: String? = null, val translit: String? = null, val meaning: String? = null)

@Composable
private fun StepCard(n: Int, s: GuideStep) {
    ItemCard {
        Text("$n. ${s.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(s.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        s.arabic?.let {
            Text(it, style = MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Rtl), lineHeight = 40.sp,
                textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
        s.translit?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp)) }
        s.meaning?.let { Text("„$it“", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private const val NOTE = "Beschrieben ist der verbreitete Ablauf. Einzelheiten unterscheiden sich je nach Rechtsschule (z. B. hanafitisch, schafiitisch) " +
    "und Tradition – im Zweifel den Imam deiner Moschee fragen. Übersetzungen sind sinngemäß."

private val WUDU = listOf(
    GuideStep("Absicht (Niyya)", "Im Herzen fassen: die Gebetswaschung für Allah vornehmen, um rein zu sein für das Gebet."),
    GuideStep("Basmala", "Vor dem Beginn sprechen:", "بِسْمِ ٱللَّهِ", "Bismillāh", "Im Namen Allahs"),
    GuideStep("Hände waschen", "Beide Hände bis zu den Handgelenken 3-mal waschen, rechts beginnen, zwischen den Fingern mit."),
    GuideStep("Mund ausspülen", "Mit der rechten Hand Wasser in den Mund nehmen und 3-mal gründlich ausspülen."),
    GuideStep("Nase reinigen", "Mit der rechten Hand Wasser leicht in die Nase ziehen und mit der linken Hand wieder ausschnäuzen – 3-mal."),
    GuideStep("Gesicht waschen", "Das ganze Gesicht 3-mal waschen: vom Haaransatz bis unter das Kinn, von Ohr zu Ohr."),
    GuideStep("Arme waschen", "Den rechten Arm bis einschließlich Ellbogen 3-mal waschen, dann den linken."),
    GuideStep("Kopf bestreichen (Masah)", "Die nassen Hände einmal vom Haaransatz nach hinten zum Nacken und wieder nach vorne über den Kopf führen."),
    GuideStep("Ohren", "Mit den nassen Zeigefingern die Innenseite und mit den Daumen die Rückseite der Ohren einmal abwischen."),
    GuideStep("Füße waschen", "Den rechten Fuß bis einschließlich Knöchel 3-mal waschen, auch zwischen den Zehen, dann den linken."),
    GuideStep("Bittgebet danach", "Nach der Waschung sprechen:",
        "أَشْهَدُ أَنْ لَا إِلَٰهَ إِلَّا ٱللَّهُ وَحْدَهُ لَا شَرِيكَ لَهُ، وَأَشْهَدُ أَنَّ مُحَمَّدًا عَبْدُهُ وَرَسُولُهُ",
        "Ashhadu an lā ilāha illallāhu waḥdahū lā sharīka lah, wa ashhadu anna Muḥammadan ʿabduhū wa rasūluh.",
        "Ich bezeuge, dass es keinen Gott gibt außer Allah, Er allein, ohne Partner, und ich bezeuge, dass Muhammad Sein Diener und Gesandter ist."),
)

private const val WUDU_INVALID = "Die Gebetswaschung wird u. a. ungültig durch: Toilettengang (Urin, Stuhl), Blähungen, tiefen Schlaf, " +
    "Bewusstlosigkeit. Nach einer großen rituellen Unreinheit ist stattdessen die Ganzkörperwaschung (Ghusl) nötig."

private val FATIHA_AR = "بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ۝ ٱلْحَمْدُ لِلَّهِ رَبِّ ٱلْعَٰلَمِينَ ۝ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ ۝ مَٰلِكِ يَوْمِ ٱلدِّينِ ۝ " +
    "إِيَّاكَ نَعْبُدُ وَإِيَّاكَ نَسْتَعِينُ ۝ ٱهْدِنَا ٱلصِّرَٰطَ ٱلْمُسْتَقِيمَ ۝ صِرَٰطَ ٱلَّذِينَ أَنْعَمْتَ عَلَيْهِمْ غَيْرِ ٱلْمَغْضُوبِ عَلَيْهِمْ وَلَا ٱلضَّآلِّينَ"
private const val FATIHA_TR = "Bismillāhi r-raḥmāni r-raḥīm. Al-ḥamdu lillāhi rabbi l-ʿālamīn. Ar-raḥmāni r-raḥīm. Māliki yawmi d-dīn. " +
    "Iyyāka naʿbudu wa iyyāka nastaʿīn. Ihdinā ṣ-ṣirāṭa l-mustaqīm. Ṣirāṭa lladhīna anʿamta ʿalayhim, ghayri l-maghḍūbi ʿalayhim wa la ḍ-ḍāllīn. (Āmīn)"
private const val FATIHA_DE = "Im Namen Allahs, des Allerbarmers, des Barmherzigen. Lob sei Allah, dem Herrn der Welten, dem Allerbarmer, dem Barmherzigen, " +
    "dem Herrscher am Tag des Gerichts. Dir allein dienen wir und Dich allein bitten wir um Hilfe. Leite uns den geraden Weg, den Weg derer, " +
    "denen Du Gnade erwiesen hast, nicht derer, die Deinen Zorn erregt haben, und nicht der Irregehenden."

private val SALAH = listOf(
    GuideStep("Vorbereitung", "Gebetswaschung, saubere Kleidung (Männer mindestens vom Bauchnabel bis zu den Knien bedeckt, Frauen alles außer " +
        "Gesicht und Händen) und ein sauberer Platz. Richtung Qibla stellen (siehe Kompass)."),
    GuideStep("Absicht (Niyya)", "Im Herzen das bestimmte Gebet beabsichtigen, z. B. „die 2 Pflicht-Rakʿa des Fadschr-Gebets“."),
    GuideStep("Eröffnungs-Takbir", "Hände bis auf Höhe der Ohren (bzw. Schultern) heben und sprechen:", "ٱللَّهُ أَكْبَرُ", "Allāhu akbar", "Allah ist größer"),
    GuideStep("Stehen (Qiyām) – Eröffnungsbittgebet", "Hände vor dem Körper übereinanderlegen (rechte über linke), Blick auf den Platz der Niederwerfung. Leise:",
        "سُبْحَانَكَ ٱللَّٰهُمَّ وَبِحَمْدِكَ، وَتَبَارَكَ ٱسْمُكَ، وَتَعَالَىٰ جَدُّكَ، وَلَا إِلَٰهَ غَيْرُكَ",
        "Subḥānaka llāhumma wa bi-ḥamdik, wa tabāraka smuk, wa taʿālā jadduk, wa lā ilāha ghayruk.",
        "Gepriesen seist Du, o Allah, und Dir sei Lob. Gesegnet ist Dein Name, erhaben Deine Majestät, und es gibt keinen Gott außer Dir."),
    GuideStep("Zuflucht und Basmala", "", "أَعُوذُ بِٱللَّهِ مِنَ ٱلشَّيْطَانِ ٱلرَّجِيمِ ۝ بِسْمِ ٱللَّهِ ٱلرَّحْمَٰنِ ٱلرَّحِيمِ",
        "Aʿūdhu billāhi mina sh-shayṭāni r-rajīm. Bismillāhi r-raḥmāni r-raḥīm.",
        "Ich suche Zuflucht bei Allah vor dem verfluchten Satan. Im Namen Allahs, des Allerbarmers, des Barmherzigen."),
    GuideStep("Al-Fātiḥa", "Die erste Sure wird in jeder Rakʿa rezitiert:", FATIHA_AR, FATIHA_TR, FATIHA_DE),
    GuideStep("Eine weitere Sure", "In der 1. und 2. Rakʿa folgt eine kurze Sure, z. B. Al-Ikhlāṣ:",
        "قُلْ هُوَ ٱللَّهُ أَحَدٌ ۝ ٱللَّهُ ٱلصَّمَدُ ۝ لَمْ يَلِدْ وَلَمْ يُولَدْ ۝ وَلَمْ يَكُن لَّهُۥ كُفُوًا أَحَدٌۢ",
        "Qul huwa llāhu aḥad. Allāhu ṣ-ṣamad. Lam yalid wa lam yūlad. Wa lam yakun lahū kufuwan aḥad.",
        "Sag: Er ist Allah, ein Einziger, Allah, der Absolute. Er zeugt nicht und ist nicht gezeugt worden, und niemand ist Ihm gleich."),
    GuideStep("Verbeugung (Rukūʿ)", "„Allāhu akbar“ sagen, verbeugen, Hände auf die Knie, Rücken gerade. 3-mal:",
        "سُبْحَانَ رَبِّيَ ٱلْعَظِيمِ", "Subḥāna rabbiya l-ʿaẓīm", "Gepriesen sei mein Herr, der Gewaltige"),
    GuideStep("Aufrichten (Iʿtidāl)", "Beim Aufrichten, dann aufrecht stehend:",
        "سَمِعَ ٱللَّهُ لِمَنْ حَمِدَهُ ۝ رَبَّنَا وَلَكَ ٱلْحَمْدُ", "Samiʿa llāhu liman ḥamidah – Rabbanā wa laka l-ḥamd",
        "Allah hört den, der Ihn lobt – Unser Herr, Dir gebührt das Lob"),
    GuideStep("Niederwerfung (Sudschūd)", "„Allāhu akbar“ sagen und niederwerfen: Stirn und Nase, beide Handflächen, Knie und Zehen berühren den Boden. 3-mal:",
        "سُبْحَانَ رَبِّيَ ٱلْأَعْلَىٰ", "Subḥāna rabbiya l-aʿlā", "Gepriesen sei mein Herr, der Höchste"),
    GuideStep("Sitzen zwischen den Niederwerfungen", "„Allāhu akbar“, kurz aufrecht sitzen:", "رَبِّ ٱغْفِرْ لِي", "Rabbi ghfir lī", "Mein Herr, vergib mir"),
    GuideStep("Zweite Niederwerfung", "„Allāhu akbar“, zweite Niederwerfung wie zuvor (3-mal „Subḥāna rabbiya l-aʿlā“). Damit ist eine Rakʿa beendet."),
    GuideStep("Weitere Rakʿa", "Mit „Allāhu akbar“ aufstehen und die nächste Rakʿa wie oben beten – ohne Eröffnungsbittgebet. " +
        "In der 3. und 4. Rakʿa wird nur Al-Fātiḥa rezitiert."),
    GuideStep("Sitzen mit Taschahhud", "Nach jeder 2. Rakʿa sitzen und sprechen (der rechte Zeigefinger wird beim Glaubensbekenntnis angehoben):",
        "ٱلتَّحِيَّاتُ لِلَّهِ وَٱلصَّلَوَاتُ وَٱلطَّيِّبَاتُ، ٱلسَّلَامُ عَلَيْكَ أَيُّهَا ٱلنَّبِيُّ وَرَحْمَةُ ٱللَّهِ وَبَرَكَاتُهُ، ٱلسَّلَامُ عَلَيْنَا وَعَلَىٰ عِبَادِ ٱللَّهِ ٱلصَّالِحِينَ، " +
            "أَشْهَدُ أَنْ لَا إِلَٰهَ إِلَّا ٱللَّهُ، وَأَشْهَدُ أَنَّ مُحَمَّدًا عَبْدُهُ وَرَسُولُهُ",
        "At-taḥiyyātu lillāhi wa ṣ-ṣalawātu wa ṭ-ṭayyibāt. As-salāmu ʿalayka ayyuha n-nabiyyu wa raḥmatu llāhi wa barakātuh. " +
            "As-salāmu ʿalaynā wa ʿalā ʿibādi llāhi ṣ-ṣāliḥīn. Ashhadu an lā ilāha illallāh, wa ashhadu anna Muḥammadan ʿabduhū wa rasūluh.",
        "Alle Grüße, Gebete und guten Worte gebühren Allah. Friede sei mit dir, o Prophet, und die Barmherzigkeit Allahs und Sein Segen. " +
            "Friede sei mit uns und mit den rechtschaffenen Dienern Allahs. Ich bezeuge, dass es keinen Gott gibt außer Allah, und ich bezeuge, dass Muhammad Sein Diener und Gesandter ist."),
    GuideStep("Im letzten Sitzen: Segenswunsch", "Nur im letzten Sitzen nach dem Taschahhud:",
        "ٱللَّٰهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ وَعَلَىٰ آلِ مُحَمَّدٍ، كَمَا صَلَّيْتَ عَلَىٰ إِبْرَاهِيمَ وَعَلَىٰ آلِ إِبْرَاهِيمَ، إِنَّكَ حَمِيدٌ مَجِيدٌ، " +
            "ٱللَّٰهُمَّ بَارِكْ عَلَىٰ مُحَمَّدٍ وَعَلَىٰ آلِ مُحَمَّدٍ، كَمَا بَارَكْتَ عَلَىٰ إِبْرَاهِيمَ وَعَلَىٰ آلِ إِبْرَاهِيمَ، إِنَّكَ حَمِيدٌ مَجِيدٌ",
        "Allāhumma ṣalli ʿalā Muḥammadin wa ʿalā āli Muḥammad, kamā ṣallayta ʿalā Ibrāhīma wa ʿalā āli Ibrāhīm, innaka ḥamīdun majīd. " +
            "Allāhumma bārik ʿalā Muḥammadin wa ʿalā āli Muḥammad, kamā bārakta ʿalā Ibrāhīma wa ʿalā āli Ibrāhīm, innaka ḥamīdun majīd.",
        "O Allah, segne Muhammad und die Familie Muhammads, wie Du Ibrahim und die Familie Ibrahims gesegnet hast; Du bist der Lobenswerte, " +
            "der Ruhmreiche. O Allah, schenke Muhammad und der Familie Muhammads Segen, wie Du Ibrahim und der Familie Ibrahims Segen geschenkt hast; " +
            "Du bist der Lobenswerte, der Ruhmreiche."),
    GuideStep("Friedensgruß (Taslīm)", "Kopf nach rechts drehen und sprechen, dann nach links – das Gebet ist beendet:",
        "ٱلسَّلَامُ عَلَيْكُمْ وَرَحْمَةُ ٱللَّهِ", "As-salāmu ʿalaykum wa raḥmatu llāh", "Friede sei mit euch und die Barmherzigkeit Allahs"),
)

private val RAKAT = listOf("Fadschr" to "2", "Dhuhr" to "4", "Asr" to "4", "Maghrib" to "3", "Ischa" to "4")

@Composable
internal fun WuduGuide() = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
    Text("Gebetswaschung (Wuḍūʾ)", style = MaterialTheme.typography.titleMedium)
    Text("Mit sauberem Wasser, die Reihenfolge einhalten und ohne lange Unterbrechung.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    WUDU.forEachIndexed { i, s -> StepCard(i + 1, s) }
    ItemCard { Text(WUDU_INVALID, style = MaterialTheme.typography.bodySmall) }
    Text(NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}

@Composable
internal fun SalahGuide() = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
    Text("Das Gebet (Ṣalāh)", style = MaterialTheme.typography.titleMedium)
    ItemCard {
        Text("Pflicht-Rakʿa pro Gebet", style = MaterialTheme.typography.titleSmall)
        RAKAT.forEach { (n, r) -> Row { Text(n, Modifier.weight(1f)); Text("$r Rakʿa", fontWeight = FontWeight.Bold) } }
        Text("Rezitation: Fadschr, Maghrib und Ischa (erste zwei Rakʿa) laut, Dhuhr und Asr leise.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
    SALAH.forEachIndexed { i, s -> StepCard(i + 1, s) }
    Text(NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
}
