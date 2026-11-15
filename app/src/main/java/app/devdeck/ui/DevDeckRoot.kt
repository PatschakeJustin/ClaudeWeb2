package app.devdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.devdeck.AppContainer
import app.devdeck.DevDeckApp
import app.devdeck.ui.agent.AgentScreen
import app.devdeck.ui.connections.ConnectionsScreen
import app.devdeck.ui.files.FilesScreen
import app.devdeck.ui.settings.SettingsScreen
import app.devdeck.ui.terminal.TerminalScreen

enum class Dest(val label: String, val icon: ImageVector) {
    AGENT("Claude", Icons.Filled.SmartToy),
    TERMINAL("Terminal", Icons.Filled.Terminal),
    FILES("Dateien", Icons.Filled.Folder),
    CONNECTIONS("Verbindungen", Icons.Filled.Dns),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

/** True on tablets / unfolded foldables / landscape phones wide enough for two panes. */
val LocalWideLayout = compositionLocalOf { false }

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as DevDeckApp).container

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevDeckRoot() {
    var dest by rememberSaveable { mutableStateOf(Dest.AGENT) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        CompositionLocalProvider(LocalWideLayout provides wide) {
            if (wide) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                        NavigationRail(Modifier.fillMaxHeight()) {
                            Dest.entries.forEach { d ->
                                NavigationRailItem(
                                    selected = dest == d,
                                    onClick = { dest = d },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label) },
                                )
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) { Content(dest) { dest = it } }
                    }
                }
            } else {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            Dest.entries.forEach { d ->
                                NavigationBarItem(
                                    selected = dest == d,
                                    onClick = { dest = d },
                                    icon = { Icon(d.icon, contentDescription = d.label) },
                                    label = { Text(d.label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                                    alwaysShowLabel = false,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
                    ) { Content(dest) { dest = it } }
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
