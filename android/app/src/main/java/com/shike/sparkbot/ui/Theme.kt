package com.shike.sparkbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一套配色。深色是原来那套（夜间看屏幕不刺眼），浅色是白天/户外用的白底版本。
 *
 * 强调色在浅色下压深一档：同一个 #4C8DFF 放在白底上对比度只有 3:1 出头，
 * 小字号的按钮文字会发飘。
 */
@Immutable
data class SparkColors(
    val bg: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val accent: Color,
    val accentSoft: Color,
    val teal: Color,
    val danger: Color,
    val warn: Color,
    val textMain: Color,
    val textMuted: Color,
    val line: Color,
    /** 输入框底色，比 surface 再深/浅一档 */
    val field: Color,
    /** 离线状态点的灰 */
    val dotOff: Color,
)

private val DarkColors = SparkColors(
    bg = Color(0xFF0B0E13),
    surface = Color(0xFF151A22),
    surfaceHigh = Color(0xFF1D2531),
    accent = Color(0xFF4C8DFF),
    accentSoft = Color(0x334C8DFF),
    teal = Color(0xFF35D0BA),
    danger = Color(0xFFFF4D5E),
    warn = Color(0xFFFFB020),
    textMain = Color(0xFFE8EDF5),
    textMuted = Color(0xFF8B95A6),
    line = Color(0xFF232B38),
    field = Color(0xFF101821),
    dotOff = Color(0xFF3A4252),
)

private val LightColors = SparkColors(
    bg = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFE9EDF4),
    accent = Color(0xFF2F6FE4),
    accentSoft = Color(0x1A2F6FE4),
    teal = Color(0xFF0F9D8A),
    danger = Color(0xFFE0384A),
    warn = Color(0xFFB26A00),
    textMain = Color(0xFF10141B),
    textMuted = Color(0xFF66707F),
    line = Color(0xFFDEE3EC),
    field = Color(0xFFF7F9FC),
    dotOff = Color(0xFFC7CEDA),
)

private val LocalSparkColors = staticCompositionLocalOf { DarkColors }

// 名字沿用原来的，调用方一行都不用改。注意这几个是 @Composable 取值，
// 不能写在 Canvas 的 draw lambda 里——那边要先把颜色提成局部变量。
val Bg: Color
    @Composable get() = LocalSparkColors.current.bg
val Surface: Color
    @Composable get() = LocalSparkColors.current.surface
val SurfaceHigh: Color
    @Composable get() = LocalSparkColors.current.surfaceHigh
val Accent: Color
    @Composable get() = LocalSparkColors.current.accent
val AccentSoft: Color
    @Composable get() = LocalSparkColors.current.accentSoft
val Teal: Color
    @Composable get() = LocalSparkColors.current.teal
val Danger: Color
    @Composable get() = LocalSparkColors.current.danger
val Warn: Color
    @Composable get() = LocalSparkColors.current.warn
val TextMain: Color
    @Composable get() = LocalSparkColors.current.textMain
val TextMuted: Color
    @Composable get() = LocalSparkColors.current.textMuted
val Line: Color
    @Composable get() = LocalSparkColors.current.line
val Field: Color
    @Composable get() = LocalSparkColors.current.field

private fun darkScheme(c: SparkColors) = darkColorScheme(
    primary = c.accent,
    onPrimary = Color.White,
    background = c.bg,
    onBackground = c.textMain,
    surface = c.surface,
    onSurface = c.textMain,
    surfaceVariant = c.surfaceHigh,
    onSurfaceVariant = c.textMuted,
    error = c.danger
)

private fun lightScheme(c: SparkColors) = lightColorScheme(
    primary = c.accent,
    onPrimary = Color.White,
    background = c.bg,
    onBackground = c.textMain,
    surface = c.surface,
    onSurface = c.textMain,
    surfaceVariant = c.surfaceHigh,
    onSurfaceVariant = c.textMuted,
    error = c.danger
)

/** 跟随系统深浅色。Activity 声明了 uiMode 自己处理，所以切换是即时的，不会重建界面。 */
@Composable
fun SparkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalSparkColors provides colors) {
        MaterialTheme(colorScheme = if (dark) darkScheme(colors) else lightScheme(colors), content = content)
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, action: @Composable (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        action?.invoke()
    }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Surface)
            .border(1.dp, Line, RoundedCornerShape(18.dp))
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun Dot(active: Boolean, color: Color? = null) {
    val on = color ?: Teal
    val off = LocalSparkColors.current.dotOff
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) on else off)
    )
}

/** 无依赖的线性小图标，避免引入 material-icons-extended。 */
@Composable
fun Glyph(kind: Int, tint: Color? = null, size: Int = 22) {
    val color = tint ?: TextMuted
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        when (kind) {
            0 -> { // 设备：机身 + 天线
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.15f, s * 0.3f),
                    size = Size(s * 0.7f, s * 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
                    style = Stroke(width = w)
                )
                drawLine(color, Offset(s * 0.5f, s * 0.3f), Offset(s * 0.5f, s * 0.12f), w, StrokeCap.Round)
                drawCircle(color, radius = s * 0.06f, center = Offset(s * 0.5f, s * 0.1f))
            }
            1 -> { // 遥控：十字方向
                drawLine(color, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.5f, s * 0.88f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.12f, s * 0.5f), Offset(s * 0.88f, s * 0.5f), w, StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(s * 0.5f, s * 0.1f); lineTo(s * 0.4f, s * 0.26f); lineTo(s * 0.6f, s * 0.26f); close()
                }
                drawPath(arrow, color)
            }
            2 -> { // 相机
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.1f, s * 0.28f),
                    size = Size(s * 0.8f, s * 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
                    style = Stroke(width = w)
                )
                drawCircle(color, radius = s * 0.16f, center = Offset(s * 0.5f, s * 0.55f), style = Stroke(width = w))
                drawLine(color, Offset(s * 0.36f, s * 0.2f), Offset(s * 0.64f, s * 0.2f), w, StrokeCap.Round)
            }
            else -> { // 账号
                drawCircle(color, radius = s * 0.18f, center = Offset(s * 0.5f, s * 0.33f), style = Stroke(width = w))
                drawArc(
                    color = color,
                    startAngle = 200f,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(s * 0.18f, s * 0.45f),
                    size = Size(s * 0.64f, s * 0.6f),
                    style = Stroke(width = w, cap = StrokeCap.Round)
                )
            }
        }
    }
}

@Composable
fun Label(text: String, color: Color? = null, size: Int = 12) {
    Text(text, color = color ?: TextMuted, fontSize = size.sp)
}

@Composable
fun DividerLine() {
    Spacer(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .background(Line)
    )
}
