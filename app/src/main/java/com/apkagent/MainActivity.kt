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

private const val START_DESTINATION = "projects"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}

        setContent {
            val lang by LocaleManager.language.collectAsState()
            val strings = remember(lang) { LocaleManager.strings }
            CompositionLocalProvider(LocalAppStrings provides strings) {
                // 直接在组合作用域读取 currentTheme（mutableStateOf）：设置页改动后会触发重组刷新配色，
                // 不再用 remember 保存一次性快照。
                APKAgentTheme(theme = ThemeState.currentTheme) {
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

    // 顶层目的地：singleTop + popUpTo(startDestination){saveState} + restoreState，
    // 避免 projects <-> chat 反复 navigate 堆积重复实例，并保留各自返回栈状态。
    val navigateTop: (String) -> Unit = { route ->
        nav.navigate(route) {
            launchSingleTop = true
            popUpTo(START_DESTINATION) { saveState = true }
            restoreState = true
        }
    }
    // 子页面仅做 singleTop 防抖，返回统一 popBackStack()。
    val navigateDetail: (String) -> Unit = { route ->
        nav.navigate(route) { launchSingleTop = true }
    }

    NavHost(
        navController = nav,
        startDestination = START_DESTINATION,
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
                onOpenProjectChat = { navigateTop("chat") },
                onOpenProjectEditor = { navigateDetail("editor") },
                onOpenSettings = { navigateDetail("settings") }
            )
        }
        composable("chat") {
            com.apkagent.ui.ChatScreen(
                onOpenSettings = { navigateDetail("settings") },
                onOpenEditor = { navigateDetail("editor") },
                onOpenProjects = { navigateTop("projects") }
            )
        }
        composable("settings") {
            com.apkagent.ui.SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenAbout = { navigateDetail("about") },
                onOpenTerminal = onOpenTerminal
            )
        }
        composable("editor") {
            val ctx = LocalContext.current
            val app = ctx.applicationContext as? ApkAgentApp
            if (app != null) com.apkagent.ui.EditorScreen(rootDir = app.currentWorkspace(), onBack = { nav.popBackStack() })
        }
        composable("about") { com.apkagent.ui.AboutScreen(onBack = { nav.popBackStack() }) }
        // terminal 由 AppEntry 的 showTerminal 分支承载，NavHost 内不再保留不可达的死路由。
    }
}
