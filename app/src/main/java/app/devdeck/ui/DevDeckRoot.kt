package app.devdeck.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.devdeck.AppContainer
import app.devdeck.DevDeckApp
import app.devdeck.R
import app.devdeck.ui.agent.AgentScreen
import app.devdeck.ui.connections.ConnectionsScreen
import app.devdeck.ui.files.FilesScreen
import app.devdeck.ui.settings.SettingsScreen
import app.devdeck.ui.terminal.TerminalScreen

enum class Dest(val label: String, val icon: ImageVector) {
    HOME("Start", Icons.Filled.Dashboard),
    PLANNER("Planer", Icons.Filled.CalendarMonth),
    AGENT("KI-Chat", Icons.Filled.SmartToy),
    TERMINAL("Terminal", Icons.Filled.Terminal),
    FILES("Dateien", Icons.Filled.Folder),
    CONNECTIONS("Server", Icons.Filled.Dns),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

/** True on tablets / unfolded foldables / landscape phones wide enough for two panes. */
val LocalWideLayout = compositionLocalOf { false }

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DevDeckApp).container

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevDeckRoot() {
    var dest by rememberSaveable { mutableStateOf(Dest.HOME) }
    val container = appContainer()
    val navRequest by container.navRequest.collectAsState()
    LaunchedEffect(navRequest) { navRequest?.let { dest = it; container.navRequest.value = null } }
    UpdateDialog()
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val wide = maxWidth >= 600.dp
        CompositionLocalProvider(LocalWideLayout provides wide) {
            if (wide) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            header = {
                                Image(
                                    painterResource(R.mipmap.ic_launcher_foreground), contentDescription = null,
                                    modifier = Modifier.padding(top = 8.dp).size(48.dp).clip(RoundedCornerShape(14.dp))
                                        .background(Color(0xFF160A17)),
                                )
                                Spacer(Modifier.height(8.dp))
                            },
                        ) {
                            Dest.entries.forEach { d ->
                                NavigationRailItem(
                                    selected = dest == d,
                                    onClick = { dest = d },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label, maxLines = 1) },
                                )
                            }
                        }
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.weight(1f).fillMaxHeight()) { Content(dest) { dest = it } }
                    }
                }
            } else {
                val imeVisible = WindowInsets.isImeVisible
                Scaffold(
                    bottomBar = {
                        // While typing the keyboard needs the space.
                        if (!imeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Dest.entries.forEach { d ->
                                NavigationBarItem(
                                    selected = dest == d,
                                    onClick = { dest = d },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                        Content(dest) { dest = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun Content(dest: Dest, navigate: (Dest) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        when (dest) {
            Dest.HOME -> app.devdeck.ui.home.HomeScreen(navigate)
            Dest.PLANNER -> app.devdeck.ui.planner.PlannerScreen()
            Dest.AGENT -> AgentScreen(navigate)
            Dest.TERMINAL -> TerminalScreen(navigate)
            Dest.FILES -> FilesScreen(navigate)
            Dest.CONNECTIONS -> ConnectionsScreen(navigate)
            Dest.SETTINGS -> SettingsScreen()
        }
    }
}
