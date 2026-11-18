package app.devdeck.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.devdeck.core.planner.AddressBar
import app.devdeck.core.planner.Bookmark
import app.devdeck.core.planner.now
import app.devdeck.ui.appContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One open tab. The WebView lives as long as the app (like the terminal), so pages survive switching screens. */
class BrowserTab(val id: Long, val web: WebView) {
    val url = MutableStateFlow("")
    val title = MutableStateFlow("Neuer Tab")
    val progress = MutableStateFlow(100)
    val canBack = MutableStateFlow(false)
    val canForward = MutableStateFlow(false)
}

@Serializable data class HistoryEntry(val url: String, val title: String, val at: Long)

/** All tabs, the local history and the hooks the WebViews need from the visible activity (file upload, fullscreen). */
class BrowserState(private val app: Context) {
    val tabs = MutableStateFlow<List<BrowserTab>>(emptyList())
    val current = MutableStateFlow<Long?>(null)
    val history = MutableStateFlow(loadHistory())
    /** Fullscreen video view (from onShowCustomView). */
    val fullscreen = MutableStateFlow<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    /** Set by the screen; called when a page asks for a file. */
    var fileChooser: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null
    private var nextId = 1L
    var desktop = false
        set(v) { field = v; tabs.value.forEach { applyUserAgent(it.web) } }

    fun tab(id: Long?) = tabs.value.firstOrNull { it.id == id }

    @SuppressLint("SetJavaScriptEnabled")
    fun open(url: String): BrowserTab {
        val web = WebView(MutableContextWrapper(app))
        val tab = BrowserTab(nextId++, web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        defaultUA = defaultUA ?: web.settings.userAgentString
        applyUserAgent(web)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.scheme == "http" || u.scheme == "https" || u.scheme == "about" || u.scheme == "data") return false
                // mailto:, tel:, intent:, market: … → other apps
                return runCatching {
                    val i = if (u.scheme == "intent") Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME) else Intent(Intent.ACTION_VIEW, u)
                    view.context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
                }.getOrDefault(true)
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { tab.url.value = url; update(tab) }
            override fun onPageFinished(view: WebView, url: String) {
                tab.url.value = url; update(tab)
                if (url.startsWith("http")) addHistory(url, view.title ?: url)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { tab.progress.value = p; update(tab) }
            override fun onReceivedTitle(view: WebView, t: String?) { tab.title.value = t?.ifBlank { null } ?: tab.url.value }
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
                fileChooser?.invoke(cb, params) ?: run { cb.onReceiveValue(null); true }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { fullscreenCallback = callback; fullscreen.value = view }
            override fun onHideCustomView() { exitFullscreen() }
        }
        web.setDownloadListener { dlUrl, ua, disposition, mime, _ -> download(web.context, dlUrl, ua, disposition, mime) }
        tabs.update { it + tab }
        current.value = tab.id
        if (url.isNotBlank()) web.loadUrl(url)
        return tab
    }

    fun close(id: Long) {
        val t = tab(id) ?: return
        tabs.update { it - t }
        (t.web.parent as? ViewGroup)?.removeView(t.web)
        t.web.destroy()
        if (current.value == id) current.value = tabs.value.lastOrNull()?.id
    }

    fun exitFullscreen() { fullscreenCallback?.onCustomViewHidden(); fullscreenCallback = null; fullscreen.value = null }

    private fun update(t: BrowserTab) { t.canBack.value = t.web.canGoBack(); t.canForward.value = t.web.canGoForward() }

    private var defaultUA: String? = null
    private fun applyUserAgent(web: WebView) {
        web.settings.userAgentString = if (desktop)
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36" else defaultUA
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
    }

    private fun download(ctx: Context, url: String, ua: String?, disposition: String?, mime: String?) {
        val name = URLUtil.guessFileName(url, disposition, mime)
        try {
            val req = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mime); addRequestHeader("User-Agent", ua ?: "")
                CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                setTitle(name)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            }
            (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Toast.makeText(app, "Download gestartet: $name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Android 7–9 may need storage permission for the Downloads folder: let another app handle it.
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---- history (only on this device)
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs get() = app.getSharedPreferences("browser", 0)
    private fun loadHistory(): List<HistoryEntry> = runCatching {
        json.decodeFromString(ListSerializer(HistoryEntry.serializer()), app.getSharedPreferences("browser", 0).getString("history", "[]")!!)
    }.getOrDefault(emptyList())
    private fun addHistory(url: String, title: String) {
        history.update { (listOf(HistoryEntry(url, title, System.currentTimeMillis())) + it.filter { h -> h.url != url }).take(300) }
        prefs.edit().putString("history", json.encodeToString(ListSerializer(HistoryEntry.serializer()), history.value)).apply()
    }
    fun clearData() {
        history.value = emptyList(); prefs.edit().remove("history").apply()
        CookieManager.getInstance().removeAllCookies(null)
        android.webkit.WebStorage.getInstance().deleteAllData()
        tabs.value.forEach { it.web.clearCache(true); it.web.clearHistory() }
    }
}

private fun Context.findActivity(): Activity? { var c = this; while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }; return null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(onClose: () -> Unit) {
    val c = appContainer()
    val state = c.browser
    val context = LocalContext.current
    val settings by c.settings.state.collectAsState()
    val engine = runCatching { AddressBar.Engine.valueOf(settings.browserEngine) }.getOrDefault(AddressBar.Engine.DUCKDUCKGO)
    val home = settings.browserHome.ifBlank { "https://duckduckgo.com" }
    LaunchedEffect(settings.browserDesktop) { state.desktop = settings.browserDesktop }

    val tabs by state.tabs.collectAsState()
    val currentId by state.current.collectAsState()
    LaunchedEffect(Unit) { if (state.tabs.value.isEmpty()) state.open(home) }
    val tab = tabs.firstOrNull { it.id == currentId } ?: tabs.lastOrNull()
    val fullscreen by state.fullscreen.collectAsState()

    // File upload for <input type=file>
    var pendingFile by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pendingFile?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)); pendingFile = null
    }
    DisposableEffect(Unit) {
        state.fileChooser = { cb, params ->
            pendingFile?.onReceiveValue(null); pendingFile = cb
            runCatching { picker.launch(params.createIntent()) }.isSuccess.also { if (!it) { cb.onReceiveValue(null); pendingFile = null } }
        }
        onDispose { state.fileChooser = null }
    }

    var showTabs by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    val fs = fullscreen
    if (fs != null) {
        BackHandler { state.exitFullscreen() }
        AndroidView({ FrameLayout(it).apply { setBackgroundColor(android.graphics.Color.BLACK) } }, Modifier.fillMaxSize(),
            update = { f -> if (f.getChildAt(0) !== fs) { f.removeAllViews(); (fs.parent as? ViewGroup)?.removeView(fs); f.addView(fs) } })
        return
    }
    if (tab == null) return

    val url by tab.url.collectAsState()
    val progress by tab.progress.collectAsState()
    val canBack by tab.canBack.collectAsState()
    val canForward by tab.canForward.collectAsState()
    val title by tab.title.collectAsState()
    var address by remember(tab.id) { mutableStateOf("") }
    var editingAddress by remember { mutableStateOf(false) }
    LaunchedEffect(url) { if (!editingAddress) address = url }
    val bookmarks = c.planner.data.collectAsState().value.life.bookmarks.filter { !it.deleted }
    val bookmarked = bookmarks.any { it.url == url }

    BackHandler { if (tab.web.canGoBack()) tab.web.goBack() else onClose() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Browser schließen") }
            OutlinedTextField(
                address, { address = it; editingAddress = true }, singleLine = true,
                placeholder = { Text("Suchen oder Adresse eingeben") },
                leadingIcon = { Icon(if (url.startsWith("https://")) Icons.Filled.Lock else Icons.Filled.Public, null, Modifier.size(18.dp)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { editingAddress = false; tab.web.loadUrl(AddressBar.toUrl(address, engine)) }),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            )
            IconButton(onClick = { showTabs = true }) {
                BadgedBox(badge = { Badge { Text("${tabs.size}") } }) { Icon(Icons.Filled.Tab, "Tabs") }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menü") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Neuer Tab") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { menu = false; state.open(home) })
                    DropdownMenuItem(text = { Text(if (bookmarked) "Lesezeichen entfernen" else "Lesezeichen hinzufügen") },
                        leadingIcon = { Icon(if (bookmarked) Icons.Filled.Star else Icons.Filled.StarBorder, null) }, onClick = {
                            menu = false
                            c.planner.update { p ->
                                val l = p.life
                                p.copy(life = l.copy(bookmarks = if (bookmarked) l.bookmarks.map { if (it.url == url && !it.deleted) it.copy(deleted = true, updatedAt = now()) else it }
                                    else l.bookmarks + Bookmark(title = title.ifBlank { url }, url = url)))
                            }
                        })
                    DropdownMenuItem(text = { Text("Lesezeichen & Verlauf") }, leadingIcon = { Icon(Icons.Filled.Bookmarks, null) }, onClick = { menu = false; showBookmarks = true })
                    DropdownMenuItem(text = { Text("Desktop-Ansicht") }, leadingIcon = { Checkbox(settings.browserDesktop, null) }, onClick = {
                        menu = false; c.settings.update { it.copy(browserDesktop = !it.browserDesktop) }; tab.web.reload()
                    })
                    DropdownMenuItem(text = { Text("Teilen") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = {
                        menu = false
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), "Link teilen"))
                    })
                    DropdownMenuItem(text = { Text("In anderer App öffnen") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = {
                        menu = false; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    })
                    DropdownMenuItem(text = { Text("Als Startseite festlegen") }, leadingIcon = { Icon(Icons.Filled.Home, null) }, onClick = {
                        menu = false; c.settings.update { it.copy(browserHome = url) }
                    })
                    HorizontalDivider()
                    Text("Suchmaschine", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    AddressBar.Engine.entries.forEach { e ->
                        DropdownMenuItem(text = { Text(e.label) }, leadingIcon = { RadioButton(e == engine, null) }, onClick = {
                            menu = false; c.settings.update { it.copy(browserEngine = e.name) }
                        })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Verlauf, Cookies & Cache löschen") }, leadingIcon = { Icon(Icons.Filled.DeleteSweep, null) }, onClick = {
                        menu = false; state.clearData(); Toast.makeText(context, "Browserdaten gelöscht", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
        if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(tab.id) {
                AndroidView(
                    factory = { ctx ->
                        (tab.web.context as? MutableContextWrapper)?.baseContext = ctx.findActivity() ?: ctx
                        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
                        FrameLayout(ctx).apply { addView(tab.web, FrameLayout.LayoutParams(-1, -1)) }
                    },
                    onRelease = { f -> f.removeAllViews(); (tab.web.context as? MutableContextWrapper)?.baseContext = context.applicationContext },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(enabled = canBack, onClick = { tab.web.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            IconButton(enabled = canForward, onClick = { tab.web.goForward() }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Vor") }
            IconButton(onClick = { if (progress < 100) tab.web.stopLoading() else tab.web.reload() }) {
                Icon(if (progress < 100) Icons.Filled.Close else Icons.Filled.Refresh, if (progress < 100) "Stopp" else "Neu laden")
            }
            IconButton(onClick = { tab.web.loadUrl(home) }) { Icon(Icons.Filled.Home, "Startseite") }
            IconButton(onClick = { showBookmarks = true }) { Icon(Icons.Filled.Bookmarks, "Lesezeichen") }
        }
    }

    if (showTabs) {
        AlertDialog(onDismissRequest = { showTabs = false }, title = { Text("Tabs (${tabs.size})") },
            text = {
                LazyColumn {
                    items(tabs, key = { it.id }) { t ->
                        val tt by t.title.collectAsState(); val tu by t.url.collectAsState()
                        Row(Modifier.fillMaxWidth().clickable { state.current.value = t.id; showTabs = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tt, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (t.id == tab.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(tu, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { state.close(t.id); if (state.tabs.value.isEmpty()) state.open(home) }) { Icon(Icons.Filled.Close, "Tab schließen") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { state.open(home); showTabs = false }) { Text("+ Neuer Tab") } },
            dismissButton = { TextButton(onClick = { showTabs = false }) { Text("Schließen") } })
    }

    if (showBookmarks) {
        val history by state.history.collectAsState()
        var which by remember { mutableStateOf(0) }
        AlertDialog(onDismissRequest = { showBookmarks = false }, title = {
            Row { FilterChip(which == 0, { which = 0 }, { Text("Lesezeichen") }); Spacer(Modifier.width(8.dp)); FilterChip(which == 1, { which = 1 }, { Text("Verlauf") }) }
        }, text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (which == 0) {
                    if (bookmarks.isEmpty()) item { Text("Noch keine Lesezeichen. Menü ⋮ → „Lesezeichen hinzufügen“. Sie werden mit deinem Konto synchronisiert.") }
                    items(bookmarks, key = { it.id }) { b ->
                        Row(Modifier.fillMaxWidth().clickable { tab.web.loadUrl(b.url); showBookmarks = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(b.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { c.planner.update { p -> p.copy(life = p.life.copy(bookmarks = p.life.bookmarks.map {
                                if (it.id == b.id) it.copy(deleted = true, updatedAt = now()) else it })) } }) { Icon(Icons.Filled.Delete, "Löschen") }
                        }
                    }
                } else {
                    if (history.isEmpty()) item { Text("Verlauf ist leer.") }
                    items(history.take(150), key = { it.url + it.at }) { h ->
                        Column(Modifier.fillMaxWidth().clickable { tab.web.loadUrl(h.url); showBookmarks = false }.padding(vertical = 6.dp)) {
                            Text(h.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.url, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showBookmarks = false }) { Text("Schließen") } })
    }
}
