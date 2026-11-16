package app.devdeck.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.devdeck.DevDeckApp
import app.devdeck.data.WidgetDef
import app.devdeck.data.WidgetKind
import app.devdeck.data.WidgetResult
import app.devdeck.ui.MainActivity
import app.devdeck.ui.home.WidgetColors
import app.devdeck.ui.theme.DevDeckTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Home-screen widgets: which app widget shows which widget definition, plus background refresh. */
object HomeWidgets {
    private const val PREFS = "home_widgets"
    const val EXTRA_PROMPT = "app.devdeck.PROMPT"

    fun binding(context: Context, appWidgetId: Int): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("w$appWidgetId", null)

    fun bind(context: Context, appWidgetId: Int, defId: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("w$appWidgetId", defId).apply()

    fun boundDefIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.values.filterIsInstance<String>().toSet()

    suspend fun updateAll(context: Context) = runCatching { HumantizeGlanceWidget().updateAll(context) }

    /** Periodic refresh (every 15 minutes, widgets respect their own interval) while home widgets exist. */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<WidgetWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("home-widgets", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun refreshNow(context: Context, defId: String? = null) {
        val req = OneTimeWorkRequestBuilder<WidgetWorker>().setInputData(workDataOf("id" to defId)).build()
        WorkManager.getInstance(context).enqueue(req)
    }
}

/** Runs the widgets' commands over SSH and redraws the home-screen widgets. */
class WidgetWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DevDeckApp).container
        val only = inputData.getString("id")
        val ids = if (only != null) setOf(only) else HomeWidgets.boundDefIds(applicationContext)
        val now = System.currentTimeMillis()
        ids.mapNotNull { c.widgets.get(it) }
            .filter { it.kind != WidgetKind.AI }
            .filter { w -> only != null || w.kind != WidgetKind.BUTTON && (c.widgets.results.value[w.id]?.at ?: 0) + w.refreshMinutes * 60_000L - 30_000 < now }
            .forEach { c.widgetRunner.run(it) }
        HomeWidgets.updateAll(applicationContext)
        return Result.success()
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        HomeWidgets.refreshNow(context, parameters[DefKey])
    }
    companion object { val DefKey = ActionParameters.Key<String>("def") }
}

class HumantizeGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val container = (context.applicationContext as DevDeckApp).container
        provideContent {
            val defs by container.widgets.widgets.collectAsState()
            val results by container.widgets.results.collectAsState()
            val def = HomeWidgets.binding(context, appWidgetId)?.let { b -> defs.firstOrNull { it.id == b } }
            WidgetBody(context, def, def?.let { results[it.id] })
        }
    }
}

private val Bg = ColorProvider(Color(0xFF171B22))
private val Fg = ColorProvider(Color(0xFFE6E8EC))
private val Dim = ColorProvider(Color(0xFFAAB2BF))

@androidx.compose.runtime.Composable
private fun WidgetBody(context: Context, def: WidgetDef?, r: WidgetResult?) {
    val click = when {
        def == null -> actionStartActivity<MainActivity>()
        def.kind == WidgetKind.AI -> actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra(HomeWidgets.EXTRA_PROMPT, def.prompt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        else -> actionRunCallback<RefreshAction>(actionParametersOf(RefreshAction.DefKey to def.id))
    }
    Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(18.dp).padding(12.dp).clickable(click)) {
        val accent = def?.let { WidgetColors[it.color.mod(WidgetColors.size)] } ?: Color(0xFF5AA2FF)
        Text(def?.title ?: "Humantize", style = TextStyle(color = ColorProvider(accent), fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
        Spacer(GlanceModifier.height(6.dp))
        val body = when {
            def == null -> "Widget nicht mehr vorhanden. Lange drücken, um ein anderes zu wählen."
            def.kind == WidgetKind.AI -> def.prompt
            def.kind == WidgetKind.BUTTON -> r?.text ?: "Tippen zum Ausführen"
            else -> r?.text ?: "Tippen zum Laden"
        }
        Text(body, style = TextStyle(color = if (r != null && !r.ok) ColorProvider(Color(0xFFFF6E8F)) else Fg, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace), maxLines = 8)
        Spacer(GlanceModifier.defaultWeight())
        Row {
            Text((r?.let { SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(it.at)) } ?: "") + "  ↻",
                style = TextStyle(color = Dim, fontSize = 10.sp))
        }
    }
}

class HumantizeWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HumantizeGlanceWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        HomeWidgets.schedule(context)
    }
}

/** Opens when a widget is placed on the home screen: choose which widget it shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val c = (application as DevDeckApp).container
        setContent {
            DevDeckTheme {
                val defs by c.widgets.widgets.collectAsState()
                val scope = rememberCoroutineScope()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Welches Widget anzeigen?", style = MaterialTheme.typography.titleLarge)
                        if (defs.isEmpty()) {
                            Text("Du hast noch keine Widgets. Erstelle sie in der App unter „Start“.")
                            Button(onClick = { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)); finish() }) {
                                Text("App öffnen")
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(defs, key = { it.id }) { d ->
                                Card(Modifier.fillMaxWidth().clickable {
                                    HomeWidgets.bind(this@WidgetConfigActivity, appWidgetId, d.id)
                                    HomeWidgets.schedule(this@WidgetConfigActivity)
                                    HomeWidgets.refreshNow(this@WidgetConfigActivity, d.id)
                                    scope.launch {
                                        HomeWidgets.updateAll(this@WidgetConfigActivity)
                                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
                                        finish()
                                    }
                                }) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(d.title, style = MaterialTheme.typography.titleMedium)
                                        Text(d.kind.label, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
