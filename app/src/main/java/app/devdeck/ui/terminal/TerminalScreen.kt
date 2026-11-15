package app.devdeck.ui.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.devdeck.ui.Dest
import app.devdeck.ui.common.containerViewModel
import app.devdeck.ui.theme.MonoFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(navigate: (Dest) -> Unit) {
    val vm = containerViewModel { TerminalViewModel(it) }
    val status by vm.status.collectAsState()
    val connections by vm.connections.collectAsState()
    val settings by vm.settings.collectAsState()
    val ctrl by vm.ctrlArmed.collectAsState()
    var pickerOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var links by remember { mutableStateOf<List<String>?>(null) }
    val context = LocalContext.current
    val login by vm.login.collectAsState()

    LaunchedEffect(vm) {
        vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    // Open the login page as soon as it is known (state, not a one-shot event, so it is never missed).
    val detected by vm.detected.collectAsState()
    val loginUrl = detected.url
    LaunchedEffect(loginUrl) {
        if (loginUrl != null && vm.shouldOpen(loginUrl)) openLink(context, loginUrl)
    }
    // Second source while logging in: scan the rendered terminal buffer for links.
    val loginActive = login != null
    LaunchedEffect(loginActive, webView) {
        while (loginActive) {
            webView?.evaluateJavascript("termLinks()") { raw -> decodeJsList(raw).reversed().takeIf { it.isNotEmpty() }?.let(vm::onBufferLinks) }
            kotlinx.coroutines.delay(1500)
        }
    }

    val title = when (val s = status) {
        TerminalViewModel.Status.Idle -> "Terminal"
        is TerminalViewModel.Status.Connecting -> "${s.name} …"
        is TerminalViewModel.Status.Connected -> s.name
        is TerminalViewModel.Status.Closed -> "${s.name} (getrennt)"
    }
    val connected = status is TerminalViewModel.Status.Connected

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Box {
                    TextButton(onClick = { pickerOpen = true }) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Icon(Icons.Filled.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = pickerOpen, onDismissRequest = { pickerOpen = false }) {
                        if (connections.ssh.isEmpty()) {
                            DropdownMenuItem(text = { Text("SSH-Verbindung anlegen …") }, onClick = {
                                pickerOpen = false; navigate(Dest.CONNECTIONS)
                            })
                        }
                        connections.ssh.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.name}  (${c.username}@${c.host})") },
                                onClick = { pickerOpen = false; vm.connect(c) },
                            )
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = {
                    webView?.evaluateJavascript("termLinks()") { raw -> links = decodeJsList(raw) }
                }) { Icon(Icons.Filled.Link, "Links") }
                IconButton(onClick = { vm.setFontSize(settings.terminalFontSize - 1) }) { Icon(Icons.Filled.TextDecrease, "Kleiner") }
                IconButton(onClick = { vm.setFontSize(settings.terminalFontSize + 1) }) { Icon(Icons.Filled.TextIncrease, "Größer") }
                if (connected) IconButton(onClick = vm::disconnect) { Icon(Icons.Filled.LinkOff, "Trennen") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Mehr") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Claude Code starten") }, enabled = connected, onClick = {
                            menuOpen = false; vm.send("claude\r")
                        })
                        DropdownMenuItem(text = { Text("Claude Code fortsetzen (--continue)") }, enabled = connected, onClick = {
                            menuOpen = false; vm.send("claude --continue\r")
                        })
                        DropdownMenuItem(text = { Text("Claude Code installieren") }, enabled = connected, onClick = {
                            menuOpen = false; vm.send("curl -fsSL https://claude.ai/install.sh | bash\r")
                        })
                        DropdownMenuItem(text = { Text("Alle neu anmelden") }, enabled = status !is TerminalViewModel.Status.Idle, onClick = {
                            menuOpen = false; vm.reloginAll()
                        })
                        DropdownMenuItem(text = { Text("tmux-Sitzung (bleibt bei Abbruch erhalten)") }, enabled = connected, onClick = {
                            menuOpen = false; vm.send("tmux new -A -s devdeck\r")
                        })
                    }
                }
            },
        )

        val st = login
        if (st != null) LoginBanner(st, detected, vm, context)
        else detected.url?.let { DetectedBanner(it, vm, context) }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            TerminalWebView(
                fontSize = settings.terminalFontSize,
                onCreated = { webView = it },
                onInput = vm::onInput,
                onResize = vm::onResize,
                attach = vm::attach,
                detach = vm::detach,
            )
            if (status == TerminalViewModel.Status.Idle) {
                Surface(Modifier.align(Alignment.Center).padding(24.dp), tonalElevation = 2.dp, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Server wählen", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Oben auf „Terminal“ tippen und eine SSH-Verbindung auswählen. " +
                                "Über ⋮ startest du dort Claude Code.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        ExtraKeys(
            ctrl = ctrl, onCtrl = vm::toggleCtrl, onKey = { vm.send(it) },
            onFocus = { webView?.evaluateJavascript("termFocus()", null) },
            onPaste = { clipboardText(context)?.let { vm.send(it) } },
            onCopy = {
                webView?.evaluateJavascript("termSelection()") { raw ->
                    val text = decodeJsString(raw)
                    if (text.isNotEmpty()) { copyText(context, text); Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show() }
                    else Toast.makeText(context, "Erst Text im Terminal markieren (lange drücken)", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    links?.let { list ->
        AlertDialog(
            onDismissRequest = { links = null },
            title = { Text("Links im Terminal") },
            text = {
                if (list.isEmpty()) Text("Keine Links gefunden.")
                else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    list.forEach { u ->
                        Column {
                            Text(u, fontFamily = MonoFamily, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                            Row {
                                TextButton(onClick = { openLink(context, u); links = null }) { Text("Öffnen") }
                                TextButton(onClick = { copyText(context, u); Toast.makeText(context, "Kopiert", Toast.LENGTH_SHORT).show() }) { Text("Kopieren") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { links = null }) { Text("Schließen") } },
        )
    }
}

/**
 * Opens [url] in the default web browser – explicitly, so that apps claiming the domain (e.g. the Claude app
 * for claude.ai) do not swallow the login page.
 */
private fun openLink(context: Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addCategory(Intent.CATEGORY_BROWSABLE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val browser = runCatching {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        context.packageManager.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" }
    }.getOrNull()
    try {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "Anmeldeseite wird im Browser geöffnet …", Toast.LENGTH_SHORT).show() }
        try {
            context.startActivity(if (browser != null) Intent(intent).setPackage(browser) else intent)
        } catch (_: Exception) {
            context.startActivity(intent)
        }
    } catch (_: Exception) {
        copyText(context, url)
        Handler(Looper.getMainLooper()).post { Toast.makeText(context, "Kein Browser gefunden – Link kopiert", Toast.LENGTH_SHORT).show() }
    }
}

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Humantize", text))
}

private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.takeIf { it.isNotEmpty() }
}

/** evaluateJavascript returns the JS value JSON-encoded; a returned string is therefore a JSON string literal. */
private fun decodeJsString(raw: String?): String =
    runCatching { Json.parseToJsonElement(raw ?: "null").jsonPrimitive.contentOrNull }.getOrNull().orEmpty()

private fun decodeJsList(raw: String?): List<String> =
    runCatching { Json.parseToJsonElement(decodeJsString(raw)).jsonArray.map { it.jsonPrimitive.content } }.getOrDefault(emptyList())

@Composable
private fun DetectedBanner(url: String, vm: TerminalViewModel, context: Context) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text("Anmeldelink erkannt", style = MaterialTheme.typography.titleSmall)
            Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = MonoFamily, maxLines = 2)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { openLink(context, url) }) { Text("Seite öffnen") }
                TextButton(onClick = { copyText(context, url); Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show() }) { Text("Link kopieren") }
                TextButton(onClick = {
                    val clip = clipboardText(context)
                    if (clip == null) Toast.makeText(context, "Zwischenablage ist leer", Toast.LENGTH_SHORT).show() else vm.pasteAndEnter(clip)
                }) { Text("Code einfügen") }
                TextButton(onClick = vm::dismissDetected) { Text("Schließen") }
            }
        }
    }
}

@Composable
private fun LoginBanner(st: TerminalViewModel.LoginState, det: TerminalViewModel.Detected, vm: TerminalViewModel, context: Context) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                "Anmelden: ${st.step.label}" + if (st.steps.size > 1) "  (${st.index + 1}/${st.steps.size})" else "",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                when {
                    st.done -> if (st.isLast) "✓ Angemeldet. Fertig in 3 s …" else "✓ Angemeldet. Weiter mit ${st.steps[st.index + 1].label} in 3 s …"
                    det.code != null -> "Anmeldeseite geöffnet. Dort diesen Code eingeben: ${det.code}"
                    det.url != null -> "Anmeldeseite geöffnet. Nach dem Anmelden den angezeigten Code kopieren und hier „Code einfügen“ tippen. " +
                        "Kommt kein Code, ist die Anmeldung schon fertig."
                    else -> "Warte auf den Anmeldelink … Fragt das Programm etwas (z. B. Farbschema oder Anmeldeart), " +
                        "mit ↑/↓ und Enter wählen: „Claude account with subscription“ bzw. „Login with Google“ / „Qwen OAuth“."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                det.url?.let { u ->
                    TextButton(onClick = { openLink(context, u) }) { Text("Seite öffnen") }
                    TextButton(onClick = { copyText(context, u); Toast.makeText(context, "Link kopiert", Toast.LENGTH_SHORT).show() }) { Text("Link kopieren") }
                }
                det.code?.let { c ->
                    TextButton(onClick = { copyText(context, c); Toast.makeText(context, "Code kopiert", Toast.LENGTH_SHORT).show() }) { Text("Code kopieren") }
                }
                TextButton(onClick = {
                    val clip = clipboardText(context)
                    if (clip == null) Toast.makeText(context, "Zwischenablage ist leer – Code auf der Anmeldeseite kopieren", Toast.LENGTH_LONG).show()
                    else vm.pasteAndEnter(clip)
                }) { Text("Code einfügen") }
                TextButton(onClick = vm::nextLoginStep) { Text(if (st.isLast) "Fertig" else "Weiter") }
                TextButton(onClick = vm::finishLogin) { Text("Abbrechen") }
            }
        }
    }
}

@Composable
private fun ExtraKeys(
    ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit, onFocus: () -> Unit,
    onPaste: () -> Unit, onCopy: () -> Unit,
) {
    val keys = listOf(
        "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
        "^C" to "\u0003", "^D" to "\u0004", "^R" to "\u0012", "|" to "|", "/" to "/", "~" to "~", "-" to "-",
        "⇧Tab" to "\u001b[Z", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~",
    )
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = ctrl, onClick = onCtrl, label = { Text("Ctrl", fontFamily = MonoFamily) })
            TextButton(onClick = { onPaste(); onFocus() }) { Text("Einfügen") }
            TextButton(onClick = onCopy) { Text("Kopieren") }
            keys.forEach { (label, seq) ->
                TextButton(onClick = { onKey(seq); onFocus() }) { Text(label, fontFamily = MonoFamily) }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TerminalWebView(
    fontSize: Int,
    onCreated: (WebView) -> Unit,
    onInput: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    attach: ((ByteArray) -> Unit) -> Unit,
    detach: () -> Unit,
) {
    val main = remember { Handler(Looper.getMainLooper()) }
    var view by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(0xFF000000.toInt())
                val web = this
                addJavascriptInterface(object {
                    @JavascriptInterface fun onInput(d: String) = onInput(d)
                    @JavascriptInterface fun onResize(c: Int, r: Int) = onResize(c, r)
                    @JavascriptInterface fun openUrl(u: String) = openLink(ctx, u)
                    @JavascriptInterface fun onReady(c: Int, r: Int) {
                        onResize(c, r)
                        main.post {
                            web.evaluateJavascript("termSetFontSize($fontSize)", null)
                            attach { bytes ->
                                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                main.post { web.evaluateJavascript("termWrite('$b64')", null) }
                            }
                        }
                    }
                }, "Bridge")
                loadUrl("file:///android_asset/terminal/index.html")
                onCreated(this)
                view = this
            }
        },
    )

    LaunchedEffect(fontSize, view) { view?.evaluateJavascript("termSetFontSize($fontSize)", null) }

    DisposableEffect(Unit) {
        onDispose {
            detach()
            view?.destroy()
        }
    }
}
