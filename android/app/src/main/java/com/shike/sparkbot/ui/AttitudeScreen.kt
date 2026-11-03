package com.shike.sparkbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.ImuState
import com.shike.sparkbot.UiState
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/**
 * 姿态页。3D 模型直接吃固件 `/imu` 的四元数，跟着真实转动走。
 *
 * 满舵角度、死区这些**不在这页**——那些是游戏参数，跟「姿态准不准」是两件事。
 * 这页只回答一个问题：机器人现在是什么姿势。
 *
 * 数据分两条流：文字面板吃 5Hz 的 `imuUi`，3D 视图自己订阅 20Hz 的 `imu`
 * 再平滑到 60fps。20Hz 驱动整页重组会把 120Hz 屏的帧预算吃穿（实测 p90
 * UI 线程 15.7ms vs 预算 8.3ms，19% 掉帧），体感就是「偶尔顿一下」。
 */
@Composable
fun AttitudeScreen(state: UiState, vm: AppViewModel) {
    val feed by vm.imuUi.collectAsState()
    val frame = feed.frame

    // 相机默认站在车头右前方（azimuth 负值绕世界 z 转向 -y，即右侧）
    var azimuth by remember { mutableStateOf(-36f) }
    var elevation by remember { mutableStateOf(28f) }

    // 页面可见才轮询：切走之后还在 20Hz 打设备只会白耗电、白占 socket。
    // key 带上连接目标——第一次进来可能还没选设备，那时 endpoint() 是 null，
    // 只写 LaunchedEffect(Unit) 的话选完设备也不会自动起来。
    //
    // 同时把 /status 轮询停掉：固件 httpd 是单任务串行的，一条卡住的连接
    // 就能让 /imu 排队（弱网丢包时尤其明显）。姿态页的数据全来自 /imu，
    // 不需要再让一个大请求去抢那唯一的 handler。
    LaunchedEffect(state.currentIp, state.mode) {
        vm.pauseStatusPolling()
        vm.startImuPolling()
    }
    DisposableEffect(Unit) {
        onDispose {
            vm.stopImuPolling()
            vm.resumeStatusPolling()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            "姿态",
            when {
                frame != null -> "${state.targetLabel} · ${feed.rttMs} ms · ${frame.hz} Hz"
                state.hasTarget -> "等 IMU 数据…"
                else -> "未选择设备"
            }
        )

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.94f)
                .clip(RoundedCornerShape(22.dp))
                .background(Surface)
        ) {
            AttitudeStage(
                feed = vm.imu,
                azimuth = azimuth,
                elevation = elevation,
                onOrbit = { dAz, dEl ->
                    azimuth += dAz
                    elevation = (elevation + dEl).coerceIn(-72f, 82f)
                },
                onResetView = {
                    azimuth = -36f
                    elevation = 28f
                }
            )

            if (frame == null) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.hasTarget) "无 IMU 数据" else "未连接设备",
                        color = TextMuted, fontSize = 13.sp
                    )
                }
            }

            // 右上角：视角复位。拖动改了角度之后总要能一键回去
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh)
                    .clickable {
                        azimuth = -36f
                        elevation = 28f
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text("复位视角", color = TextMuted, fontSize = 10.sp)
            }

            // 左下角：轴的颜色图例
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                AxisLegend("+x 车头", Danger)
                AxisLegend("+y 左", Teal)
                AxisLegend("+z 上", Warn)
            }
        }

        Spacer(Modifier.height(12.dp))

        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) {
                Metric("ROLL", frame?.roll, "左右倾", Modifier.weight(1f))
                Metric("PITCH", frame?.pitch, "前后倾", Modifier.weight(1f))
                Metric("YAW", frame?.yaw, "航向·会漂", Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(12.dp))

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("游戏量", color = TextMain, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "相对零点",
                    color = TextMuted, fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Metric("TILT X", frame?.tiltX, "右倾为正", Modifier.weight(1f), signed = true)
                Metric("TILT Y", frame?.tiltY, "前倾为正", Modifier.weight(1f), signed = true)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "tilt 是扣掉零点之后的倾角，游戏里映射摇杆就用这两个。" +
                    "零点默认是水平面，重启设备会恢复。",
                color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        Panel(Modifier.fillMaxWidth()) {
            InfoRow("芯片", frame?.mode?.ifBlank { "—" } ?: "—")
            InfoRow("加速度", frame?.accG?.joinToString("  ") { fmt3(it) } ?: "—")
            InfoRow("角速度", frame?.gyrDps?.joinToString("  ") { fmt1(it) } ?: "—")
            InfoRow("陀螺零偏", frame?.gbias?.joinToString("  ") { fmt2(it) } ?: "—")
            InfoRow("采样 / 序号", frame?.let { "${it.hz} Hz  ·  #${it.seq}" } ?: "—")
        }

        Spacer(Modifier.height(14.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceHigh)
                .clickable(enabled = frame != null) { vm.zeroImu() }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "把当前姿态设为手持零点",
                color = if (frame != null) TextMain else TextMuted,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "拖动漫游视角，双击复位。模型跟着真实姿态转，" +
                "yaw 没有磁力计做绝对参考，只会随时间缓慢漂移，看短时转动可以，别当罗盘。",
            color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * 单独一层，让 60fps 的平滑状态只影响这个 Canvas 的重组范围，
 * 不至于把整页的文字每帧都刷一遍。
 *
 * 这里自己订阅 20Hz 的 [feed]，而不是从上层传 q 进来——否则 20Hz 的
 * `collectAsState` 会把整个 Column（含 14 次字符串格式化）一起重组。
 */
@Composable
private fun AttitudeStage(
    feed: StateFlow<ImuState>,
    azimuth: Float,
    elevation: Float,
    onOrbit: (Float, Float) -> Unit,
    onResetView: () -> Unit
) {
    val f by feed.collectAsState()
    // 用 seq 当 key：同一帧重复重组时不必再算一次四元数
    val targetQ = remember(f.frame?.seq) { f.frame?.toQuat() }
    val smooth = rememberSmoothedQuat(targetQ)
    AttitudeView(
        q = smooth,
        azimuth = azimuth,
        elevation = elevation,
        modifier = Modifier.fillMaxSize(),
        onOrbit = onOrbit,
        onResetView = onResetView
    )
}

@Composable
private fun Metric(
    label: String,
    value: Float?,
    hint: String,
    modifier: Modifier = Modifier,
    signed: Boolean = true
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            value?.let { if (signed) signedDeg(it) else fmt1(it) } ?: "—",
            color = if (value == null) TextMuted else TextMain,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(2.dp))
        Text(hint, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextMuted, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = TextMain, fontSize = 11.sp,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

@Composable
private fun AxisLegend(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.width(10.dp).height(2.dp).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text, color = TextMuted, fontSize = 9.sp)
    }
}

/**
 * 组装四元数。优先用固件给的 q；老固件没这个字段时用 roll/pitch/yaw 合成，
 * 四级半角公式在 ZYX 次序下跟固件那套 atan2 是互逆的。
 */
private fun com.shike.sparkbot.net.SparkApi.Imu.toQuat(): Quat? {
    if (!ready) return null
    val raw = q
    if (raw != null && raw.size == 4 && abs(raw[0]) + abs(raw[1]) + abs(raw[2]) + abs(raw[3]) > 0.1f) {
        return Quat(raw[0], raw[1], raw[2], raw[3]).normalize()
    }
    return Quat.fromEulerDeg(roll, pitch, yaw)
}

/**
 * 定点格式化，替代 `String.format`。
 *
 * 这一页一屏有 14 处数字要格式化，`String.format` 每次都要解析一遍格式串、
 * 分配 Formatter，单次几十微秒、十几处叠起来就在 UI 线程上量出来了。
 * 这里直接整数取整拼字符串，行为等价于 `%.Nf`（四舍五入、不补千分位）。
 */
private fun fixed(v: Float, decimals: Int): String {
    val scale = when (decimals) {
        1 -> 10
        2 -> 100
        else -> 1000
    }
    val n = kotlin.math.round(v * scale).toInt()
    val a = if (n < 0) -n else n
    val whole = a / scale
    val frac = a % scale
    val sign = if (n < 0) "-" else ""
    return when (decimals) {
        1 -> "$sign$whole.$frac"
        // frac 补前导零：0..99 → "05"；0..999 → "005"
        2 -> "$sign$whole.${(frac + 100).toString().substring(1)}"
        else -> "$sign$whole.${(frac + 1000).toString().substring(1)}"
    }
}

private fun fmt1(v: Float) = fixed(v, 1)
private fun fmt2(v: Float) = fixed(v, 2)
private fun fmt3(v: Float) = fixed(v, 3)

/** 带符号的 `%+.1f°`；正数补 '+'，零按正数处理。 */
private fun signedDeg(v: Float): String {
    val s = fixed(v, 1)
    return if (s.startsWith("-")) "$s°" else "+$s°"
}
