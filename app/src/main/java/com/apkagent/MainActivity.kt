package com.apkagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.AppTheme
import com.apkagent.ui.ChatScreen
import com.apkagent.ui.EditorScreen
import com.apkagent.ui.SettingsScreen
import com.apkagent.ui.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}
        setContent {
            val theme by remember { mutableStateOf(ThemeState.currentTheme) }
            APKAgentTheme(theme = theme) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 壁纸背景
                    ThemeState.wallpaperBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    AppNav()
                }
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "chat") {
        composable("chat") { ChatScreen(onOpenSettings = { nav.navigate("settings") }, onOpenEditor = { nav.navigate("editor") }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("editor") {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as? ApkAgentApp
            if (app != null) EditorScreen(rootDir = app.workspace, onBack = { nav.popBackStack() })
        }
    }
}
