package com.ascent.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.ascent.app.data.Prefs
import com.ascent.app.data.Tracker

val CBg = Color(0xFF14161A)
val CSurface = Color(0xFF1E2127)
val CSurface2 = Color(0xFF282C34)
val CAccent = Color(0xFF38B79F)
val CGold = Color(0xFFE4B15A)
val CText = Color(0xFFE8EAED)
val CMuted = Color(0xFF8A9099)
val CRed = Color(0xFFE05A5A)

@Composable
fun AscentTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CAccent, onPrimary = Color(0xFF0B0C0E),
            secondary = CGold, onSecondary = Color(0xFF0B0C0E),
            background = CBg, onBackground = CText,
            surface = CSurface, onSurface = CText,
            surfaceVariant = CSurface2, onSurfaceVariant = CMuted,
            surfaceContainer = CSurface, surfaceContainerHigh = CSurface2,
            surfaceContainerHighest = CSurface2, surfaceContainerLow = CSurface,
            outline = CMuted, error = CRed,
        ),
        content = content,
    )
}

enum class Screen(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home), LOG("Log", Icons.Filled.Edit), SETTINGS("Settings", Icons.Filled.Settings)
}

@Composable
fun AscentApp(resumeTick: Int) {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var settings by remember { mutableStateOf(Prefs.load(ctx)) }
    var refresh by remember { mutableIntStateOf(0) }
    val key = resumeTick * 1000 + refresh

    LaunchedEffect(key, settings) {
        Tracker.catchUp(ctx)
        Tracker.refreshToday(ctx)
    }

    Scaffold(
        containerColor = CBg,
        bottomBar = {
            NavigationBar(containerColor = CSurface) {
                Screen.entries.forEach { s ->
                    NavigationBarItem(
                        selected = screen == s,
                        onClick = { screen = s },
                        icon = { Icon(s.icon, null) },
                        label = { Text(s.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = CAccent.copy(alpha = 0.25f)),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                Screen.HOME -> HomeScreen(settings, key) { refresh++ }
                Screen.LOG -> LogScreen(settings)
                Screen.SETTINGS -> SettingsScreen(settings, key, onSave = { Prefs.save(ctx, it); settings = it }) { refresh++ }
            }
        }
    }
}
