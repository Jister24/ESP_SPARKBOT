package com.shike.sparkbot.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.UiState

@Composable
fun CameraScreen(state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT > 28 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) vm.capturePhoto() else vm.emit("没有存储权限，无法保存到相册")
    }

    LaunchedEffect(state.currentIp) {
        if (state.currentIp != null) vm.startCamera() else vm.stopCamera()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        ScreenHeader(
            "相机",
            if (state.cameraOn) "MJPEG 实时图传 · ${state.fps} fps" else "图传未连接"
        )

        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
        ) {
            val frame = state.frame
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "实时画面",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        color = Accent,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when {
                            !state.online -> "设备离线"
                            state.cameraError != null -> "图传失败：${state.cameraError}"
                            else -> "正在连接图传…"
                        },
                        color = TextMuted,
                        fontSize = 12.sp
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state.cameraOn) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x99000000))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(Teal))
                            Spacer(Modifier.size(6.dp))
                            Text("LIVE ${state.fps}fps", color = Color.White, fontSize = 10.sp)
                        }
                    }
                }
                if (state.holding) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.85f))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            "${dirLabel(state.holdVector)} · ${(state.speed * 100).toInt()}%",
                            color = Color.White, fontSize = 10.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (state.deviceRot) "设备 180°" else "原始方向",
                        color = Color.White, fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 方向控制：按住持续移动、松手立即停车（底盘 500ms 看门狗兜底）。
        // 放在图传下方，眼睛不用离开画面就能开；状态角标也在预览左上角同步显示。
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveKey(
                arrow = "▲", label = "前进", modifier = Modifier.weight(1f), enabled = state.currentIp != null,
                active = state.holding && state.holdVector == (0f to 1f),
                onStart = { vm.startHold(0f, 1f) }, onEnd = { vm.stopMotion() }
            )
            DriveKey(
                arrow = "▼", label = "后退", modifier = Modifier.weight(1f), enabled = state.currentIp != null,
                active = state.holding && state.holdVector == (0f to -1f),
                onStart = { vm.startHold(0f, -1f) }, onEnd = { vm.stopMotion() }
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DriveKey(
                arrow = "◀", label = "左转", modifier = Modifier.weight(1f), enabled = state.currentIp != null,
                active = state.holding && state.holdVector == (-1f to 0f),
                onStart = { vm.startHold(-1f, 0f) }, onEnd = { vm.stopMotion() }
            )
            DriveKey(
                arrow = "▶", label = "右转", modifier = Modifier.weight(1f), enabled = state.currentIp != null,
                active = state.holding && state.holdVector == (1f to 0f),
                onStart = { vm.startHold(1f, 0f) }, onEnd = { vm.stopMotion() }
            )
            Button(
                onClick = { vm.stopMotion() },
                modifier = Modifier.weight(1f).height(DRIVE_KEY_HEIGHT),
                shape = RoundedCornerShape(15.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Danger)
            ) {
                Text("急停", color = Color.White, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                if (!permissionGranted) {
                    permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    vm.capturePhoto()
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = !state.captureBusy && state.currentIp != null,
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                disabledContainerColor = SurfaceHigh
            )
        ) {
            if (state.captureBusy) {
                CircularProgressIndicator(
                    Modifier.size(18.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.size(10.dp))
            }
            Text(
                if (state.captureBusy) "抓图中…" else "拍照并保存到相册",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.restartCamera() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(13.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh)
            ) {
                Text("重连图传", color = TextMain, fontSize = 12.sp, maxLines = 1)
            }
            Button(
                onClick = { vm.toggleDeviceRotation() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(13.dp),
                enabled = state.online && !state.rotBusy,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceHigh,
                    disabledContainerColor = Surface,
                    disabledContentColor = TextMuted
                )
            ) {
                Text(
                    if (state.deviceRot) "恢复原始方向" else "画面翻转",
                    color = if (state.online && !state.rotBusy) TextMain else TextMuted,
                    fontSize = 12.sp, maxLines = 1
                )
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(intent) }
                        .onFailure { vm.emit("没有可打开相册的应用") }
                },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(13.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh)
            ) {
                Text("打开相册", color = TextMain, fontSize = 12.sp, maxLines = 1)
            }
        }

        Spacer(Modifier.height(16.dp))

        state.lastSaved?.let { path ->
            Panel(Modifier.fillMaxWidth()) {
                Text("最近保存", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(path, color = TextMain, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }

        Text(
            "长按方向键行驶，松手即停（速度在遥控页调整）。\n" +
                "画面方向由设备端处理（固件每帧转 180°），图传、拍照和机器人的小屏始终一致。\n" +
                "图传与抓图共用 8081 端口，拍照时预览会短暂中断约 1 秒。照片存放在相册 Pictures/SparkBot。",
            color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

private val DRIVE_KEY_HEIGHT = 52.dp

/** 方向键：按下即走，抬起/滑出/被打断都立刻停车。 */
@Composable
private fun DriveKey(
    arrow: String,
    label: String,
    modifier: Modifier,
    enabled: Boolean,
    active: Boolean,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val lit = pressed || active
    Box(
        modifier
            .height(DRIVE_KEY_HEIGHT)
            .clip(RoundedCornerShape(15.dp))
            .background(
                when {
                    lit -> Accent
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(arrow, color = if (lit) Color.White else if (enabled) TextMain else TextMuted,
                fontSize = 15.sp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = if (lit) Color.White else if (enabled) TextMain else TextMuted,
                fontSize = 13.sp)
        }
    }
}

private fun dirLabel(v: Pair<Float, Float>?): String = when {
    v == null -> "行驶中"
    v.second > 0f -> "前进"
    v.second < 0f -> "后退"
    v.first > 0f -> "右转"
    v.first < 0f -> "左转"
    else -> "行驶中"
}
