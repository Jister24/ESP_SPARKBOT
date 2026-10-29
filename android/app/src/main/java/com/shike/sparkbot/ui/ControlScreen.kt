package com.shike.sparkbot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.UiState

private val LIGHT_MODES = listOf(
    0 to "充电呼吸", 1 to "低电", 2 to "常亮", 3 to "闪烁",
    4 to "白光慢呼吸", 5 to "白光快呼吸", 6 to "流水", 7 to "灯效秀", 8 to "睡眠"
)

@Composable
fun ControlScreen(state: UiState, vm: AppViewModel) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            "遥控",
            if (state.online) "${state.currentIp} · ${state.latencyMs ?: 0} ms" else "设备离线"
        )

        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Surface)
                .padding(vertical = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                DirectionPad(state, vm)
                Spacer(Modifier.height(16.dp))
                Text(
                    if (state.holding) "行进中…" else "按住方向键持续移动，松手即停",
                    color = if (state.holding) Accent else TextMuted,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = { vm.stopMotion() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Danger)
        ) {
            Text("急停", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(20.dp))

        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("速度", color = TextMain, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("${(state.speed * 100).toInt()}%", color = Accent, fontSize = 14.sp)
            }
            Slider(
                value = state.speed,
                onValueChange = { vm.updateSpeed(it) },
                valueRange = 0.1f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = SurfaceHigh
                )
            )
            Text("速度百分比直接乘到 x / y 上，100% 即满速", color = TextMuted, fontSize = 11.sp)
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { vm.dance() },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh)
            ) {
                Text("跳舞 d1", color = TextMain, fontSize = 13.sp)
            }
            Button(
                onClick = { vm.toggleFlipVertical() },
                modifier = Modifier.weight(1f).height(46.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh)
            ) {
                Text(
                    if (state.flipVertical) "画面：上下翻转" else "画面：原始",
                    color = TextMain, fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("灯光模式", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            LIGHT_MODES.forEach { (mode, label) ->
                val active = state.lightMode == mode
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) Accent else SurfaceHigh)
                        .clickable { vm.setLight(mode) }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        "$mode · $label",
                        color = if (active) androidx.compose.ui.graphics.Color.White else TextMuted,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Panel(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("底盘原始指令", color = TextMain, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Dot(state.status != null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.status != null) "链路正常" else "无数据",
                    color = if (state.status != null) Teal else TextMuted,
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "x = ${fmt(state.status?.x ?: 0f)}   y = ${fmt(state.status?.y ?: 0f)}\n" +
                    "y > 0 前进，y < 0 后退；x > 0 右转，x < 0 左转",
                color = TextMuted, fontSize = 12.sp, lineHeight = 19.sp
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "底盘看门狗 500ms：松手后自动停车。跳舞指令会阻塞底盘约 17 秒，期间方向键无效。",
            color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun DirectionPad(state: UiState, vm: AppViewModel) {
    val cell = 84.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        PadButton("▲", cell, enabled = state.online,
            onStart = { vm.startHold(0f, 1f) }, onEnd = { vm.stopMotion() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadButton("◀", cell, enabled = state.online,
                onStart = { vm.startHold(-1f, 0f) }, onEnd = { vm.stopMotion() })
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(cell)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.holding) {
                        val v = state.holdVector
                        if (v != null) "${v.first.toInt()},${v.second.toInt()}" else "···"
                    } else {
                        "方向"
                    },
                    color = if (state.holding) Accent else TextMuted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            PadButton("▶", cell, enabled = state.online,
                onStart = { vm.startHold(1f, 0f) }, onEnd = { vm.stopMotion() })
        }
        PadButton("▼", cell, enabled = state.online,
            onStart = { vm.startHold(0f, -1f) }, onEnd = { vm.stopMotion() })
    }
}

@Composable
private fun PadButton(
    label: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    pressed -> Accent
                    enabled -> SurfaceHigh
                    else -> Surface
                }
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onStart()
                    waitForUpOrCancellation()
                    pressed = false
                    onEnd()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) TextMain else TextMuted,
            fontSize = 24.sp
        )
    }
}

private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)
