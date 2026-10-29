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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
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

val Bg = Color(0xFF0B0E13)
val Surface = Color(0xFF151A22)
val SurfaceHigh = Color(0xFF1D2531)
val Accent = Color(0xFF4C8DFF)
val AccentSoft = Color(0x334C8DFF)
val Teal = Color(0xFF35D0BA)
val Danger = Color(0xFFFF4D5E)
val TextMain = Color(0xFFE8EDF5)
val TextMuted = Color(0xFF8B95A6)
val Line = Color(0xFF232B38)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Bg,
    onBackground = TextMain,
    surface = Surface,
    onSurface = TextMain,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = TextMuted,
    error = Danger
)

@Composable
fun SparkTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
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
fun Dot(active: Boolean, color: Color = Teal) {
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) color else Color(0xFF3A4252))
    )
}

/** 无依赖的线性小图标，避免引入 material-icons-extended。 */
@Composable
fun Glyph(kind: Int, tint: Color = TextMuted, size: Int = 22) {
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension
        val w = s * 0.09f
        when (kind) {
            0 -> { // 设备：机身 + 天线
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.15f, s * 0.3f),
                    size = Size(s * 0.7f, s * 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.14f),
                    style = Stroke(width = w)
                )
                drawLine(tint, Offset(s * 0.5f, s * 0.3f), Offset(s * 0.5f, s * 0.12f), w, StrokeCap.Round)
                drawCircle(tint, radius = s * 0.06f, center = Offset(s * 0.5f, s * 0.1f))
            }
            1 -> { // 遥控：十字方向
                drawLine(tint, Offset(s * 0.5f, s * 0.12f), Offset(s * 0.5f, s * 0.88f), w, StrokeCap.Round)
                drawLine(tint, Offset(s * 0.12f, s * 0.5f), Offset(s * 0.88f, s * 0.5f), w, StrokeCap.Round)
                val arrow = Path().apply {
                    moveTo(s * 0.5f, s * 0.1f); lineTo(s * 0.4f, s * 0.26f); lineTo(s * 0.6f, s * 0.26f); close()
                }
                drawPath(arrow, tint)
            }
            2 -> { // 相机
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(s * 0.1f, s * 0.28f),
                    size = Size(s * 0.8f, s * 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f),
                    style = Stroke(width = w)
                )
                drawCircle(tint, radius = s * 0.16f, center = Offset(s * 0.5f, s * 0.55f), style = Stroke(width = w))
                drawLine(tint, Offset(s * 0.36f, s * 0.2f), Offset(s * 0.64f, s * 0.2f), w, StrokeCap.Round)
            }
            else -> { // 账号
                drawCircle(tint, radius = s * 0.18f, center = Offset(s * 0.5f, s * 0.33f), style = Stroke(width = w))
                drawArc(
                    color = tint,
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
fun Label(text: String, color: Color = TextMuted, size: Int = 12) {
    Text(text, color = color, fontSize = size.sp)
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
