// 设计「账本墨绿」：个人记账的视觉词汇取自纸质收据与账本墨水。
// 纸白底、墨色正文、账绿唯一强调（只落在「账」上：合计/选中/主操作）、锈红只给破坏性操作。
// 全 App 的 colorScheme 从这里出发，页面不再各自取色。
package io.github.pnickzhangq.photoledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- 亮色：纸 / 墨 / 账绿 / 锈红 ----
private val LightScheme = lightColorScheme(
    primary = Color(0xFF1D5C45),            // 账绿
    onPrimary = Color(0xFFF7F6F2),
    primaryContainer = Color(0xFFCFE4D9),
    onPrimaryContainer = Color(0xFF0E3B2B),
    secondary = Color(0xFF4A6B5B),
    onSecondary = Color(0xFFF7F6F2),
    secondaryContainer = Color(0xFFD9E7DF),
    onSecondaryContainer = Color(0xFF17362A),
    error = Color(0xFFA63D2A),              // 锈红：仅破坏性操作
    onError = Color(0xFFF7F6F2),
    errorContainer = Color(0xFFEFDCD4),
    onErrorContainer = Color(0xFF4A1A0F),
    background = Color(0xFFF7F6F2),         // 纸
    onBackground = Color(0xFF1F1D1A),       // 墨
    surface = Color(0xFFF7F6F2),
    onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFEFEDE4),
    onSurfaceVariant = Color(0xFF8A857B),   // 灰：日期与辅助文案
    surfaceDim = Color(0xFFDEDBD2),
    surfaceContainerLowest = Color(0xFFF7F6F2),
    surfaceContainerLow = Color(0xFFF1EFE8),
    surfaceContainer = Color(0xFFEBE8E0),
    surfaceContainerHigh = Color(0xFFE5E2D9),
    surfaceContainerHighest = Color(0xFFDFDCD2),
    outline = Color(0xFFDDD9CE),
    outlineVariant = Color(0xFFE8E5DB),
)

// ---- 暗色：同构——纸变炭底，账绿提亮一档 ----
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF86C0A4),
    onPrimary = Color(0xFF10352A),
    primaryContainer = Color(0xFF2A4D3D),
    onPrimaryContainer = Color(0xFFC4E3D4),
    secondary = Color(0xFFA3C2B1),
    onSecondary = Color(0xFF17362A),
    secondaryContainer = Color(0xFF2E4A3D),
    onSecondaryContainer = Color(0xFFD9E7DF),
    error = Color(0xFFE58A70),
    onError = Color(0xFF3F140A),
    errorContainer = Color(0xFF5C2415),
    onErrorContainer = Color(0xFFF6DAD0),
    background = Color(0xFF151412),
    onBackground = Color(0xFFE8E6DF),
    surface = Color(0xFF151412),
    onSurface = Color(0xFFE8E6DF),
    surfaceVariant = Color(0xFF211F1C),
    onSurfaceVariant = Color(0xFF9C968A),
    surfaceDim = Color(0xFF151412),
    surfaceContainerLowest = Color(0xFF100F0E),
    surfaceContainerLow = Color(0xFF1C1B19),
    surfaceContainer = Color(0xFF201F1C),
    surfaceContainerHigh = Color(0xFF252420),
    surfaceContainerHighest = Color(0xFF2B2925),
    outline = Color(0xFF3C3A34),
    outlineVariant = Color(0xFF2B2925),
)

@Composable
fun LedgerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        content = content,
    )
}
