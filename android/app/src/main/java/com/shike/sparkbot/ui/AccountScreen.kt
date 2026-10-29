package com.shike.sparkbot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shike.sparkbot.AppViewModel
import com.shike.sparkbot.UiState

@Composable
fun AccountScreen(state: UiState, vm: AppViewModel) {
    BackHandler(enabled = state.webCanGoBack) { vm.accountGoBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("官方控制台", color = TextMain, fontSize = 20.sp)
                Text(
                    "角色 · 音色 · 声纹 · OTA",
                    color = TextMuted, fontSize = 11.sp, maxLines = 1
                )
            }
            TextButton(onClick = { vm.accountReload() }) {
                Text("刷新", color = Accent, fontSize = 13.sp)
            }
            TextButton(onClick = { vm.accountClearLogin() }) {
                Text("退出", color = TextMuted, fontSize = 13.sp)
            }
        }

        if (state.webProgress in 1..99) {
            LinearProgressIndicator(
                progress = { state.webProgress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Accent,
                trackColor = SurfaceHigh
            )
        } else {
            Spacer(Modifier.height(2.dp).fillMaxWidth().background(Line))
        }

        AndroidView(
            factory = { ctx -> vm.accountWebView(ctx) },
            // weight 占剩余高度，不用 fillMaxSize()：后者在 Column 里按整屏高度测量，
            // WebView 会比可见区域高一截，页面底部吸底的东西会被系统导航条挡住。
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}
