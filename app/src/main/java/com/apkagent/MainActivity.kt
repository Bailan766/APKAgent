package com.apkagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.ChatScreen
import com.apkagent.ui.EditorScreen
import com.apkagent.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}
        setContent {
            APKAgentTheme(darkTheme = isSystemInDarkTheme()) {
                AppNav()
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = "chat",
        enterTransition = { fadeIn(tween(200)) },
        exitTransition = { fadeOut(tween(150)) },
        popEnterTransition = { fadeIn(tween(200)) },
        popExitTransition = { fadeOut(tween(150)) }
    ) {
        composable("chat") {
            ChatScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenEditor = { nav.navigate("editor") }
            )
        }
        composable(
            "settings",
            enterTransition = { slideInHorizontally(tween(250)) { it } },
            exitTransition = { slideOutHorizontally(tween(200)) { it } }
        ) {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
        composable(
            "editor",
            enterTransition = { slideInHorizontally(tween(250)) { it } },
            exitTransition = { slideOutHorizontally(tween(200)) { it } }
        ) {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as? ApkAgentApp
            if (app != null) {
                EditorScreen(rootDir = app.workspace, onBack = { nav.popBackStack() })
            }
        }
    }
}
