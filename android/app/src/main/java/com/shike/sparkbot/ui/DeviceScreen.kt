package com.shike.sparkbot.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.UiState

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
        ScreenHeader("设备", "同一 Wi-Fi 下直连，无需云端") {
            TextButton(onClick = { vm.scan() }, enabled = !state.scanning) {
                Text(
                    if (state.scanning) "扫描中 ${state.scanDone}/254" else "扫描局域网",
                    color = if (state.scanning) TextMuted else Accent,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        CurrentDevicePanel(state, vm)

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
            OutlinedTextField(
                value = manualIp,
                onValueChange = { manualIp = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("192.168.1.50", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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

        Spacer(Modifier.height(18.dp))
        TipsCard()
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun CurrentDevicePanel(state: UiState, vm: AppViewModel) {
    val device = state.current
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(state.online)
            Spacer(Modifier.width(8.dp))
            Text(
                device?.name ?: "未选择设备",
                color = TextMain, fontSize = 17.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    state.online -> "在线"
                    device != null -> "离线"
                    else -> "待连接"
                },
                color = when {
                    state.online -> Teal
                    device != null -> Danger
                    else -> TextMuted
                },
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(device?.ip ?: "先扫描或手动添加 IP", color = TextMuted, fontSize = 13.sp)

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("延迟", state.latencyMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
            Metric("运行", state.status?.let { formatUptime(it.uptimeSec) } ?: "—", Modifier.weight(1f))
            Metric("摄像头", if (state.status?.camera == true) "可用" else "—", Modifier.weight(1f))
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

        if (device != null) {
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

@Composable
private fun TipsCard() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF101821))
            .padding(14.dp)
    ) {
        Column {
            Text("连不上怎么办", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "1. 确认手机和设备连的是同一个路由器（不是 5G 访客网络）\n" +
                    "2. 设备重启后 IP 可能变化，重新扫描即可\n" +
                    "3. 路由器开了「AP 隔离」时设备与手机互相不可见\n" +
                    "4. 建议在路由器给设备 MAC 绑定静态 IP",
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
