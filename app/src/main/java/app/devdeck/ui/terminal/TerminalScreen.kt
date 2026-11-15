package app.devdeck.ui.terminal

import android.annotation.SuppressLint
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
                        DropdownMenuItem(text = { Text("tmux-Sitzung (bleibt bei Abbruch erhalten)") }, enabled = connected, onClick = {
                            menuOpen = false; vm.send("tmux new -A -s devdeck\r")
                        })
                    }
                }
            },
        )

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

        ExtraKeys(ctrl = ctrl, onCtrl = vm::toggleCtrl, onKey = { vm.send(it) }, onFocus = {
            webView?.evaluateJavascript("termFocus()", null)
        })
    }
}

@Composable
private fun ExtraKeys(ctrl: Boolean, onCtrl: () -> Unit, onKey: (String) -> Unit, onFocus: () -> Unit) {
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
                setBackgroundColor(0xFF14161C.toInt())
                val web = this
                addJavascriptInterface(object {
                    @JavascriptInterface fun onInput(d: String) = onInput(d)
                    @JavascriptInterface fun onResize(c: Int, r: Int) = onResize(c, r)
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
