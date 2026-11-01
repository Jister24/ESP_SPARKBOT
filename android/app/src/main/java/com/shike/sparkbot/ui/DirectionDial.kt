package com.shike.sparkbot.ui

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

// 八向：0 = 右转（东），逆时针每 45° 一格。斜向给 0.707 分量，
// 底盘算的是 left = y + x、right = y - x，于是外侧履带满速、内侧停转，
// 正好是差速车能做的最大斜向弧线。
private val DIAL_DIRECTIONS = listOf(
    1f to 0f,           // 右转
    0.707f to 0.707f,   // 右前
    0f to 1f,           // 前进
    -0.707f to 0.707f,  // 左前
    -1f to 0f,          // 左转
    -0.707f to -0.707f, // 左后
    0f to -1f,          // 后退
    0.707f to -0.707f   // 右后
)

val DIAL_SIZE = 172.dp

/**
 * 方向圆盘：按住拖动，摇杆跟着手指走并吸附到八个方向之一；松手立刻停车。
 *
 * 只有跨扇区时才下发指令，不在每一帧拖动里发请求——`startHold` 每次都会重启
 * 重发循环，抖一下就刷一串 `/move` 出去，设备的单线程 httpd 吃不消。
 * 落在中心死区里等价于松手。
 */
@Composable
fun DirectionDial(
    enabled: Boolean,
    holdVector: Pair<Float, Float>?,
    onDirection: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    size: Dp = DIAL_SIZE
) {
    var pressing by remember { mutableStateOf(false) }
    var sector by remember { mutableStateOf(-1) }  // -1 = 中位（不发指令）
    var drag by remember { mutableStateOf(Offset.Zero) }

    val dim = if (enabled) 1f else 0.34f
    val lit = pressing || sector >= 0 || holdVector != null

    // 按住时摇杆立刻跟手（snap），松手弹回中心（spring）
    val knob by animateOffsetAsState(
        targetValue = if (pressing) drag else Offset.Zero,
        animationSpec = if (pressing) snap() else spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "dialKnob"
    )

    Box(
        Modifier
            .size(size)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val r = this.size.width.coerceAtMost(this.size.height) / 2f
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                val travel = r * 0.56f   // 摇杆最大行程
                val dead = r * 0.17f     // 中心死区

                fun push(p: Offset) {
                    val raw = p - c
                    val len = hypot(raw.x, raw.y)
                    drag = if (len > travel) raw * (travel / len) else raw
                    if (len < dead) {
                        if (sector != -1) {
                            sector = -1
                            onRelease()
                        }
                        return
                    }
                    val deg = atan2(-raw.y, raw.x) * 180.0 / PI
                    val idx = ((round(deg / 45.0).toInt() % 8) + 8) % 8
                    if (idx != sector) {
                        sector = idx
                        val dir = DIAL_DIRECTIONS[idx]
                        onDirection(dir.first, dir.second)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressing = true
                    push(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        push(change.position)
                        change.consume()
                    }
                    pressing = false
                    sector = -1
                    onRelease()
                }
            }
    ) {
        // Canvas 的 draw lambda 不是 composable 作用域，颜色得先在这里取出来
        val cBg = Bg
        val cSurface = Surface
        val cSurfaceHigh = SurfaceHigh
        val cAccent = Accent
        val cLine = Line
        val cTextMuted = TextMuted
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.width.coerceAtMost(this.size.height) / 2f
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val ringW = 2.dp.toPx()

            // 盘面：中心亮、边缘暗，做出一点凹陷的观感
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(cSurfaceHigh, cSurface, cBg),
                    center = c,
                    radius = r
                ),
                radius = r,
                center = c
            )
            drawCircle(
                color = if (lit) cAccent.copy(alpha = 0.55f) else cLine,
                radius = r - ringW / 2f,
                center = c,
                style = Stroke(ringW)
            )

            // 八个方向的刻度，主方向更长更亮
            for (i in 0 until 8) {
                val a = i * 45.0 * PI / 180.0
                val d = Offset(cos(a).toFloat(), -sin(a).toFloat())
                val cardinal = i % 2 == 0
                drawLine(
                    color = if (i == sector) cAccent
                    else cTextMuted.copy(alpha = (if (cardinal) 0.45f else 0.22f) * dim),
                    start = c + d * (r * (if (cardinal) 0.84f else 0.88f)),
                    end = c + d * (r * 0.95f),
                    strokeWidth = if (cardinal) 2.dp.toPx() else 1.3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // 当前扇区的高亮扇形（像雷达扫过的那一块）
            if (sector >= 0) {
                drawArc(
                    color = cAccent.copy(alpha = 0.16f),
                    startAngle = -(sector * 45.0 + 22.5).toFloat(),
                    sweepAngle = 45f,
                    useCenter = true,
                    topLeft = Offset(c.x - r * 0.92f, c.y - r * 0.92f),
                    size = Size(r * 1.84f, r * 1.84f)
                )
            }

            // 四个主向箭头
            for (k in intArrayOf(0, 2, 4, 6)) {
                val a = k * 45.0 * PI / 180.0
                val d = Offset(cos(a).toFloat(), -sin(a).toFloat())
                val perp = Offset(-d.y, d.x)
                val tip = c + d * (r * 0.70f)
                val base = c + d * (r * 0.53f)
                val half = r * 0.085f
                val p1 = base + perp * half
                val p2 = base - perp * half
                val path = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(p1.x, p1.y)
                    lineTo(p2.x, p2.y)
                    close()
                }
                drawPath(
                    path,
                    color = if (k == sector) cAccent else cTextMuted.copy(alpha = 0.9f * dim)
                )
            }

            // 中位标记
            drawCircle(
                color = cTextMuted.copy(alpha = 0.32f * dim),
                radius = 3.dp.toPx(),
                center = c,
                style = Stroke(1.dp.toPx())
            )

            // 摇杆
            val kc = c + knob
            val knobR = 28.dp.toPx()
            if (lit) {
                drawCircle(cAccent.copy(alpha = 0.20f), radius = knobR * 1.5f, center = kc)
            }
            drawCircle(cSurfaceHigh, radius = knobR, center = kc)
            drawCircle(
                color = if (lit) cAccent else cTextMuted.copy(alpha = 0.45f * dim),
                radius = knobR,
                center = kc,
                style = Stroke(2.dp.toPx())
            )
            if (lit) {
                drawCircle(cAccent, radius = knobR * 0.32f, center = kc)
            }
        }
    }
}

/** 「右前 / 左后 / 前进」这种两字标签，图传角标和圆盘提示共用。 */
fun dirLabel(v: Pair<Float, Float>?): String {
    if (v == null) return "行驶中"
    val (x, y) = v
    val forward = when {
        y > 0.01f -> "前"
        y < -0.01f -> "后"
        else -> ""
    }
    val turn = when {
        x > 0.01f -> "右"
        x < -0.01f -> "左"
        else -> ""
    }
    return when {
        turn.isNotEmpty() && forward.isNotEmpty() -> "$turn$forward"
        forward.isNotEmpty() -> if (forward == "前") "前进" else "后退"
        turn.isNotEmpty() -> "${turn}转"
        else -> "行驶中"
    }
}
