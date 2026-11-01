package com.shike.sparkbot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.UiState
import com.shike.sparkbot.net.LinkMode
import java.util.Locale

@Composable
fun DeviceScreen(state: UiState, vm: AppViewModel) {
    var manualIp by rememberSaveable { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            "设备",
            when (state.mode) {
                LinkMode.LAN -> "同一 Wi-Fi 下直连，无需云端"
                LinkMode.REMOTE -> "经服务器中转，任何网络都能连"
            }
        ) {
            if (state.mode == LinkMode.LAN) {
                TextButton(onClick = { vm.scan() }, enabled = !state.scanning) {
                    Text(
                        if (state.scanning) "扫描中 ${state.scanDone}/254" else "扫描局域网",
                        color = if (state.scanning) TextMuted else Accent,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        ModeSwitch(state, vm)

        Spacer(Modifier.height(14.dp))
        CurrentDevicePanel(state, vm)

        if (state.mode == LinkMode.REMOTE) {
            Spacer(Modifier.height(14.dp))
            RemotePanel(state, vm)
        }

        if (state.mode == LinkMode.LAN) {
            Spacer(Modifier.height(22.dp))
            Text("设备列表", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))

            if (state.devices.isEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("还没有设备", color = TextMain, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "点右上角扫描，或手动输入设备 IP。设备 IP 变了的话也可以在路由器里给它绑定静态地址。",
                        color = TextMuted, fontSize = 12.sp
                    )
                }
            } else {
                state.devices.forEach { device ->
                    DeviceRow(
                        name = device.name,
                        ip = device.ip,
                        current = device.ip == state.currentIp,
                        online = device.ip == state.currentIp && state.online,
                        onSelect = { vm.selectDevice(device.ip) },
                        onDelete = { vm.removeDevice(device.ip) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("手动添加", color = TextMain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DarkField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    placeholder = "192.168.1.50",
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = {
                        vm.addDevice(manualIp)
                        manualIp = ""
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("添加", fontSize = 13.sp)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        TipsCard(state.mode)
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CurrentDevicePanel(state: UiState, vm: AppViewModel) {
    val device = state.current
    val remote = state.mode == LinkMode.REMOTE
    val title = when {
        remote -> "远程接入"
        device != null -> device.name
        else -> "未选择设备"
    }
    val address = when {
        remote && state.remoteHost.isNotBlank() -> "https://${state.remoteHost}/bot"
        remote -> "还没填服务器地址"
        device != null -> device.ip
        else -> "先扫描或手动添加 IP"
    }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(state.online)
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.online -> "在线"
                    state.hasTarget -> "离线"
                    else -> "待连接"
                },
                color = when {
                    state.online -> Teal
                    state.hasTarget -> Danger
                    else -> TextMuted
                },
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(address, color = TextMuted, fontSize = 13.sp)

        // 局域网里连着离线，九成是设备重启后换了 IP：把可能的原因直接写在脸上，
        // 别让人对着「离线」两个字猜。
        if (!remote && state.hasTarget && !state.online && !state.scanning) {
            Spacer(Modifier.height(6.dp))
            Text(
                "连不上这个地址。设备重启或 Wi-Fi 重连后 IP 可能会变，点右上角「扫描局域网」重新找一次。",
                color = Warn, fontSize = 11.sp, lineHeight = 17.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("延迟", state.latencyMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
            Metric("运行", state.status?.let { formatUptime(it.uptimeSec) } ?: "—", Modifier.weight(1f))
            Metric("摄像头", if (state.status?.camera == true) "可用" else "—", Modifier.weight(1f))
        }

        // 老固件的 /status 没有 batt 字段，这里整块静默不显示，不要摆一个假的 0%
        state.status?.batt?.let { batt ->
            Spacer(Modifier.height(12.dp))
            PowerSection(
                bodyPercent = batt,
                bodyMillivolts = state.status.battMv,
                bodyCharging = state.status.charging,
                chassisPercent = state.status.chassisBatt,
                chassisMillivolts = state.status.chassisBattMv,
                chassisCharging = state.status.chassisCharging
            )
        }

        if (state.scanning) {
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { state.scanDone / 254f },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                color = Accent,
                trackColor = SurfaceHigh
            )
            Spacer(Modifier.height(6.dp))
            Text("正在扫描 ${state.scanDone}/254 个地址…", color = TextMuted, fontSize = 11.sp)
        }

        if (state.hasTarget) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.refreshStatus() }) {
                    Text("刷新状态", color = Accent, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (state.deviceRot) "画面已 180° 转正" else "原始方向",
                    color = TextMuted, fontSize = 11.sp
                )
            }
        }
    }
}

// 电量色阶：≤20% 红、≤50% 琥珀、其余青绿。和屏幕角标用同一套阈值，看起来才一致
@Composable
private fun levelColor(percent: Int): Color = when {
    percent <= 20 -> Danger
    percent <= 50 -> Warn
    else -> Teal
}

/**
 * 电源区：本体 + 底盘各一块。
 *
 * 底盘电量是主体板通过 UART 问 C2 拿到的，没改过的底盘固件不会应答，
 * 这时 chassisPercent 为 null，只画本体一块（不留空位）。
 */
@Composable
private fun PowerSection(
    bodyPercent: Int,
    bodyMillivolts: Int?,
    bodyCharging: Boolean,
    chassisPercent: Int?,
    chassisMillivolts: Int?,
    chassisCharging: Boolean
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceHigh)
            .padding(horizontal = 11.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("电源", color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (chassisPercent == null) {
                Text("底盘未上报", color = TextMuted.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(9.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BatteryTile(
                label = "本体", percent = bodyPercent,
                millivolts = bodyMillivolts, charging = bodyCharging,
                modifier = Modifier.weight(1f)
            )
            if (chassisPercent != null) {
                BatteryTile(
                    label = "底盘", percent = chassisPercent,
                    millivolts = chassisMillivolts, charging = chassisCharging,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BatteryTile(
    label: String,
    percent: Int,
    millivolts: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier
) {
    val safe = percent.coerceIn(0, 100)
    val level = levelColor(safe)
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 11.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (charging) {
                Text(
                    "充电",
                    color = Teal, fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Teal.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$safe",
                color = if (safe <= 20) Danger else TextMain,
                fontSize = 25.sp, fontWeight = FontWeight.SemiBold
            )
            Text(
                "%", color = TextMuted, fontSize = 12.sp,
                modifier = Modifier.padding(start = 1.dp, bottom = 4.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        BatteryGauge(safe, level)
        Spacer(Modifier.height(6.dp))
        Text(
            // 充电状态已经由右上角的胶囊表达了，这里只说电压，别重复
            millivolts?.let { String.format(Locale.US, "%.2f V", it / 1000f) } ?: "—",
            color = TextMuted, fontSize = 11.sp
        )
    }
}

/** 画成一个电池：外壳 + 内填充 + 正极触点，填充比例跟着百分比走 */
@Composable
private fun BatteryGauge(percent: Int, color: Color) {
    val track = TextMuted.copy(alpha = 0.35f)
    Canvas(Modifier.fillMaxWidth().height(11.dp)) {
        val h = size.height
        val capW = h * 0.32f
        val gap = h * 0.14f
        val bodyW = size.width - capW - gap
        val stroke = (h * 0.15f).coerceAtLeast(1.2f)
        drawRoundRect(
            color = track,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(bodyW - stroke, h - stroke),
            cornerRadius = CornerRadius(h * 0.28f),
            style = Stroke(width = stroke)
        )
        drawRoundRect(
            color = track,
            topLeft = Offset(bodyW + gap, h * 0.31f),
            size = Size(capW, h * 0.38f),
            cornerRadius = CornerRadius(capW * 0.45f)
        )
        val inset = stroke + h * 0.1f
        val maxWidth = bodyW - inset * 2f
        val filled = maxWidth * (percent.coerceIn(0, 100) / 100f)
        if (filled > 0f) {
            drawRoundRect(
                color = color,
                topLeft = Offset(inset, inset),
                size = Size(filled.coerceAtLeast(h * 0.18f), h - inset * 2f),
                cornerRadius = CornerRadius(h * 0.18f)
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceHigh)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(label, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun DeviceRow(
    name: String,
    ip: String,
    current: Boolean,
    online: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (current) AccentSoft else Surface)
            .clickable { onSelect() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(online)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = TextMain, fontSize = 14.sp)
            Text(ip, color = TextMuted, fontSize = 11.sp)
        }
        if (current) {
            Text("当前", color = Accent, fontSize = 11.sp)
            Spacer(Modifier.width(12.dp))
        }
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(50))
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("×", color = TextMuted, fontSize = 18.sp)
        }
    }
}

/** 局域网 / 远程二选一。切换会立刻换连接目标并重新轮询状态。 */
@Composable
private fun ModeSwitch(state: UiState, vm: AppViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceHigh)
            .padding(4.dp)
    ) {
        ModeTab("局域网", "同一 Wi-Fi", state.mode == LinkMode.LAN, Modifier.weight(1f)) {
            vm.setMode(LinkMode.LAN)
        }
        ModeTab("远程", "任何网络", state.mode == LinkMode.REMOTE, Modifier.weight(1f)) {
            vm.setMode(LinkMode.REMOTE)
        }
    }
}

@Composable
private fun ModeTab(
    title: String,
    hint: String,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) Accent else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            title,
            color = if (active) Color.White else TextMain,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            hint,
            color = if (active) Color(0xCCFFFFFF) else TextMuted,
            fontSize = 10.sp
        )
    }
}

/** 远程服务器地址与 Basic 账号。改完点保存立刻重连，不用退出重进。 */
@Composable
private fun RemotePanel(state: UiState, vm: AppViewModel) {
    var host by remember(state.remoteHost) { mutableStateOf(state.remoteHost) }
    var user by remember(state.remoteUser) { mutableStateOf(state.remoteUser) }
    var pass by remember(state.remotePass) { mutableStateOf(state.remotePass) }

    val dirty = host.trim() != state.remoteHost ||
        user.trim() != state.remoteUser ||
        pass.trim() != state.remotePass

    Panel(Modifier.fillMaxWidth()) {
        Text("服务器", color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "隧道由家里的 NAS 主动连出，所以手机在哪张网里都能控制。改完保存会立刻重连。",
            color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp
        )

        Spacer(Modifier.height(12.dp))
        Label("地址（不要带 https://）")
        Spacer(Modifier.height(6.dp))
        DarkField(
            value = host,
            onValueChange = { host = it },
            placeholder = "bot.example.com",
            keyboardType = KeyboardType.Uri,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Label("账号")
        Spacer(Modifier.height(6.dp))
        DarkField(
            value = user,
            onValueChange = { user = it },
            placeholder = "sparkbot",
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))
        Label("密码")
        Spacer(Modifier.height(6.dp))
        DarkField(
            value = pass,
            onValueChange = { pass = it },
            placeholder = "服务器上的 Basic 密码",
            password = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { vm.updateRemote(host, user, pass) },
                enabled = dirty,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent)
            ) {
                Text("保存并重连", fontSize = 13.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (dirty) "有未保存的修改" else "已保存",
                color = if (dirty) TextMuted else Teal,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun DarkField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = { Text(placeholder, color = TextMuted, fontSize = 13.sp) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Line,
            focusedContainerColor = Surface,
            unfocusedContainerColor = Surface,
            cursorColor = Accent,
            focusedTextColor = TextMain,
            unfocusedTextColor = TextMain
        )
    )
}

@Composable
private fun TipsCard(mode: LinkMode) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Field)
            .padding(14.dp)
    ) {
        Column {
            Text(
                if (mode == LinkMode.REMOTE) "远程连不上怎么办" else "连不上怎么办",
                color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (mode == LinkMode.REMOTE) {
                    "1. 家里 NAS 上的 frpc 容器要在运行（极空间 → Docker → 容器）\n" +
                        "2. 设备本身要在线：局域网点「刷新状态」能出延迟就说明设备没问题\n" +
                        "3. 提示账号或密码不对，说明服务器上的鉴权文件被改过\n" +
                        "4. 远程图传比局域网慢，属于固件编码瓶颈，不是网络问题"
                } else {
                    "1. 确认手机和设备连的是同一个路由器（不是 5G 访客网络）\n" +
                        "2. 设备重启后 IP 可能变化，重新扫描即可\n" +
                        "3. 路由器开了「AP 隔离」时设备与手机互相不可见\n" +
                        "4. 建议在路由器给设备 MAC 绑定静态 IP"
                },
                color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp
            )
        }
    }
}

fun formatUptime(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    else -> "${seconds / 3600}h${(seconds % 3600) / 60}m"
}
