package com.apkagent

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apkagent.store.SettingsStore
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.SetupScreen
import com.apkagent.ui.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}
        setContent {
            val theme by remember { mutableStateOf(ThemeState.currentTheme) }
            APKAgentTheme(theme = theme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    ThemeState.wallpaperBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    AppEntry()
                }
            }
        }
    }
}

@Composable
private fun AppEntry() {
    val ctx = LocalContext.current
    val settingsStore = remember { SettingsStore(ctx) }
    var setupDone by remember { mutableStateOf(settingsStore.isSetupCompleted()) }
    var showTerminal by remember { mutableStateOf(false) }

    when {
        showTerminal -> {
            com.apkagent.ui.TerminalScreen(onBack = { showTerminal = false })
        }
        !setupDone -> {
            com.apkagent.ui.SetupScreen(
                onSetupComplete = {
                    settingsStore.markSetupCompleted()
                    setupDone = true
                },
                onOpenTerminal = { showTerminal = true }
            )
        }
        else -> {
            AppNav(onOpenTerminal = { showTerminal = true })
        }
    }
}

@Composable
private fun AppNav(onOpenTerminal: () -> Unit = {}) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") {
            com.apkagent.ui.ChatScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenEditor = { nav.navigate("editor") }
            )
        }
        composable("settings") {
            com.apkagent.ui.SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAbout = { nav.navigate("about") },
                onOpenTerminal = onOpenTerminal
            )
        }
        composable("editor") {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as? ApkAgentApp
            if (app != null) com.apkagent.ui.EditorScreen(rootDir = app.workspace, onBack = { nav.popBackStack() })
        }
        composable("about") { com.apkagent.ui.AboutScreen(onBack = { nav.popBackStack() }) }
        composable("terminal") { com.apkagent.ui.TerminalScreen(onBack = { nav.popBackStack() }) }
    }
}
