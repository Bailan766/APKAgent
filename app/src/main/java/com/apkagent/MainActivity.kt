package com.apkagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.apkagent.i18n.LocalAppStrings
import com.apkagent.i18n.LocaleManager
import com.apkagent.ui.APKAgentTheme
import com.apkagent.ui.ThemeState

private const val START_DESTINATION = "projects"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { enableEdgeToEdge() } catch (_: Throwable) {}

        setContent {
            val lang by LocaleManager.language.collectAsState()
            val strings = remember(lang) { LocaleManager.strings }
            CompositionLocalProvider(LocalAppStrings provides strings) {
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
    val navBackStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val topRoutes = remember { setOf("projects", "chat", "editor", "settings") }

    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0

    // 顶层目的地：singleTop + popUpTo(startDestination){saveState} + restoreState，
    // 避免反复切换悬浮底栏产生重复实例。
    val navigateTop: (String) -> Unit = { route ->
        if (route != currentRoute) {
            nav.navigate(route) {
                launchSingleTop = true
                popUpTo(START_DESTINATION) { saveState = true }
                restoreState = true
            }
        }
    }
    val navigateDetail: (String) -> Unit = { route ->
        nav.navigate(route) { launchSingleTop = true }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = nav,
            startDestination = START_DESTINATION,
            enterTransition = {
                fadeIn(animationSpec = tween(220))
            },
            exitTransition = {
                fadeOut(animationSpec = tween(180))
            },
            popEnterTransition = {
                fadeIn(animationSpec = tween(220))
            },
            popExitTransition = {
                fadeOut(animationSpec = tween(180))
            }
        ) {
            composable("projects") {
                com.apkagent.ui.ProjectScreen(
                    onOpenProjectChat = { navigateTop("chat") },
                    onOpenProjectEditor = { navigateTop("editor") },
                    onOpenSettings = { navigateTop("settings") }
                )
            }
            composable("chat") {
                com.apkagent.ui.ChatScreen(
                    onOpenSettings = { navigateTop("settings") },
                    onOpenEditor = { navigateTop("editor") },
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
                if (app != null) com.apkagent.ui.EditorScreen(rootDir = app.currentWorkspace(), onBack = { navigateTop("projects") })
            }
            composable("about") { com.apkagent.ui.AboutScreen(onBack = { nav.popBackStack() }) }
        }

        // Material 3 悬浮胶囊状底部导航栏（软键盘弹起时自动避让隐藏）
        AnimatedVisibility(
            visible = currentRoute in topRoutes && !imeVisible,
            enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { it / 2 },
            exit = fadeOut(tween(150)) + slideOutVertically(tween(180)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            FloatingCapsuleNavBar(
                currentRoute = currentRoute,
                onNavigate = navigateTop
            )
        }
    }
}

private data class NavTabItem(
    val route: String,
    val label: String,
    val icon: ImageVector
)

@Composable
private fun FloatingCapsuleNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit
) {
    val tabs = remember {
        listOf(
            NavTabItem("projects", "工作台", Icons.Default.Dashboard),
            NavTabItem("chat", "AI 助手", Icons.Default.AutoAwesome),
            NavTabItem("editor", "源码", Icons.Default.Code),
            NavTabItem("settings", "设置", Icons.Default.Settings)
        )
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val selected = currentRoute == tab.route
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onNavigate(tab.route) }
                ) {
                    Row(
                        modifier = Modifier.padding(
                            horizontal = if (selected) 16.dp else 13.dp,
                            vertical = 9.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp)
                        )
                        if (selected) {
                            Text(
                                text = tab.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
