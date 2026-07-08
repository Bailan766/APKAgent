package com.apkagent.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import java.io.File

/** 主题枚举 */
enum class AppTheme(
    val label: String,
    val isDark: Boolean,
    val hasWallpaper: Boolean = false
) {
    SYSTEM("跟随系统", false),
    AMOLED_BLACK("AMOLED 纯黑", true),
    PURE_WHITE("纯白", false),
    CREAM("乳白暖色", false),
    MD3_BLUE("MD3 蓝色", true),
    MD3_GREEN("MD3 绿色", false),
    MD3_PURPLE("MD3 紫色", true),
    MD3_ORANGE("MD3 橙色", false),
    MD3_MONO("MD3 黑白灰", false),
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

/** MD3 多色配色方案 */
private fun dynamicColorScheme(
    darkTheme: Boolean,
    seed: Color
): androidx.compose.material3.ColorScheme {
    val scheme = if (darkTheme) darkColorScheme(seed) else lightColorScheme(seed)
    return scheme
}

private fun darkColorScheme(seed: Color) = androidx.compose.material3.darkColorScheme(
    primary = seed,
    secondary = seed.copy(alpha = 0.8f),
    tertiary = seed.copy(red = seed.red * 0.7f, green = seed.green * 1.2f, blue = seed.blue * 1.3f),
    surface = Color(0xFF111111),
    background = Color(0xFF0A0A0A),
    surfaceVariant = Color(0xFF1C1C1E),
)

private fun lightColorScheme(seed: Color) = androidx.compose.material3.lightColorScheme(
    primary = seed,
    secondary = seed.copy(alpha = 0.85f),
    tertiary = seed.copy(red = seed.red * 1.1f, green = seed.green * 0.9f, blue = seed.blue * 1.2f),
    surface = Color(0xFFFFFFFF),
    background = Color(0xFFF5F5F5),
    surfaceVariant = Color(0xFFE8E8ED),
)

@Composable
fun APKAgentTheme(
    theme: AppTheme = ThemeState.currentTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = when (theme) {
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) darkColorScheme(Color(0xFF4A90D9)) else lightColorScheme(Color(0xFF4A90D9))
        AppTheme.AMOLED_BLACK -> darkColorScheme(Color(0xFF4A90D9)).copy(background = Color(0xFF000000), surface = Color(0xFF0A0A0A), surfaceVariant = Color(0xFF141414))
        AppTheme.PURE_WHITE -> lightColorScheme(Color(0xFF333333)).copy(background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFF0F0F0))
        AppTheme.CREAM -> lightColorScheme(Color(0xFF8B7355)).copy(background = Color(0xFFFAF8F5), surface = Color(0xFFF5F0EB), surfaceVariant = Color(0xFFEDE4DA))
        AppTheme.MD3_BLUE -> dynamicColorScheme(true, Color(0xFF4A90D9))
        AppTheme.MD3_GREEN -> dynamicColorScheme(false, Color(0xFF34A853))
        AppTheme.MD3_PURPLE -> dynamicColorScheme(true, Color(0xFF9B59B6))
        AppTheme.MD3_ORANGE -> dynamicColorScheme(false, Color(0xFFE67E22))
        AppTheme.MD3_MONO -> dynamicColorScheme(false, Color(0xFF666666))
        AppTheme.CUSTOM_WALLPAPER -> lightColorScheme(Color(0xFF4A90D9))
        AppTheme.CUSTOM_WALLPAPER_DARK -> darkColorScheme(Color(0xFF4A90D9))
    }

    MaterialTheme(colorScheme = colorScheme) {
        // 设置系统状态栏颜色
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
