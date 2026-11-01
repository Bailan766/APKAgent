package com.apkagent.ui

import com.apkagent.i18n.LocaleManager
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** 主题枚举 */
enum class AppTheme(
    val label: String,
    val isDark: Boolean,
    val hasWallpaper: Boolean = false
) {
    SYSTEM("跟随系统", false),
    AMOLED_BLACK("Obsidian 极夜黑", true),
    PURE_WHITE("纯白极简", false),
    CREAM("乳白暖色", false),
    MD3_BLUE("Cyber 赛博蓝", true),
    MD3_GREEN("Terminal 终端绿", true),
    MD3_PURPLE("Neon 霓虹紫", true),
    MD3_ORANGE("Amber 琥珀橙", false),
    MD3_MONO("Carbon 碳黑灰", true),
    CUSTOM_WALLPAPER("自定义壁纸", false, true),
    CUSTOM_WALLPAPER_DARK("自定义壁纸(暗)", true, true);
}

/** 主题状态持有 */
object ThemeState {
    var currentTheme by mutableStateOf(AppTheme.AMOLED_BLACK)
    var wallpaperBitmap by mutableStateOf<Bitmap?>(null)
    var wallpaperPath by mutableStateOf<String?>(null)

    fun loadWallpaper(path: String) {
        try {
            wallpaperBitmap = BitmapFactory.decodeFile(path)
            wallpaperPath = path
        } catch (_: Throwable) {
            wallpaperBitmap = null
            wallpaperPath = null
        }
    }
}

private fun darkColorScheme(seed: Color) = androidx.compose.material3.darkColorScheme(
    primary = seed,
    onPrimary = Color(0xFF04101E),
    primaryContainer = seed.copy(alpha = 0.2f),
    onPrimaryContainer = seed,
    secondary = Color(0xFF10B981),
    onSecondary = Color(0xFF021B10),
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = Color(0xFF6EE7B7),
    tertiary = Color(0xFFA855F7),
    onTertiary = Color(0xFF2E0854),
    surface = Color(0xFF0E1522),
    onSurface = Color(0xFFF1F5F9),
    background = Color(0xFF080C14),
    onBackground = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF172033),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2B3A55),
    error = Color(0xFFEF4444),
    onError = Color(0xFF450A0A)
)

private fun lightColorScheme(seed: Color) = androidx.compose.material3.lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.copy(alpha = 0.15f),
    onPrimaryContainer = seed,
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun APKAgentTheme(
    theme: AppTheme = ThemeState.currentTheme,
    content: @Composable () -> Unit
) {
    val cyanSeed = Color(0xFF38BDF8)
    val colorScheme = when (theme) {
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) darkColorScheme(cyanSeed) else lightColorScheme(Color(0xFF0284C7))
        AppTheme.AMOLED_BLACK -> darkColorScheme(cyanSeed).copy(
            background = Color(0xFF000000),
            surface = Color(0xFF090D14),
            surfaceVariant = Color(0xFF131A26),
            outline = Color(0xFF1E293B)
        )
        AppTheme.PURE_WHITE -> lightColorScheme(Color(0xFF0F172A)).copy(
            background = Color.White,
            surface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFFF1F5F9)
        )
        AppTheme.CREAM -> lightColorScheme(Color(0xFF8B7355)).copy(
            background = Color(0xFFFAF8F5),
            surface = Color(0xFFF5F0EB),
            surfaceVariant = Color(0xFFEDE4DA)
        )
        AppTheme.MD3_BLUE -> darkColorScheme(Color(0xFF38BDF8))
        AppTheme.MD3_GREEN -> darkColorScheme(Color(0xFF10B981))
        AppTheme.MD3_PURPLE -> darkColorScheme(Color(0xFFA855F7))
        AppTheme.MD3_ORANGE -> lightColorScheme(Color(0xFFF59E0B))
        AppTheme.MD3_MONO -> darkColorScheme(Color(0xFF94A3B8)).copy(
            surface = Color(0xFF121212),
            background = Color(0xFF0A0A0A),
            surfaceVariant = Color(0xFF1E1E1E)
        )
        AppTheme.CUSTOM_WALLPAPER -> lightColorScheme(cyanSeed)
        AppTheme.CUSTOM_WALLPAPER_DARK -> darkColorScheme(cyanSeed)
    }

    MaterialTheme(colorScheme = colorScheme) {
        val view = LocalView.current
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !theme.isDark
                isAppearanceLightNavigationBars = !theme.isDark
            }
        }
        content()
    }
}

/** Localized theme name for the current UI language. */
fun AppTheme.localizedLabel(): String {
    val s = LocaleManager.strings
    return when (this) {
        AppTheme.SYSTEM -> s.themeSystem
        AppTheme.AMOLED_BLACK -> s.themeAmoled
        AppTheme.PURE_WHITE -> s.themePureWhite
        AppTheme.CREAM -> s.themeCream
        AppTheme.MD3_BLUE -> s.themeMd3Blue
        AppTheme.MD3_GREEN -> s.themeMd3Green
        AppTheme.MD3_PURPLE -> s.themeMd3Purple
        AppTheme.MD3_ORANGE -> s.themeMd3Orange
        AppTheme.MD3_MONO -> s.themeMd3Mono
        AppTheme.CUSTOM_WALLPAPER -> s.themeWallpaper
        AppTheme.CUSTOM_WALLPAPER_DARK -> s.themeWallpaperDark
    }
}
