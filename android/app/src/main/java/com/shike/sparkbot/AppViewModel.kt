package com.shike.sparkbot

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shike.sparkbot.data.Device
import com.shike.sparkbot.data.DeviceStore
import com.shike.sparkbot.media.ImageOps
import com.shike.sparkbot.media.PhotoSaver
import com.shike.sparkbot.net.Discovery
import com.shike.sparkbot.net.MjpegStreamer
import com.shike.sparkbot.net.SparkApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val devices: List<Device> = emptyList(),
    val currentIp: String? = null,
    val status: SparkApi.Status? = null,
    val latencyMs: Int? = null,
    val scanning: Boolean = false,
    val scanDone: Int = 0,
    val speed: Float = 1f,
    val holding: Boolean = false,
    val holdVector: Pair<Float, Float>? = null,
    val cameraOn: Boolean = false,
    val cameraError: String? = null,
    val frame: ImageBitmap? = null,
    val fps: Int = 0,
    val captureBusy: Boolean = false,
    val lastSaved: String? = null,
    val lightMode: Int? = null,
    val webProgress: Int = 0,
    val webTitle: String = "官方控制台",
    val webCanGoBack: Boolean = false,
    val flipVertical: Boolean = true
) {
    val online: Boolean get() = status != null
    val current: Device? get() = devices.firstOrNull { it.ip == currentIp }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DeviceStore(app)
    private val _state = MutableStateFlow(
        UiState(
            devices = store.devices(),
            currentIp = store.currentIp(),
            speed = store.speed(),
            flipVertical = store.flipVertical()
        )
    )
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    private var pollJob: Job? = null
    private var holdJob: Job? = null
    private var streamer: MjpegStreamer? = null
    private var frameCount = 0
    private var fpsMark = 0L
    private var webView: WebView? = null

    init {
        if (_state.value.currentIp == null) {
            _state.value.devices.firstOrNull()?.let { selectDevice(it.ip, notify = false) }
        } else {
            startPolling()
        }
        if (_state.value.devices.isEmpty()) {
            viewModelScope.launch { scan() }
        }
    }

    // ---------------- 设备 ----------------

    fun selectDevice(ip: String, notify: Boolean = true) {
        if (_state.value.currentIp == ip && pollJob != null) return
        stopCamera()
        stopMotion()
        store.setCurrentIp(ip)
        _state.update { it.copy(currentIp = ip, status = null, latencyMs = null) }
        startPolling()
        if (notify) emit("已切换到 $ip")
    }

    fun addDevice(rawIp: String) {
        val ip = rawIp.trim()
            .removePrefix("http://").removePrefix("https://")
            .substringBefore('/')
            .substringBefore(':')
        if (!IP_REGEX.matches(ip) || ip.split(".").any { (it.toIntOrNull() ?: 999) > 255 }) {
            emit("IP 格式不对，示例 192.168.1.50")
            return
        }
        val list = _state.value.devices.toMutableList()
        if (list.none { it.ip == ip }) {
            list.add(0, Device(ip, DeviceStore.defaultName(ip), System.currentTimeMillis()))
            persist(list)
        }
        selectDevice(ip)
        viewModelScope.launch {
            val st = withContext(Dispatchers.IO) {
                runCatching { SparkApi.status(ip, 2000) }.getOrNull()
            }
            if (st == null) emit("已保存 $ip，但暂时连不上") else emit("已连接 $ip")
        }
    }

    fun removeDevice(ip: String) {
        val list = _state.value.devices.filterNot { it.ip == ip }
        persist(list)
        if (_state.value.currentIp == ip) {
            pollJob?.cancel()
            stopCamera()
            val next = list.firstOrNull()?.ip
            store.setCurrentIp(next)
            _state.update { it.copy(currentIp = next, status = null) }
            if (next != null) startPolling()
        }
        emit("已删除 $ip")
    }

    private fun persist(list: List<Device>) {
        store.saveDevices(list)
        _state.update { it.copy(devices = list) }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (_state.value.scanning) return
        viewModelScope.launch {
            _state.update { it.copy(scanning = true, scanDone = 0) }
            val prefix = Discovery.localPrefix()
            if (prefix == null) {
                _state.update { it.copy(scanning = false) }
                emit("未连上 Wi-Fi，请先连到设备所在局域网")
                return@launch
            }
            val found = withContext(Dispatchers.IO) {
                Discovery.scan(
                    prefix = prefix,
                    onFound = { hit ->
                        val list = _state.value.devices.toMutableList()
                        val index = list.indexOfFirst { it.ip == hit.ip }
                        val device = Device(
                            ip = hit.ip,
                            name = if (index >= 0) list[index].name else DeviceStore.defaultName(hit.ip),
                            lastSeen = System.currentTimeMillis()
                        )
                        if (index >= 0) list[index] = device else list.add(device)
                        persist(list)
                    },
                    onProgress = { done, _ ->
                        _state.update { it.copy(scanDone = done) }
                    }
                )
            }
            _state.update { it.copy(scanning = false) }
            if (found.isEmpty()) {
                emit("$prefix.0/24 网段没找到设备，可手动输入 IP")
            } else {
                emit("发现 ${found.size} 台设备")
                if (_state.value.currentIp == null) selectDevice(found.first().ip, notify = false)
            }
        }
    }

    // ---------------- 状态轮询 ----------------

    private fun startPolling() {
        pollJob?.cancel()
        val ip = _state.value.currentIp ?: return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                val begin = SystemClock.elapsedRealtime()
                val status = withContext(Dispatchers.IO) {
                    runCatching { SparkApi.status(ip, 2000) }.getOrNull()
                }
                val cost = (SystemClock.elapsedRealtime() - begin).toInt()
                if (status != null) {
                    failures = 0
                    _state.update { it.copy(status = status, latencyMs = cost) }
                } else {
                    failures += 1
                    if (failures >= 2) {
                        _state.update { it.copy(status = null, latencyMs = null) }
                    }
                }
                delay(3000)
            }
        }
    }

    fun refreshStatus() {
        val ip = _state.value.currentIp ?: return
        viewModelScope.launch {
            val begin = SystemClock.elapsedRealtime()
            val status = withContext(Dispatchers.IO) {
                runCatching { SparkApi.status(ip, 2000) }.getOrNull()
            }
            if (status != null) {
                _state.update {
                    it.copy(
                        status = status,
                        latencyMs = (SystemClock.elapsedRealtime() - begin).toInt()
                    )
                }
            } else {
                _state.update { it.copy(status = null, latencyMs = null) }
            }
        }
    }

    // ---------------- 运动 ----------------

    fun startHold(x: Float, y: Float) {
        val ip = _state.value.currentIp ?: return
        if (!_state.value.online) {
            emit("设备离线，先检查连接")
            return
        }
        holdJob?.cancel()
        _state.update { it.copy(holding = true, holdVector = x to y) }
        holdJob = viewModelScope.launch {
            while (isActive) {
                val speed = _state.value.speed
                withContext(Dispatchers.IO) {
                    runCatching { SparkApi.move(ip, x * speed, y * speed) }
                }
                delay(200)   // 底盘 500ms 看门狗，200ms 重发留足余量
            }
        }
    }

    fun stopMotion() {
        _state.update { it.copy(holding = false, holdVector = null) }
        holdJob?.cancel()
        holdJob = null
        val ip = _state.value.currentIp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.stop(ip) }
        }
    }

    fun updateSpeed(value: Float) {
        _state.update { it.copy(speed = value) }
        store.setSpeed(value)
    }

    fun dance() {
        val ip = _state.value.currentIp ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.raw(ip, "d1") }
                .onFailure { emit("指令发送失败") }
        }
        emit("开始跳舞，约 17 秒内底盘不响应指令")
    }

    fun setLight(mode: Int) {
        val ip = _state.value.currentIp ?: return
        _state.update { it.copy(lightMode = mode) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.raw(ip, "w$mode") }
                .onFailure { emit("灯光指令发送失败") }
        }
    }

    /**
     * 算出这一帧要做的翻转组合。
     *
     * 期望结果 T = 是否上下翻转；设备实际输出 D = 是否 180°（= 水平+垂直）。
     * 水平和垂直翻转都对合，所以：
     *   水平 = 设备 180°；  垂直 = 设备 180° 与「想要上下翻转」异或。
     * 四种组合（设备开/关 × 想要/不想要）都能被这一组标志覆盖，
     * 既不用改设备状态，也不影响它自己的 LCD 预览和云端拍照。
     */
    private fun flipFlags(): Pair<Boolean, Boolean> {
        val deviceRot = _state.value.status?.rot ?: false
        val wantVertical = _state.value.flipVertical
        return deviceRot to (deviceRot xor wantVertical)
    }

    /** 切换画面的上下翻转（App 侧处理，不动固件）。 */
    fun toggleFlipVertical() {
        val target = !_state.value.flipVertical
        _state.update { it.copy(flipVertical = target) }
        store.setFlipVertical(target)
        restartCamera()
        emit(if (target) "画面已上下翻转" else "已恢复原始画面")
    }

    // ---------------- 图传 / 拍照 ----------------

    fun startCamera() {
        val ip = _state.value.currentIp ?: return
        if (streamer != null) return
        if (!_state.value.online) {
            _state.update { it.copy(cameraError = "设备离线") }
            return
        }
        _state.update { it.copy(cameraOn = true, cameraError = null) }
        fpsMark = SystemClock.elapsedRealtime()
        frameCount = 0
        streamer = MjpegStreamer(
            host = ip,
            onFrame = { bitmap: Bitmap ->
                frameCount += 1
                val now = SystemClock.elapsedRealtime()
                val fps = if (now - fpsMark >= 1000) {
                    val value = frameCount
                    frameCount = 0
                    fpsMark = now
                    value
                } else {
                    _state.value.fps
                }
                _state.update {
                    val (h, v) = flipFlags()
                    val shown = runCatching { ImageOps.transform(bitmap, h, v) }.getOrDefault(bitmap)
                    it.copy(frame = shown.asImageBitmap(), fps = fps, cameraError = null)
                }
            },
            onState = { connected, message ->
                _state.update {
                    it.copy(
                        cameraOn = connected || it.cameraOn,
                        cameraError = if (connected) null else (message ?: it.cameraError)
                    )
                }
            }
        ).also { it.start() }
    }

    fun stopCamera() {
        streamer?.stop()
        streamer = null
        _state.update { it.copy(cameraOn = false, frame = null, fps = 0) }
    }

    fun restartCamera() {
        val wasOn = streamer != null
        stopCamera()
        if (wasOn) viewModelScope.launch {
            delay(300)
            startCamera()
        }
    }

    fun capturePhoto() {
        val ip = _state.value.currentIp ?: return
        if (_state.value.captureBusy) return
        viewModelScope.launch {
            _state.update { it.copy(captureBusy = true) }
            val wasStreaming = streamer != null
            if (wasStreaming) {
                // 8081 只有一个 httpd 任务，图传不停掉 /snapshot 会一直排队
                stopCamera()
                delay(250)
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { SparkApi.snapshot(ip) }.getOrNull()
            }
            if (bytes == null || bytes.size < 1024) {
                emit("拍照失败，检查设备是否在线")
            } else {
                val path = runCatching {
                    withContext(Dispatchers.IO) {
                        val (h, v) = flipFlags()
                        val out = ImageOps.transformJpeg(bytes, h, v) ?: bytes
                        PhotoSaver.saveJpeg(getApplication(), out)
                    }
                }.getOrNull()
                if (path != null) {
                    _state.update { it.copy(lastSaved = path) }
                    emit("已保存到相册：$path")
                } else {
                    emit("写入相册失败")
                }
            }
            if (wasStreaming) {
                delay(150)
                startCamera()
            }
            _state.update { it.copy(captureBusy = false) }
        }
    }

    // ---------------- 官方控制台 WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    fun accountWebView(context: Context): WebView {
        webView?.let { existing ->
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        val view = WebView(context)
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: android.webkit.WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host ?: return false
                return if (host.endsWith("xiaozhi.me") || host.endsWith("tenclass.net")) {
                    false
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
                    }
                    true
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                _state.update { it.copy(webCanGoBack = view.canGoBack()) }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                _state.update { it.copy(webProgress = newProgress) }
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) {
                    _state.update { it.copy(webTitle = title) }
                }
            }
        }
        view.loadUrl(CONSOLE_URL)
        webView = view
        return view
    }

    fun accountGoBack(): Boolean {
        val view = webView ?: return false
        return if (view.canGoBack()) {
            view.goBack()
            true
        } else {
            false
        }
    }

    fun accountReload() {
        webView?.reload()
    }

    fun accountClearLogin() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        webView?.clearCache(true)
        webView?.clearHistory()
        webView?.loadUrl(CONSOLE_URL)
        emit("已清除登录状态")
    }

    fun emit(message: String) {
        _events.tryEmit(message)
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        holdJob?.cancel()
        streamer?.stop()
        webView?.destroy()
        webView = null
    }

    companion object {
        const val CONSOLE_URL = "https://xiaozhi.me/console/agents"
        private val IP_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    }
}
