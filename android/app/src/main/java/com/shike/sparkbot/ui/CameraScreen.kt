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

    LaunchedEffect(state.currentIp, state.online) {
        if (state.online) vm.startCamera() else vm.stopCamera()
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
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x99000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        if (state.flipVertical) "640×480 · 已翻转" else "640×480 · 原始",
                        color = Color.White, fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

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
            enabled = !state.captureBusy && state.online,
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
                onClick = { vm.toggleFlipVertical() },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(13.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceHigh)
            ) {
                Text(
                    if (state.flipVertical) "恢复原始画面" else "上下翻转",
                    color = TextMain, fontSize = 12.sp, maxLines = 1
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
            "图传与抓图共用 8081 端口，拍照时预览会短暂中断约 1 秒。照片存放在相册 Pictures/SparkBot。",
            color = TextMuted, fontSize = 11.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}
