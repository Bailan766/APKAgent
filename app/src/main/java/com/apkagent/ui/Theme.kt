package com.apkagent.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.apkagent.i18n.LocaleManager

// ══════════════════════════════════════════════════
//  Material Design 3 暖粉/玫瑰/酒红动态色调系统
// ══════════════════════════════════════════════════

val M3RoseGradient = Brush.linearGradient(
    colors = listOf(Color(0xFFFFB6C8), Color(0xFFF696B3), Color(0xFFE87A9E))
)
val M3RoseHeroTitleColor = Color(0xFF2B0510)
val M3RoseHeroBodyColor = Color(0xFF4D1022)
val M3WineIconContainer = Color(0xFF491B2A)
val M3RoseAccent = Color(0xFFFFB2C1)
val M3WarmSurface = Color(0xFF1D181A)
val M3WarmSurfaceHigh = Color(0xFF282124)

/** 主题枚举 */
enum class AppTheme(
    val label: String,
    val isDark: Boolean,
    val hasWallpaper: Boolean = false
) {
    SYSTEM("跟随系统", false),
    AMOLED_BLACK("Rose M3 玫瑰深邃", true),
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
            // 下采样防止大图 OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            // 目标分辨率约 1080p，计算采样率
            val targetSize = 1080
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > targetSize * 2 ||
                   bounds.outHeight / sampleSize > targetSize * 2) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val newBitmap = BitmapFactory.decodeFile(path, opts)
            // 替换前回收旧 Bitmap 防止内存泄漏
            wallpaperBitmap?.let { old ->
                if (old !== newBitmap && !old.isRecycled) {
                    try { old.recycle() } catch (_: Throwable) {}
                }
            }
            wallpaperBitmap = newBitmap
            wallpaperPath = path
        } catch (_: Throwable) {
            wallpaperBitmap = null
            wallpaperPath = null
        }
    }
}

private fun roseDarkColorScheme() = androidx.compose.material3.darkColorScheme(
    primary = M3RoseAccent,
    onPrimary = Color(0xFF3E0016),
    primaryContainer = Color(0xFF5A172D),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondary = Color(0xFFE6BDC4),
    onSecondary = Color(0xFF44292F),
    secondaryContainer = Color(0xFF44292F),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    surface = M3WarmSurface,
    onSurface = Color(0xFFF7EEF0),
    background = Color(0xFF0F0D0E),
    onBackground = Color(0xFFF7EEF0),
    surfaceVariant = M3WarmSurfaceHigh,
    onSurfaceVariant = Color(0xFFD0C1C5),
    outline = Color(0xFF4A3F42),
    outlineVariant = Color(0xFF362D30),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private fun darkColorScheme(seed: Color) = androidx.compose.material3.darkColorScheme(
    primary = seed,
    onPrimary = Color(0xFF04101E),
    primaryContainer = seed.copy(alpha = 0.22f),
    onPrimaryContainer = seed,
    secondary = Color(0xFFE6BDC4),
    onSecondary = Color(0xFF44292F),
    secondaryContainer = Color(0xFF44292F),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFA855F7),
    onTertiary = Color(0xFF2E0854),
    surface = M3WarmSurface,
    onSurface = Color(0xFFF7EEF0),
    background = Color(0xFF0F0D0E),
    onBackground = Color(0xFFF7EEF0),
    surfaceVariant = M3WarmSurfaceHigh,
    onSurfaceVariant = Color(0xFFD0C1C5),
    outline = Color(0xFF4A3F42),
    outlineVariant = Color(0xFF362D30),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private fun lightColorScheme(seed: Color) = androidx.compose.material3.lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.copy(alpha = 0.16f),
    onPrimaryContainer = seed,
    secondary = Color(0xFF9E2B4D),
    onSecondary = Color.White,
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1F1619),
    background = Color(0xFFFFF8F9),
    onBackground = Color(0xFF1F1619),
    surfaceVariant = Color(0xFFF6ECEF),
    onSurfaceVariant = Color(0xFF6B595E),
    outline = Color(0xFFD8C6CB),
    outlineVariant = Color(0xFFEBDCE0),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun APKAgentTheme(
    theme: AppTheme = ThemeState.currentTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = when (theme) {
        AppTheme.SYSTEM -> if (isSystemInDarkTheme()) roseDarkColorScheme() else lightColorScheme(Color(0xFF9E2B4D))
        AppTheme.AMOLED_BLACK -> roseDarkColorScheme()
        AppTheme.PURE_WHITE -> lightColorScheme(Color(0xFF9E2B4D)).copy(
            background = Color.White,
            surface = Color(0xFFFAFAFA),
            surfaceVariant = Color(0xFFF3ECEF)
        )
        AppTheme.CREAM -> lightColorScheme(Color(0xFF8B5A68)).copy(
            background = Color(0xFFFAF6F7),
            surface = Color(0xFFF5ECEF),
            surfaceVariant = Color(0xFFEDE0E4)
        )
        AppTheme.MD3_BLUE -> darkColorScheme(Color(0xFF38BDF8))
        AppTheme.MD3_GREEN -> darkColorScheme(Color(0xFF10B981))
        AppTheme.MD3_PURPLE -> darkColorScheme(Color(0xFFA855F7))
        AppTheme.MD3_ORANGE -> lightColorScheme(Color(0xFFF59E0B))
        AppTheme.MD3_MONO -> darkColorScheme(Color(0xFF94A3B8)).copy(
            surface = Color(0xFF161616),
            background = Color(0xFF0B0B0B),
            surfaceVariant = Color(0xFF222222)
        )
        AppTheme.CUSTOM_WALLPAPER -> lightColorScheme(Color(0xFF9E2B4D))
        AppTheme.CUSTOM_WALLPAPER_DARK -> roseDarkColorScheme()
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
