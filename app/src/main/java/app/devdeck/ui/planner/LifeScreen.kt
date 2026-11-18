package app.devdeck.ui.planner

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.core.planner.*
import app.devdeck.data.PlannerRepository
import app.devdeck.ui.Dest
import app.devdeck.ui.appContainer
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JTextStyle

/** The everyday modules. [preview] = widget text shown on the overview tile. */
enum class LifeModule(val label: String, val icon: ImageVector) {
    BRIEFING("Tagesbriefing", Icons.Filled.WbSunny),
    BROWSER("Browser", Icons.Filled.Public),
    PRAYER("Gebetszeiten", Icons.Filled.Brightness3),
    SLEEP("Schlaf", Icons.Filled.Bedtime),
    SHOPPING("Einkauf", Icons.Filled.ShoppingCart),
    MONEY("Haushaltsbuch", Icons.Filled.Euro),
    FOOD("Rezepte & Essen", Icons.Filled.Restaurant),
    PANTRY("Vorräte", Icons.Filled.Kitchen),
    CONTRACTS("Verträge", Icons.Filled.Description),
    HEALTH("Gesundheit", Icons.Filled.Favorite),
    TIME("Zeiterfassung", Icons.Filled.Timer),
    PEOPLE("Geburtstage", Icons.Filled.Cake),
    CHORES("Haushalt", Icons.Filled.CleaningServices),
    JOURNAL("Tagebuch", Icons.Filled.AutoStories),
}

fun lifePreview(m: LifeModule, p: PlannerData, today: LocalDate): String = when (m) {
    LifeModule.BRIEFING -> LifeText.briefing(p, today)
    LifeModule.BROWSER -> "Im Internet surfen – mit Tabs, Lesezeichen und Verlauf, direkt in der App."
    LifeModule.PRAYER -> LifeText.prayer(p.life)
    LifeModule.SLEEP -> LifeText.sleep(p.life, today)
    LifeModule.SHOPPING -> LifeText.shopping(p.life, 5)
    LifeModule.MONEY -> LifeText.budget(p.life, today)
    LifeModule.FOOD -> LifeText.meals(p.life, today)
    LifeModule.PANTRY -> LifeText.pantry(p.life, today)
    LifeModule.CONTRACTS -> LifeText.contracts(p.life, today)
    LifeModule.HEALTH -> LifeText.health(p.life, today)
    LifeModule.TIME -> LifeText.time(p.life, today)
    LifeModule.PEOPLE -> LifeText.birthdays(p.life, today)
    LifeModule.CHORES -> LifeText.chores(p.life, today)
    LifeModule.JOURNAL -> LifeText.journal(p.life, today)
}

/** Shorthand: change only the everyday part of the planner data. */
internal fun PlannerRepository.life(f: (LifeData) -> LifeData) = update { it.copy(life = f(it.life)) }

internal fun shareText(context: android.content.Context, subject: String, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text), subject))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LifeScreen(navigate: (Dest) -> Unit) {
    val c = appContainer()
    val data by c.planner.data.collectAsState()
    var open by rememberSaveable { mutableStateOf<LifeModule?>(null) }
    // A clock that ticks every 30 s so timers and "today" stay current.
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(30_000); tick++ } }
    val today = remember(tick) { LocalDate.now() }
    ReminderPermission()

    val module = open
    if (module == null) {
        Column(Modifier.fillMaxSize()) {
            val context = LocalContext.current
            var reminders by remember { mutableStateOf(app.devdeck.widget.Reminders.enabled(context)) }
            TopAppBar(title = { Text("Alltag") }, actions = {
                IconButton(onClick = { reminders = !reminders; app.devdeck.widget.Reminders.setEnabled(context, reminders) }) {
                    Icon(if (reminders) Icons.Filled.NotificationsActive else Icons.Filled.NotificationsOff,
                        if (reminders) "Erinnerungen an" else "Erinnerungen aus")
                }
            })
            LazyVerticalGrid(
                columns = GridCells.Adaptive(165.dp), modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(LifeModule.entries, key = { it.name }) { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (m == LifeModule.BRIEFING)
                        MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).clickable { if (m == LifeModule.BROWSER) navigate(Dest.BROWSER) else open = m }) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(m.icon, null, Modifier.size(20.dp), tint = colorOf(m.ordinal))
                                Text(m.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 8.dp))
                            }
                            Text(lifePreview(m, data, today), style = MaterialTheme.typography.bodySmall, maxLines = 5,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        return
    }
    BackHandler { open = null }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(module.label) },
            navigationIcon = { IconButton(onClick = { open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (module) {
                LifeModule.BRIEFING -> BriefingModule(data, today, navigate)
                LifeModule.BROWSER -> Unit
                LifeModule.PRAYER -> PrayerModule(c.planner, data.life, today, tick)
                LifeModule.SLEEP -> SleepModule(c.planner, data.life, today)
                LifeModule.SHOPPING -> ShoppingModule(c.planner, data.life)
                LifeModule.MONEY -> MoneyModule(c.planner, data.life, today)
                LifeModule.FOOD -> FoodModule(c.planner, data.life, today)
                LifeModule.PANTRY -> PantryModule(c.planner, data.life, today)
                LifeModule.CONTRACTS -> ContractsModule(c.planner, data.life, today)
                LifeModule.HEALTH -> HealthModule(c.planner, data.life, today)
                LifeModule.TIME -> TimeModule(c.planner, data.life, today, tick)
                LifeModule.PEOPLE -> PeopleModule(c.planner, data.life, today)
                LifeModule.CHORES -> ChoresModule(c.planner, data.life, today)
                LifeModule.JOURNAL -> JournalModule(c.planner, data.life, today)
            }
        }
    }
}

@Composable
private fun ReminderPermission() {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    }
}

@Composable
internal fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
internal fun ItemCard(onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) =
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)) {
        Column(Modifier.padding(12.dp), content = content)
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceChips(options: List<String>, selected: String, onSelect: (String) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) }) }
    }

// ===================================================================== Briefing

@Composable
private fun BriefingModule(data: PlannerData, today: LocalDate, navigate: (Dest) -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val text = LifeText.briefing(data, today)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(today.format(DATE_DE), style = MaterialTheme.typography.titleMedium)
        ItemCard { Text(text, style = MaterialTheme.typography.bodyLarge, lineHeight = 24.sp) }
        Button(onClick = {
            c.pendingPrompt.value = "Hier ist mein Tagesüberblick. Fasse ihn kurz und freundlich zusammen, setze Prioritäten " +
                "und schlage eine sinnvolle Reihenfolge für den Tag vor:\n\n$text"
            navigate(Dest.AGENT)
        }) { Icon(Icons.Filled.AutoAwesome, null); Text("  Von der KI zusammenfassen lassen") }
        OutlinedButton(onClick = { shareText(context, "Tagesbriefing", text) }) { Text("Teilen …") }
        Text("Tipp: Als Widget auf dem Startbildschirm siehst du das Briefing jeden Morgen sofort.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===================================================================== 1 Einkauf

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShoppingModule(repo: PlannerRepository, l: LifeData) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var shop by rememberSaveable { mutableStateOf("Allgemein") }
    val shops = (listOf("Allgemein", "Supermarkt", "Drogerie", "Baumarkt") + l.shops()).distinct()
    fun add(n: String) { if (n.isNotBlank()) repo.life { it.copy(shopping = it.shopping + ShoppingItem(name = n.trim(), amount = amount.trim(), shop = shop)) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, placeholder = { Text("Artikel") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(amount, { amount = it }, placeholder = { Text("Menge") }, singleLine = true, modifier = Modifier.width(90.dp))
                IconButton(enabled = name.isNotBlank(), onClick = { add(name); name = ""; amount = "" }) { Icon(Icons.Filled.Add, "Hinzufügen") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                shops.forEach { s -> FilterChip(selected = shop == s, onClick = { shop = s }, label = { Text(s) }) }
            }
            val sug = l.suggestions()
            if (sug.isNotEmpty()) {
                Text("Oft gekauft", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sug.forEach { s -> AssistChip(onClick = { repo.life { it.withShopping(listOf(s), shop) } }, label = { Text("+ $s") }) }
                }
            }
        }
        val open = l.openShopping()
        if (open.isEmpty()) item { Empty("Die Liste ist leer.") }
        open.groupBy { it.shop }.forEach { (s, list) ->
            item(key = "h-$s") { SectionTitle(s) }
            items(list, key = { it.id }) { i ->
                Row(Modifier.fillMaxWidth().clickable {
                    repo.life { it.copy(shopping = it.shopping.upsert(i.copy(done = true, updatedAt = now()))) }
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(false, { repo.life { it.copy(shopping = it.shopping.upsert(i.copy(done = true, updatedAt = now()))) } })
                    Text(i.name, Modifier.weight(1f))
                    if (i.amount.isNotBlank()) Text(i.amount, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = { repo.life { it.copy(shopping = it.shopping.upsert(i.copy(deleted = true, updatedAt = now()))) } }) {
                        Icon(Icons.Filled.Close, "Entfernen", Modifier.size(18.dp))
                    }
                }
            }
        }
        val done = l.shopping.filter { !it.deleted && it.done }
        if (done.isNotEmpty()) item(key = "done") {
            SectionTitle("Im Wagen (${done.size})")
            done.take(30).forEach { i ->
                Row(Modifier.fillMaxWidth().clickable { repo.life { it.copy(shopping = it.shopping.upsert(i.copy(done = false, updatedAt = now()))) } },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(true, { repo.life { it.copy(shopping = it.shopping.upsert(i.copy(done = false, updatedAt = now()))) } })
                    Text(i.name, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.outline)
                }
            }
            Row {
                TextButton(onClick = {
                    // Done items stay as history for the suggestions, but are hidden by marking them deleted.
                    repo.life { it.copy(shopping = it.shopping.map { s -> if (s.done && !s.deleted) s.copy(deleted = true, updatedAt = now()) else s }) }
                }) { Text("Erledigte leeren") }
            }
        }
        item {
            OutlinedButton(onClick = { shareText(context, "Einkaufsliste", LifeText.shopping(l, 200)) }, modifier = Modifier.padding(top = 12.dp)) {
                Icon(Icons.Filled.Share, null); Text("  Liste teilen")
            }
            Text("Gemeinsame Liste: Mit demselben Hub-Konto auf beiden Handys angemeldet, ist die Liste überall gleich.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ===================================================================== 2 Haushaltsbuch

private val CATEGORIES = listOf("Lebensmittel", "Wohnen", "Mobilität", "Freizeit", "Abos", "Gesundheit", "Kleidung", "Technik", "Sonstiges")

@Composable
private fun MoneyModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var month by remember { mutableStateOf(YearMonth.from(today)) }
    var editing by remember { mutableStateOf<Expense?>(null) }
    var budgetDialog by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 88.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = month.minusMonths(1) }) { Icon(Icons.Filled.ChevronLeft, "Voriger Monat") }
                    Text("${month.month.getDisplayName(JTextStyle.FULL, DE)} ${month.year}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { month = month.plusMonths(1) }) { Icon(Icons.Filled.ChevronRight, "Nächster Monat") }
                }
                val spent = l.spent(month); val earned = l.earned(month); val budget = l.totalBudget()
                ItemCard(onClick = { budgetDialog = true }) {
                    Row { Text("Ausgaben", Modifier.weight(1f)); Text(euro(spent), fontWeight = FontWeight.Bold) }
                    Row { Text("Einnahmen", Modifier.weight(1f)); Text(euro(earned)) }
                    Row { Text("Saldo", Modifier.weight(1f)); Text(euro(earned - spent), color = if (earned - spent < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                    if (budget != null) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { (spent.toFloat() / budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                            color = if (spent > budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        Text("Budget ${euro(budget)} · übrig ${euro(budget - spent)}", style = MaterialTheme.typography.bodySmall)
                    } else Text("Tippen, um ein Monatsbudget festzulegen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val cats = l.byCategory(month)
                if (cats.isNotEmpty()) SectionTitle("Nach Kategorie")
                cats.forEach { (cat, v) ->
                    val b = l.categoryBudget(cat)
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Row { Text(cat, Modifier.weight(1f)); Text(euro(v) + (b?.let { " / ${euro(it)}" } ?: "")) }
                        LinearProgressIndicator(progress = { (v.toFloat() / (b ?: maxOf(spent, 1))).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(),
                            color = if (b != null && v > b) MaterialTheme.colorScheme.error else colorOf(CATEGORIES.indexOf(cat).coerceAtLeast(0)))
                    }
                }
                SectionTitle("Buchungen")
            }
            val entries = l.entriesIn(month).sortedByDescending { it.date }
            if (entries.isEmpty()) item { Empty("Noch keine Buchungen in diesem Monat.") }
            items(entries, key = { it.id }) { e ->
                Row(Modifier.fillMaxWidth().clickable { editing = e }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.title + if (e.monthly) "  ↻" else "")
                        Text("${e.category} · ${LocalDate.parse(e.date).format(DATE_DE)}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text((if (e.income) "+" else "−") + euro(e.cents), fontWeight = FontWeight.Medium,
                        color = if (e.income) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        AddFab { editing = Expense(title = "", cents = 0, date = today.toString()) }
    }
    editing?.let { orig ->
        var e by remember(orig.id) { mutableStateOf(orig) }
        var amount by remember(orig.id) { mutableStateOf(if (orig.cents == 0L) "" else euro(orig.cents).removeSuffix(" €")) }
        val isNew = l.expenses.none { it.id == orig.id }
        val cents = parseEuro(amount)
        EditDialog(if (isNew) "Neue Buchung" else "Buchung", { editing = null },
            onSave = if (e.title.isNotBlank() && cents != null && cents > 0) ({
                repo.life { it.copy(expenses = it.expenses.upsert(e.copy(cents = cents, updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(expenses = it.expenses.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = !e.income, onClick = { e = e.copy(income = false) }, label = { Text("Ausgabe") })
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = e.income, onClick = { e = e.copy(income = true) }, label = { Text("Einnahme") })
            }
            OutlinedTextField(e.title, { e = e.copy(title = it) }, label = { Text("Wofür?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it }, label = { Text("Betrag in €") }, singleLine = true, isError = amount.isNotBlank() && cents == null,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth())
            DateField("Datum", e.date, { d -> d?.let { e = e.copy(date = it) } })
            ChoiceChips(if (e.income) listOf("Gehalt", "Sonstiges") else CATEGORIES, e.category) { e = e.copy(category = it) }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(e.monthly, { e = e.copy(monthly = it) }); Text("  Jeden Monat (Abo, Miete …)") }
        }
    }
    if (budgetDialog) {
        val current = l.budgets.filter { !it.deleted }
        var total by remember { mutableStateOf(l.totalBudget()?.let { euro(it).removeSuffix(" €") } ?: "") }
        val perCat = remember { mutableStateMapOf<String, String>().apply { CATEGORIES.forEach { c -> l.categoryBudget(c)?.let { put(c, euro(it).removeSuffix(" €")) } } } }
        EditDialog("Monatsbudget", { budgetDialog = false }, onSave = {
            repo.life { life ->
                var b = life.budgets
                fun set(cat: String, text: String) {
                    val existing = current.firstOrNull { it.category == cat }
                    val v = parseEuro(text)
                    b = when {
                        v == null && existing != null -> b.upsert(existing.copy(deleted = true, updatedAt = now()))
                        v == null -> b
                        existing != null -> b.upsert(existing.copy(monthlyCents = v, updatedAt = now()))
                        else -> b + Budget(category = cat, monthlyCents = v)
                    }
                }
                set("", total); CATEGORIES.forEach { set(it, perCat[it].orEmpty()) }
                life.copy(budgets = b)
            }
        }, onDelete = null) {
            OutlinedTextField(total, { total = it }, label = { Text("Gesamt pro Monat (€)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Optional pro Kategorie:", style = MaterialTheme.typography.labelLarge)
            CATEGORIES.forEach { cat ->
                OutlinedTextField(perCat[cat].orEmpty(), { perCat[cat] = it }, label = { Text(cat) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ===================================================================== 3 Rezepte & Essensplan

@Composable
private fun FoodModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var editRecipe by remember { mutableStateOf<Recipe?>(null) }
    var viewRecipe by remember { mutableStateOf<Recipe?>(null) }
    var planSlot by remember { mutableStateOf<Pair<LocalDate, Meal>?>(null) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Essensplan") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Rezepte (${l.activeRecipes().size})") })
        }
        if (tab == 0) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items((0 until 7).map { today.plusDays(it.toLong()) }, key = { it.toString() }) { d ->
                    SectionTitle(PlannerText.dayLabel(d, today) + " · " + d.format(DATE_DE))
                    Meal.entries.forEach { meal ->
                        val planned = l.mealsOn(d).firstOrNull { it.meal == meal }
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { planSlot = d to meal }.padding(vertical = 6.dp)) {
                            Text(meal.label, Modifier.width(90.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(planned?.let { l.mealTitle(it) } ?: "–", color = if (planned == null) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                item {
                    val ids = (0 until 7).flatMap { l.mealsOn(today.plusDays(it.toLong())) }.mapNotNull { l.recipe(it.recipeId) }
                    if (ids.isNotEmpty()) Button(onClick = {
                        repo.life { it.withShopping(ids.flatMap { r -> r.ingredients }, "Supermarkt") }
                    }, modifier = Modifier.padding(top = 12.dp)) { Text("Zutaten der Woche auf die Einkaufsliste") }
                }
            }
        } else Box(Modifier.fillMaxSize()) {
            val list = l.activeRecipes()
            if (list.isEmpty()) Empty("Noch keine Rezepte. Mit + ein Rezept anlegen.")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp)) {
                items(list, key = { it.id }) { r ->
                    ItemCard(onClick = { viewRecipe = r }) {
                        Text(r.title, style = MaterialTheme.typography.bodyLarge)
                        Text("${r.ingredients.size} Zutaten · ${r.servings} Portionen" + if (r.tags.isNotBlank()) " · ${r.tags}" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            AddFab { editRecipe = Recipe(title = "") }
        }
    }
    viewRecipe?.let { r ->
        AlertDialog(onDismissRequest = { viewRecipe = null }, title = { Text(r.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Zutaten (${r.servings} Portionen)", style = MaterialTheme.typography.labelLarge)
                    r.ingredients.forEach { Text("• $it") }
                    if (r.steps.isNotBlank()) { Spacer(Modifier.height(10.dp)); Text("Zubereitung", style = MaterialTheme.typography.labelLarge); Text(r.steps) }
                }
            },
            confirmButton = { TextButton(onClick = { repo.life { it.withShopping(r.ingredients, "Supermarkt") }; viewRecipe = null }) { Text("Auf Einkaufsliste") } },
            dismissButton = { Row {
                TextButton(onClick = { editRecipe = r; viewRecipe = null }) { Text("Bearbeiten") }
                TextButton(onClick = { viewRecipe = null }) { Text("Schließen") }
            } })
    }
    editRecipe?.let { orig ->
        var r by remember(orig.id) { mutableStateOf(orig) }
        var ingredients by remember(orig.id) { mutableStateOf(orig.ingredients.joinToString("\n")) }
        val isNew = l.recipes.none { it.id == orig.id }
        EditDialog(if (isNew) "Neues Rezept" else "Rezept", { editRecipe = null },
            onSave = if (r.title.isNotBlank()) ({
                repo.life { it.copy(recipes = it.recipes.upsert(r.copy(ingredients = ingredients.lines().map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, updatedAt = now()))) }
            }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(recipes = it.recipes.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(r.title, { r = r.copy(title = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r.servings.toString(), { v -> v.toIntOrNull()?.let { r = r.copy(servings = it.coerceIn(1, 50)) } }, label = { Text("Portionen") }, singleLine = true)
            OutlinedTextField(ingredients, { ingredients = it }, label = { Text("Zutaten (eine pro Zeile)") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r.steps, { r = r.copy(steps = it) }, label = { Text("Zubereitung") }, minLines = 4, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(r.tags, { r = r.copy(tags = it) }, label = { Text("Stichworte (z. B. vegetarisch, schnell)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    planSlot?.let { (d, meal) ->
        val existing = l.mealsOn(d).firstOrNull { it.meal == meal }
        var free by remember(d, meal) { mutableStateOf(existing?.takeIf { it.recipeId == null }?.title.orEmpty()) }
        AlertDialog(onDismissRequest = { planSlot = null }, title = { Text("${meal.label}, ${d.format(DATE_DE)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(free, { free = it }, label = { Text("Freitext") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (l.activeRecipes().isNotEmpty()) Text("…oder Rezept wählen:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    l.activeRecipes().forEach { r ->
                        Text(r.title, Modifier.fillMaxWidth().clickable {
                            repo.life { it.copy(meals = it.meals.upsert((existing ?: MealPlan(date = d.toString(), meal = meal)).copy(recipeId = r.id, title = "", updatedAt = now()))) }
                            planSlot = null
                        }.padding(vertical = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton(enabled = free.isNotBlank(), onClick = {
                repo.life { it.copy(meals = it.meals.upsert((existing ?: MealPlan(date = d.toString(), meal = meal)).copy(recipeId = null, title = free.trim(), updatedAt = now()))) }
                planSlot = null
            }) { Text("Speichern") } },
            dismissButton = { Row {
                if (existing != null) TextButton(onClick = { repo.life { it.copy(meals = it.meals.upsert(existing.copy(deleted = true, updatedAt = now()))) }; planSlot = null }) {
                    Text("Leeren", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = { planSlot = null }) { Text("Abbrechen") }
            } })
    }
}

// ===================================================================== 4 Vorräte

@Composable
private fun PantryModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    var editing by remember { mutableStateOf<PantryItem?>(null) }
    val places = listOf("Kühlschrank", "Gefrierfach", "Vorrat", "Keller")
    Box(Modifier.fillMaxSize()) {
        val list = l.activePantry(today)
        if (list.isEmpty()) Empty("Trage ein, was du zu Hause hast – mit Haltbarkeit bekommst du rechtzeitig eine Erinnerung.")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp)) {
            list.groupBy { it.place }.forEach { (place, items) ->
                item(key = "h-$place") { SectionTitle(place) }
                items(items, key = { it.id }) { p ->
                    val n = p.daysLeft(today)
                    Row(Modifier.fillMaxWidth().clickable { editing = p }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(when {
                            n == null -> MaterialTheme.colorScheme.outlineVariant
                            n < 0 -> MaterialTheme.colorScheme.error; n <= 2 -> androidx.compose.ui.graphics.Color(0xFFF2A93B)
                            else -> MaterialTheme.colorScheme.primary }))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(p.name + if (p.amount.isNotBlank()) "  (${p.amount})" else "")
                            if (n != null) Text(when { n < 0 -> "seit ${-n} Tagen abgelaufen"; n == 0L -> "läuft heute ab"; n == 1L -> "läuft morgen ab"; else -> "noch $n Tage" },
                                style = MaterialTheme.typography.bodySmall, color = if (n <= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            repo.life { it.copy(pantry = it.pantry.upsert(p.copy(deleted = true, updatedAt = now()))) }
                        }) { Text("verbraucht") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        AddFab { editing = PantryItem(name = "") }
    }
    editing?.let { orig ->
        var p by remember(orig.id) { mutableStateOf(orig) }
        val isNew = l.pantry.none { it.id == orig.id }
        EditDialog(if (isNew) "Vorrat hinzufügen" else p.name, { editing = null },
            onSave = if (p.name.isNotBlank()) ({ repo.life { it.copy(pantry = it.pantry.upsert(p.copy(updatedAt = now()))) } }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(pantry = it.pantry.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(p.name, { p = p.copy(name = it) }, label = { Text("Was?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(p.amount, { p = p.copy(amount = it) }, label = { Text("Menge") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(places, p.place) { p = p.copy(place = it) }
            DateField("Haltbar bis", p.expires, { p = p.copy(expires = it) }, allowEmpty = true)
            if (!isNew) TextButton(onClick = { repo.life { it.withShopping(listOf(p.name)) }; editing = null }) { Text("Auf die Einkaufsliste") }
        }
    }
}

// ===================================================================== 5 Verträge

@Composable
private fun ContractsModule(repo: PlannerRepository, l: LifeData, today: LocalDate) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Contract?>(null) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 88.dp)) {
            item {
                ItemCard {
                    Text("Fixkosten", style = MaterialTheme.typography.labelLarge)
                    Text("${euro(l.contractsMonthlyCents())} / Monat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("${euro(l.contractsMonthlyCents() * 12)} / Jahr", style = MaterialTheme.typography.bodySmall)
                }
            }
            val list = l.activeContracts().sortedBy { it.cancelBy(today) ?: LocalDate.MAX }
            if (list.isEmpty()) item { Empty("Lege Verträge an (Handy, Strom, Versicherungen …). Die App erinnert dich vor Ablauf der Kündigungsfrist.") }
            items(list, key = { it.id }) { c ->
                val cancel = c.cancelBy(today)
                val days = cancel?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
                ItemCard(onClick = { editing = c }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                            Text(listOf(c.provider, c.category).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(euro(c.cents) + if (c.intervalMonths == 12) "/J" else if (c.intervalMonths == 1) "/M" else "/${c.intervalMonths}M")
                    }
                    when {
                        cancel == null -> Text("unbefristet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text("Kündigen bis ${cancel.format(DATE_DE)}" + (if (days != null && days >= 0) " (noch $days T)" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (days != null && days in 0..30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (c.document.startsWith("http")) TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(c.document))) }
                    }) { Text("Dokument öffnen") }
                    else if (c.document.isNotBlank()) Text("📎 ${c.document}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AddFab { editing = Contract(name = "") }
    }
    editing?.let { orig ->
        var c by remember(orig.id) { mutableStateOf(orig) }
        var cost by remember(orig.id) { mutableStateOf(if (orig.cents == 0L) "" else euro(orig.cents).removeSuffix(" €")) }
        val isNew = l.contracts.none { it.id == orig.id }
        EditDialog(if (isNew) "Neuer Vertrag" else c.name, { editing = null },
            onSave = if (c.name.isNotBlank()) ({ repo.life { it.copy(contracts = it.contracts.upsert(c.copy(cents = parseEuro(cost) ?: 0, updatedAt = now()))) } }) else null,
            onDelete = if (isNew) null else ({ repo.life { it.copy(contracts = it.contracts.upsert(orig.copy(deleted = true, updatedAt = now()))) } })) {
            OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text("Vertrag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.provider, { c = c.copy(provider = it) }, label = { Text("Anbieter") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(listOf("Handy & Internet", "Strom & Gas", "Versicherung", "Streaming", "Wohnen", "Mobilität", "Sonstiges"), c.category) { c = c.copy(category = it) }
            OutlinedTextField(cost, { cost = it }, label = { Text("Kosten (€)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChoiceChips(listOf("monatlich", "vierteljährlich", "jährlich"), when (c.intervalMonths) { 3 -> "vierteljährlich"; 12 -> "jährlich"; else -> "monatlich" }) {
                c = c.copy(intervalMonths = when (it) { "vierteljährlich" -> 3; "jährlich" -> 12; else -> 1 })
            }
            DateField("Laufzeit bis", c.termEnd, { c = c.copy(termEnd = it) }, allowEmpty = true)
            if (c.termEnd != null) {
                Text("Verlängert sich automatisch um", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf("endet", "1 Monat", "12 Monate", "24 Monate"), when (c.renewMonths) { 0 -> "endet"; 1 -> "1 Monat"; 24 -> "24 Monate"; else -> "12 Monate" }) {
                    c = c.copy(renewMonths = when (it) { "endet" -> 0; "1 Monat" -> 1; "24 Monate" -> 24; else -> 12 })
                }
                Text("Kündigungsfrist", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(listOf("14 Tage", "1 Monat", "3 Monate"), when (c.noticeDays) { 14 -> "14 Tage"; 90 -> "3 Monate"; else -> "1 Monat" }) {
                    c = c.copy(noticeDays = when (it) { "14 Tage" -> 14; "3 Monate" -> 90; else -> 30 })
                }
            }
            OutlinedTextField(c.document, { c = c.copy(document = it) }, label = { Text("Dokument (Link oder Nextcloud-Pfad)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(c.note, { c = c.copy(note = it) }, label = { Text("Notiz (Kundennr. …)") }, modifier = Modifier.fillMaxWidth())
        }
    }
}
