package com.shike.sparkbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// =============================================================================
// 姿态可视化：把固件 /imu 的四元数直接乘到机体顶点上，透视投影 + 朗伯着色。
//
// 坐标系跟固件完全一致（静止水平时 acc_g ≈ (0, 0, +1)g）：
//   +x 机体右侧   +y 车头方向   +z 机体上方（顶面法向）
// q 是 body→world，所以 世界坐标 = q.rotate(机体坐标)。不做任何转置/取反，
// 否则会出现「倾斜方向正好反了」这种很难自查的现象。
//
// 不用 OpenGL / SceneView：一个凸多面体加地面网格，透视投影只要 60 行，
// 引入 3D 引擎反而要处理上下文生命周期、纹理和 ProGuard 规则。
// =============================================================================

internal data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun unit(): V3 {
        val n = len()
        return if (n < 1e-6f) V3(0f, 0f, 1f) else V3(x / n, y / n, z / n)
    }
}

internal data class Quat(val w: Float, val x: Float, val y: Float, val z: Float) {

    fun normalize(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-6f) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** 旋转向量：世界系下的 v 转到机体系再转回来。这里只要 body→world。 */
    fun rotate(v: V3): V3 {
        // t = 2 * (q.xyz × v);  v' = v + w·t + q.xyz × t
        val t = V3(x, y, z).cross(v) * 2f
        return v + t * w + V3(x, y, z).cross(t)
    }

    fun dot(o: Quat) = w * o.w + x * o.x + y * o.y + z * o.z

    companion object {
        val IDENTITY = Quat(1f, 0f, 0f, 0f)

        /** 固件的 roll/pitch/yaw 是 ZYX（yaw 绕世界 z，pitch 再 roll），照它合成。 */
        fun fromEulerDeg(roll: Float, pitch: Float, yaw: Float): Quat {
            val r = Math.toRadians(roll.toDouble()) / 2
            val p = Math.toRadians(pitch.toDouble()) / 2
            val y = Math.toRadians(yaw.toDouble()) / 2
            val cr = cos(r); val sr = sin(r)
            val cp = cos(p); val sp = sin(p)
            val cy = cos(y); val sy = sin(y)
            return Quat(
                (cr * cp * cy + sr * sp * sy).toFloat(),
                (sr * cp * cy - cr * sp * sy).toFloat(),
                (cr * sp * cy + sr * cp * sy).toFloat(),
                (cr * cp * sy - sr * sp * cy).toFloat()
            )
        }

        /**
         * 球面插值。q 与 -q 表示同一姿态，先按点积把 b 翻到同一半球，
         * 否则会在两个采样之间绕远路转 360°——实机上表现为偶发的「猛甩一圈」。
         */
        fun slerp(a: Quat, b: Quat, t: Float): Quat {
            var bb = b
            var d = a.dot(b)
            if (d < 0f) {
                bb = Quat(-b.w, -b.x, -b.y, -b.z)
                d = -d
            }
            if (d > 0.9995f) {
                // 夹角极小时 sin 会趋 0，退化成线性插值（误差远小于显示精度）
                return Quat(
                    a.w + (bb.w - a.w) * t, a.x + (bb.x - a.x) * t,
                    a.y + (bb.y - a.y) * t, a.z + (bb.z - a.z) * t
                ).normalize()
            }
            val theta = acos(d.toDouble())
            val s = sin(theta).toFloat()
            val wa = sin((1 - t) * theta).toFloat() / s
            val wb = sin(t * theta).toFloat() / s
            return Quat(
                a.w * wa + bb.w * wb, a.x * wa + bb.x * wb,
                a.y * wa + bb.y * wb, a.z * wa + bb.z * wb
            ).normalize()
        }
    }
}

/** 两个姿态之间的夹角（度），用来判断「要不要再刷新一次 UI」。 */
internal fun Quat.angleTo(o: Quat): Float {
    val d = min(1f, abs(dot(o)))
    return Math.toDegrees(2.0 * acos(d.toDouble())).toFloat()
}

/**
 * 平滑：固件 100Hz 采样，但 App 走 HTTP 只拿得到 ~20Hz 且间隔抖动。
 * 每帧朝最新样本做一次 slerp，把 20Hz 的阶跃变成 60fps 的连续转动。
 * 时间常数 45ms ≈ 略小于一个采样周期，既抹掉台阶又不会明显拖后。
 */
@Composable
internal fun rememberSmoothedQuat(target: Quat?): Quat {
    var shown by remember { mutableStateOf(Quat.IDENTITY) }
    val latest = rememberUpdatedState(target)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.1f)
                last = now
                val t = latest.value ?: return@withFrameNanos
                val next = Quat.slerp(shown, t, 1f - exp(-dt / 0.045f))
                // 小于约 0.08° 的残余不再触发重组，静止时省电
                if (shown.angleTo(next) > 0.08f) shown = next
            }
        }
    }
    return shown
}

// ---------------------------------------------------------------------------
// 机体模型：一个略高的盒子（机身）+ 正面屏幕 + 顶面朝前箭头。
// 尺寸按 60×60×70mm 的机身取相对比例，视觉上比真实略瘦，倾斜更容易看出来。
// ---------------------------------------------------------------------------

private const val HX = 1.0f   // 半宽
private const val HY = 1.05f  // 半长（车头方向）
private const val HZ = 1.15f  // 半高

private data class Face(
    val corners: IntArray,
    val normal: V3,
    /** 各面的固有明暗，让同色面在没有光照差异时也能分开 */
    val tint: Float
)

private val BOX_VERTICES = arrayOf(
    V3(-HX, -HY, -HZ), V3(HX, -HY, -HZ), V3(HX, HY, -HZ), V3(-HX, HY, -HZ),
    V3(-HX, -HY, HZ), V3(HX, -HY, HZ), V3(HX, HY, HZ), V3(-HX, HY, HZ)
)

/** 顶点序按「从外侧看逆时针」，这样叉积恒指向外，背面剔除才成立。 */
private val BOX_FACES = listOf(
    Face(intArrayOf(6, 2, 3, 7), V3(0f, 1f, 0f), 1.00f),   // +y 前
    Face(intArrayOf(0, 1, 5, 4), V3(0f, -1f, 0f), 0.72f),  // -y 后
    Face(intArrayOf(1, 2, 6, 5), V3(1f, 0f, 0f), 0.86f),   // +x 右
    Face(intArrayOf(3, 0, 4, 7), V3(-1f, 0f, 0f), 0.78f),  // -x 左
    Face(intArrayOf(4, 5, 6, 7), V3(0f, 0f, 1f), 1.12f),   // +z 顶
    Face(intArrayOf(1, 0, 3, 2), V3(0f, 0f, -1f), 0.55f)   // -z 底
)

/** 正面那块表情屏，贴在 +y 面上、略微外移避免与本体共面打架。 */
private val SCREEN_QUAD = arrayOf(
    V3(0.60f, HY + 0.004f, 0.52f), V3(0.60f, HY + 0.004f, -0.50f),
    V3(-0.60f, HY + 0.004f, -0.50f), V3(-0.60f, HY + 0.004f, 0.52f)
)

/** 顶面指向车头的箭头（三个点，从 +z 看逆时针）。 */
private val ARROW_TRI = arrayOf(
    V3(0f, 0.78f, HZ + 0.004f),
    V3(-0.30f, 0.14f, HZ + 0.004f),
    V3(0.30f, 0.14f, HZ + 0.004f)
)

/** 相机基向量。垂直方向的 up 用世界 +z（跟固件 z 朝上一致）。 */
private class Camera(val eye: V3, val forward: V3, val right: V3, val up: V3)

/**
 * 相机站在 +y（车头）一侧看向原点。right = forward × up 是右手系推导出来的，
 * 因此相机在车头前方时，机体的 +x 落在屏幕**左**侧——这是「面对面看着它」
 * 的正常观感，反过来写会让左右倾看起来是镜像的。
 */
private fun cameraFor(azimuthDeg: Float, elevationDeg: Float, dist: Float): Camera {
    val az = Math.toRadians(azimuthDeg.toDouble())
    val el = Math.toRadians(elevationDeg.toDouble())
    val dir = V3(
        (sin(az) * cos(el)).toFloat(),
        (cos(az) * cos(el)).toFloat(),
        sin(el).toFloat()
    )
    val eye = dir * dist
    val forward = (V3(0f, 0f, 0f) - eye).unit()
    val right = forward.cross(V3(0f, 0f, 1f)).unit()
    val up = right.cross(forward).unit()
    return Camera(eye, forward, right, up)
}

private class Projector(
    private val cam: Camera,
    private val cx: Float,
    private val cy: Float,
    private val focal: Float
) {
    fun project(p: V3): Offset? {
        val d = p - cam.eye
        val zc = d.dot(cam.forward)
        if (zc < 0.05f) return null
        return Offset(
            cx + focal * d.dot(cam.right) / zc,
            cy - focal * d.dot(cam.up) / zc
        )
    }
}

private class PaintedFace(val depth: Float, val path: Path, val color: Color)

/** Andrew monotone chain。阴影用，只有 8 个点，不必担心开销。 */
private fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size < 3) return points
    val pts = points.sortedWith(compareBy({ it.x }, { it.y }))
    fun cross(o: Offset, a: Offset, b: Offset) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val lower = ArrayList<Offset>()
    for (p in pts) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) {
            lower.removeAt(lower.size - 1)
        }
        lower.add(p)
    }
    val upper = ArrayList<Offset>()
    for (p in pts.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) {
            upper.removeAt(upper.size - 1)
        }
        upper.add(p)
    }
    return lower.dropLast(1) + upper.dropLast(1)
}

private fun shade(c: Color, k: Float): Color = Color(
    (c.red * k).coerceIn(0f, 1f),
    (c.green * k).coerceIn(0f, 1f),
    (c.blue * k).coerceIn(0f, 1f),
    c.alpha
)

/**
 * 姿态视图本体。
 *
 * @param q         已平滑的四元数；null 表示没数据，此时按水平姿态画并压暗
 * @param azimuth   相机绕世界 z 的方位角（度）
 * @param elevation 相机仰角（度）
 * @param onOrbit   拖动回调，参数是 (dAzimuth, dElevation)
 */
@Composable
internal fun AttitudeView(
    q: Quat?,
    azimuth: Float,
    elevation: Float,
    modifier: Modifier = Modifier,
    onOrbit: (Float, Float) -> Unit = { _, _ -> },
    onResetView: () -> Unit = {}
) {
    // 颜色必须在 Canvas 的 draw lambda 外取：这些是 @Composable get()
    // 机体基色把 surfaceHigh 往 accent 里掺一档：纯 surfaceHigh 在深色主题下
    // 跟背景只差十几级灰，乘上光照系数后各面几乎同色，看不出立体感。
    val bodyColor = lerp(SurfaceHigh, Accent, 0.45f)
    val screenColor = Accent
    // 网格用 Line 和 TextMuted 掺一半：纯 Line 在深色主题下跟背景只差几级灰，
    // 投到远处基本看不见，地面就没法当参照物了。
    val gridColor = lerp(Line, TextMuted, 0.55f)
    val axisX = Danger
    val axisY = Teal
    val axisZ = Warn
    val outline = TextMuted
    val shadowColor = Color.Black

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onOrbit(-drag.x * 0.35f, -drag.y * 0.30f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { onResetView() })
            }
    ) {
        val orientation = q ?: Quat.IDENTITY
        val cam = cameraFor(azimuth, elevation, dist = 9.2f)
        val focal = min(size.width, size.height) * 1.55f
        val proj = Projector(cam, size.width / 2f, size.height * 0.52f, focal)

        // 世界顶点 = q · 机体顶点
        val world = BOX_VERTICES.map { orientation.rotate(it) }

        // ---- 地面网格：机体悬在上面，倾斜时能看出朝向 ----
        val ground = -1.75f
        val n = 4
        val step = 1.5f
        for (i in -n..n) {
            val t = i * step
            val major = i == 0
            val c = gridColor.copy(alpha = if (major) 0.75f else 0.35f)
            val w = if (major) 1.6f else 1f

            val a1 = proj.project(V3(t, -n * step, ground))
            val a2 = proj.project(V3(t, n * step, ground))
            if (a1 != null && a2 != null) drawLine(c, a1, a2, w)

            val b1 = proj.project(V3(-n * step, t, ground))
            val b2 = proj.project(V3(n * step, t, ground))
            if (b1 != null && b2 != null) drawLine(c, b1, b2, w)
        }

        // ---- 地面阴影：把 8 个顶点垂直压到地面取凸包 ----
        val shadowPts = world.mapNotNull { proj.project(V3(it.x, it.y, ground)) }
        if (shadowPts.size >= 3) {
            val hull = convexHull(shadowPts)
            val path = Path().apply {
                moveTo(hull[0].x, hull[0].y)
                for (k in 1 until hull.size) lineTo(hull[k].x, hull[k].y)
                close()
            }
            drawPath(path, shadowColor.copy(alpha = 0.22f))
        }

        // ---- 机体的面：背面剔除 + 深度排序 ----
        // 光源跟着相机走（头灯 + 偏上的补光）。之前把光源写死在世界系 (-0.45,-0.55,0.70)
        // ——那个方向是车的背面上方，正对相机的前面/侧面 lambert 恒为 0，只剩 0.42 的
        // 环境光，乘完 tint 后就压成了近黑的藏青。改成相机相对后，无论怎么拖动轨道，
        // 朝向观察者的一面总有光，同时上方补光保证顶面比侧面亮，立体感不塌。
        val light = (cam.eye.unit() * 0.85f + V3(0f, 0f, 1f) * 0.55f).unit()
        val faces = ArrayList<PaintedFace>(BOX_FACES.size)
        for (face in BOX_FACES) {
            val nWorld = orientation.rotate(face.normal)
            var centroid = V3(0f, 0f, 0f)
            for (vi in face.corners) centroid += world[vi]
            centroid *= 1f / face.corners.size
            val viewDir = (centroid - cam.eye).unit()
            if (nWorld.dot(viewDir) > 0f) continue   // 背面

            val lambert = max(0f, nWorld.dot(light))
            // 环境项 0.34 + 漫反射 0.60：最亮的面也不会过曝（0.34+0.60=0.94，乘 tint 上限 1.12 后仍 <1.06）
            val k = (0.34f + 0.60f * lambert) * face.tint
            val path = Path()
            var projectedAll = true
            for ((idx, vi) in face.corners.withIndex()) {
                val p = proj.project(world[vi])
                if (p == null) { projectedAll = false; break }
                if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            if (!projectedAll) continue
            path.close()
            faces += PaintedFace((centroid - cam.eye).dot(cam.forward), path, shade(bodyColor, k))
        }
        faces.sortByDescending { it.depth }
        faces.forEach { drawPath(it.path, it.color) }

        // ---- 正面屏幕：只有朝前时才可见（背面剔除同样做一遍） ----
        val frontVisible = orientation.rotate(V3(0f, 1f, 0f)).dot(
            (orientation.rotate(SCREEN_QUAD[0]) - cam.eye).unit()
        ) < 0f
        if (frontVisible) {
            val p = SCREEN_QUAD.map { proj.project(orientation.rotate(it)) }
            if (p.all { it != null }) {
                val path = Path().apply {
                    moveTo(p[0]!!.x, p[0]!!.y)
                    for (k in 1 until p.size) lineTo(p[k]!!.x, p[k]!!.y)
                    close()
                }
                drawPath(path, screenColor.copy(alpha = 0.22f))
                drawPath(path, screenColor.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            }
        }

        // ---- 顶面箭头：标出车头，倾斜时一眼看出机身转向 ----
        val topVisible = orientation.rotate(V3(0f, 0f, 1f)).dot(
            (orientation.rotate(V3(0f, 0f, HZ)) - cam.eye).unit()
        ) < 0f
        if (topVisible) {
            val p = ARROW_TRI.map { proj.project(orientation.rotate(it)) }
            if (p.all { it != null }) {
                val path = Path().apply {
                    moveTo(p[0]!!.x, p[0]!!.y)
                    lineTo(p[1]!!.x, p[1]!!.y)
                    lineTo(p[2]!!.x, p[2]!!.y)
                    close()
                }
                drawPath(path, screenColor.copy(alpha = 0.85f))
            }
        }

        // ---- 机体坐标轴：从中心伸出到体外，绕竖直轴自转时 x / y 会跟着扫过 ----
        // 画在机体之后（覆盖），视觉上是「从机身插出来的三根针」，
        // 比被不透明面挡掉一半更清楚。
        val origin = proj.project(orientation.rotate(V3(0f, 0f, 0f)))
        if (origin != null) {
            val axisEnds = listOf(
                V3(1.9f, 0f, 0f) to axisX,
                V3(0f, 1.9f, 0f) to axisY,
                V3(0f, 0f, 1.9f) to axisZ
            )
            for ((end, color) in axisEnds) {
                val o = proj.project(orientation.rotate(end)) ?: continue
                drawLine(color.copy(alpha = 0.9f), origin, o, 2.4f, StrokeCap.Round)
                drawCircle(color, radius = 4f, center = o)
            }
        }

        // ---- 边框：让画面有「仪表」的分界感 ----
        drawRoundRectOutline(outline.copy(alpha = 0.18f))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRoundRectOutline(color: Color) {
    drawRoundRect(
        color = color,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(28f),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
    )
}
