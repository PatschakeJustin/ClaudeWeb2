package app.devdeck.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.devdeck.DevDeckApp
import app.devdeck.ui.theme.MonoFamily
import java.io.File

/** After a crash: shows the recorded error so it can be copied or shared. */
@Composable
fun CrashDialog() {
    val context = LocalContext.current
    val file = remember { File(context.filesDir, DevDeckApp.CRASH_FILE) }
    var report by remember { mutableStateOf(if (file.exists()) runCatching { file.readText() }.getOrNull() else null) }
    val text = report ?: return
    fun close() { file.delete(); report = null }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("Die App ist abgestürzt") },
        text = {
            Column {
                Text("Bitte den Fehlerbericht kopieren und an den Entwickler schicken – damit lässt sich der Fehler gezielt beheben.")
                Text(
                    text, fontFamily = MonoFamily, fontSize = 10.sp, lineHeight = 12.sp,
                    modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Absturzbericht", text))
                Toast.makeText(context, "Bericht kopiert", Toast.LENGTH_SHORT).show()
            }) { Text("Kopieren") }
            TextButton(onClick = {
                context.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Bericht teilen",
                ))
            }) { Text("Teilen") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Schließen") } },
    )
}
