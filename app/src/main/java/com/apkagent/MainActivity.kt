package com.apkagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.ThemeState
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.i18n.LocaleManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}

        setContent {
            val theme by remember { mutableStateOf(ThemeState.currentTheme) }
            val lang by LocaleManager.language.collectAsState()
            val strings = remember(lang) { LocaleManager.strings }
            CompositionLocalProvider(LocalAppStrings provides strings) {
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
}

@Composable
private fun AppEntry() {
    val ctx = LocalContext.current
    val settingsStore = remember { (ctx.applicationContext as ApkAgentApp).settingsStore }
    var setupDone by remember { mutableStateOf(settingsStore.isSetupCompleted()) }
    var showTerminal by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = showTerminal to setupDone,
        transitionSpec = {
            val direction = if (targetState.first || !targetState.second) -1 else 1
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth * direction },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250)) togetherWith
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth * direction },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        },
        label = "PredictiveBackTransition"
    ) { (terminal, done) ->
        when {
            terminal -> {
                com.apkagent.ui.TerminalScreen(onBack = { showTerminal = false })
            }
            !done -> {
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
}

@Composable
private fun AppNav(onOpenTerminal: () -> Unit = {}) {
    val nav = rememberNavController()

    NavHost(
        navController = nav,
        startDestination = "projects",
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        }
    ) {
        composable("projects") {
            com.apkagent.ui.ProjectScreen(
                onOpenProjectChat = { nav.navigate("chat") },
                onOpenProjectEditor = { nav.navigate("editor") },
                onOpenSettings = { nav.navigate("settings") }
            )
        }
        composable("chat") {
            com.apkagent.ui.ChatScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenEditor = { nav.navigate("editor") },
                onOpenProjects = { nav.navigate("projects") }
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
            if (app != null) com.apkagent.ui.EditorScreen(rootDir = app.currentWorkspace(), onBack = { nav.popBackStack() })
        }
        composable("about") { com.apkagent.ui.AboutScreen(onBack = { nav.popBackStack() }) }
        composable("terminal") { com.apkagent.ui.TerminalScreen(onBack = { nav.popBackStack() }) }
    }
}
