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
import com.apkagent.store.SettingsStore
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.SetupScreen
import com.apkagent.ui.ThemeState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}
        
        // Android 16: 启用预测性返回手势动画
        // 让系统在用户滑动时显示预览动画
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            try {
                // Android 16+ 的预测性返回手势默认启用
                // 系统会自动处理跨 Activity/Task 的动画
                onBackPressedDispatcher.addCallback(this, 
                    object : androidx.activity.OnBackPressedCallback(true) {
                        override fun handleOnBackPressed() {
                            // 默认行为，让 Compose 的 BackHandler 处理
                        }
                    }
                )
            } catch (_: Throwable) {}
        }
        
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

    // 使用 AnimatedContent 实现预测性返回动画
    AnimatedContent(
        targetState = Triple(showTerminal, setupDone, null as String?),
        transitionSpec = {
            // 滑动 + 淡入淡出动画，模拟预测性返回手势
            val direction = if (targetState.first || !targetState.second) -1 else 1
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth * direction },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(
                animationSpec = tween(250)
            ) togetherWith slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth * direction },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(
                animationSpec = tween(200)
            )
        },
        label = "PredictiveBackTransition"
    ) { (terminal, done, _) ->
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
        startDestination = "chat",
        // Android 16 预测性返回手势动画
        enterTransition = {
            // 进入动画：从右滑入 + 淡入
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250))
        },
        exitTransition = {
            // 退出动画：向左滑出（保留部分可见）
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            // 返回进入：从左滑入（从部分位置开始）
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth / 3 },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(250))
        },
        popExitTransition = {
            // 返回退出：向右滑出
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(350, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(200))
        }
    ) {
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
