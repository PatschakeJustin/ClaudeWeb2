package app.devdeck.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.devdeck.ui.theme.DevDeckTheme
import app.devdeck.DevDeckApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // After a crash: show the report with plain Android views (no Compose, no app container), so that
        // even a crash during startup can be read and sent in instead of ending in a crash loop.
        val crash = java.io.File(filesDir, DevDeckApp.CRASH_FILE)
        if (crash.exists()) {
            showCrashReport(crash)
            return
        }
        // Status/navigation bar icons follow the light/dark system theme.
        enableEdgeToEdge()
        handleWidgetIntent(intent)
        runCatching { app.devdeck.widget.HomeWidgets.schedule(this) }
        runCatching { app.devdeck.widget.Reminders.schedule(this) }
        runCatching { app.devdeck.widget.PrayerAlarms.schedule(this) }
        setContent {
            DevDeckTheme { DevDeckRoot() }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleWidgetIntent(intent)
    }

    /** A "KI-Schnellfrage" home widget opens the chat with its prompt. */
    private fun handleWidgetIntent(intent: android.content.Intent?) {
        intent?.getStringExtra(app.devdeck.widget.HomeWidgets.EXTRA_OPEN)?.let { name ->
            Dest.entries.firstOrNull { it.name == name }?.let { (application as DevDeckApp).container.navRequest.value = it }
            return
        }
        val prompt = intent?.getStringExtra(app.devdeck.widget.HomeWidgets.EXTRA_PROMPT) ?: return
        val c = (application as DevDeckApp).container
        c.pendingPrompt.value = prompt
        c.navRequest.value = Dest.AGENT
    }

    private fun showCrashReport(file: java.io.File) {
        val text = runCatching { file.readText() }.getOrDefault("(Bericht nicht lesbar)")
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        fun label(t: String, size: Float, color: Int) = android.widget.TextView(this).apply {
            this.text = t; textSize = size; setTextColor(color); setPadding(0, 0, 0, pad / 2)
        }
        root.addView(label("Die App ist abgestürzt", 22f, 0xFFFF9C3D.toInt()))
        root.addView(label("Bitte den Bericht kopieren oder teilen und an den Entwickler schicken.", 15f, 0xFFF2E6D9.toInt()))
        val buttons = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun button(t: String, onClick: () -> Unit) = android.widget.Button(this).apply { this.text = t; setOnClickListener { onClick() } }
        buttons.addView(button("Kopieren") {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("Absturzbericht", text))
            android.widget.Toast.makeText(this, "Bericht kopiert", android.widget.Toast.LENGTH_SHORT).show()
        })
        buttons.addView(button("Teilen") {
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, text), "Bericht teilen"))
        })
        buttons.addView(button("App starten") { file.delete(); recreate() })
        root.addView(buttons)
        val report = android.widget.TextView(this).apply {
            this.text = text; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFF2E6D9.toInt()); setTextIsSelectable(true)
        }
        root.addView(android.widget.ScrollView(this).apply { addView(report) },
            android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
