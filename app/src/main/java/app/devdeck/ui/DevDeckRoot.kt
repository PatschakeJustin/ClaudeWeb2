package app.devdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.devdeck.AppContainer
import app.devdeck.DevDeckApp
import app.devdeck.ui.agent.AgentScreen
import app.devdeck.ui.connections.ConnectionsScreen
import app.devdeck.ui.files.FilesScreen
import app.devdeck.ui.lcars.Elbow
import app.devdeck.ui.lcars.LcarsBlock
import app.devdeck.ui.lcars.SegmentBar
import app.devdeck.ui.settings.SettingsScreen
import app.devdeck.ui.terminal.TerminalScreen
import app.devdeck.ui.theme.Lcars

enum class Dest(val label: String, val short: String, val icon: ImageVector) {
    AGENT("KI-Chat", "Chat", Icons.Filled.SmartToy),
    TERMINAL("Terminal", "Term", Icons.Filled.Terminal),
    FILES("Dateien", "Dateien", Icons.Filled.Folder),
    CONNECTIONS("Server", "Server", Icons.Filled.Dns),
    SETTINGS("System", "System", Icons.Filled.Settings),
}

/** True on tablets / unfolded foldables / landscape phones wide enough for two panes. */
val LocalWideLayout = compositionLocalOf { false }

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DevDeckApp).container

private fun colorFor(d: Dest) = Lcars.Cycle[d.ordinal % Lcars.Cycle.size]

@Composable
fun DevDeckRoot() {
    var dest by rememberSaveable { mutableStateOf(Dest.AGENT) }
    UpdateDialog()
    BoxWithConstraints(Modifier.fillMaxSize().background(Lcars.Black)) {
        val wide = maxWidth >= 600.dp
        CompositionLocalProvider(LocalWideLayout provides wide) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (wide) WideFrame(dest) { dest = it } else PhoneFrame(dest) { dest = it }
            }
        }
    }
}

/** Tablet: column of navigation blocks on the left, joined to the top and bottom bars by elbows. */
@Composable
private fun WideFrame(dest: Dest, navigate: (Dest) -> Unit) {
    val col = 150.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            Elbow(Lcars.Orange, columnWidth = col, barHeight = 22.dp, modifier = Modifier.width(col + 36.dp).fillMaxHeight())
            SegmentBar(22.dp, Modifier.weight(1f).padding(start = 4.dp), label = "Humantize DevApp · ${dest.label}")
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(col).fillMaxHeight().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Dest.entries.forEach { d ->
                    LcarsBlock(d.label, colorFor(d), dest == d, { navigate(d) }, Modifier.fillMaxWidth().height(58.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth().background(Lcars.Lavender))
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 14.dp, top = 6.dp, end = 6.dp)) { Content(dest, navigate) }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.Bottom) {
            Elbow(Lcars.Violet, columnWidth = col, barHeight = 14.dp, modifier = Modifier.width(col + 36.dp).fillMaxHeight(), flipVertical = true)
            SegmentBar(14.dp, Modifier.weight(1f).padding(start = 4.dp), colors = listOf(Lcars.Violet, Lcars.Peach, Lcars.Orange))
        }
    }
}

/** Phone: elbow header with the screen name, content, and a row of navigation blocks at the bottom. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneFrame(dest: Dest, navigate: (Dest) -> Unit) {
    val imeVisible = WindowInsets.isImeVisible
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(32.dp)) {
            Elbow(colorFor(dest), columnWidth = 14.dp, barHeight = 12.dp, outerRadius = 16.dp, innerRadius = 8.dp,
                modifier = Modifier.width(40.dp).fillMaxHeight())
            SegmentBar(12.dp, Modifier.weight(1f).padding(start = 4.dp), label = dest.label,
                colors = listOf(colorFor(dest), Lcars.Peach, Lcars.Lavender))
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(14.dp).fillMaxHeight().padding(bottom = 4.dp).background(colorFor(dest)))
            Box(Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp)) { Content(dest, navigate) }
        }
        // While typing the keyboard needs the space; the navigation comes back when it closes.
        if (!imeVisible) {
            Row(Modifier.fillMaxWidth().height(44.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Dest.entries.forEachIndexed { i, d ->
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
                        Dest.entries.lastIndex -> RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                        else -> RoundedCornerShape(0.dp)
                    }
                    LcarsBlock(d.short, colorFor(d), dest == d, { navigate(d) }, Modifier.weight(1f).fillMaxHeight(),
                        shape, Alignment.Center, fontSize = 12)
                }
            }
        }
    }
}

@Composable
private fun Content(dest: Dest, navigate: (Dest) -> Unit) {
    when (dest) {
        Dest.AGENT -> AgentScreen(navigate)
        Dest.TERMINAL -> TerminalScreen(navigate)
        Dest.FILES -> FilesScreen(navigate)
        Dest.CONNECTIONS -> ConnectionsScreen(navigate)
        Dest.SETTINGS -> SettingsScreen()
    }
}
