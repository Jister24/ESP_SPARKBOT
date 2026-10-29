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
import com.shike.sparkbot.media.PhotoSaver
import com.shike.sparkbot.net.Discovery
import com.shike.sparkbot.net.Endpoint
import com.shike.sparkbot.net.LinkMode
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
    val rotBusy: Boolean = false,
    val mode: LinkMode = LinkMode.LAN,
    val remoteHost: String = DeviceStore.DEFAULT_REMOTE_HOST,
    val remoteUser: String = DeviceStore.DEFAULT_REMOTE_USER,
    val remotePass: String = DeviceStore.DEFAULT_REMOTE_PASS
) {
    val online: Boolean get() = status != null
    val current: Device? get() = devices.firstOrNull { it.ip == currentIp }

    /** 远程模式下没有「当前设备」，只要服务器地址填了就能连。 */
    val hasTarget: Boolean
        get() = when (mode) {
            LinkMode.LAN -> currentIp != null
            LinkMode.REMOTE -> remoteHost.isNotBlank()
        }

    /** 状态栏/标题里显示的连接目标。 */
    val targetLabel: String
        get() = when (mode) {
            LinkMode.LAN -> currentIp ?: ""
            LinkMode.REMOTE -> remoteHost
        }

    /** 设备端是否开启 180° 转正（固件持久化在 NVS，App 只读）。 */
    val deviceRot: Boolean get() = status?.rot ?: true
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = DeviceStore(app)
    private val _state = MutableStateFlow(
        UiState(
            devices = store.devices(),
            currentIp = store.currentIp(),
            speed = store.speed(),
            mode = store.mode(),
            remoteHost = store.remoteHost(),
            remoteUser = store.remoteUser(),
            remotePass = store.remotePass()
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
        if (_state.value.mode == LinkMode.REMOTE) {
            startPolling()
        } else if (_state.value.currentIp == null) {
            _state.value.devices.firstOrNull()?.let { selectDevice(it.ip, notify = false) }
        } else {
            startPolling()
        }
        if (_state.value.devices.isEmpty() && _state.value.mode == LinkMode.LAN) {
            viewModelScope.launch { scan() }
        }
    }

    // ---------------- 连接方式 ----------------

    /** 当前应该连哪里；没配好目标时返回 null，调用方直接放弃这一轮。 */
    private fun endpoint(): Endpoint? = when (_state.value.mode) {
        LinkMode.LAN -> _state.value.currentIp?.let { Endpoint.Lan(it) }
        LinkMode.REMOTE -> _state.value.remoteHost
            .takeIf { it.isNotBlank() }
            ?.let { Endpoint.Remote(it, _state.value.remoteUser, _state.value.remotePass) }
    }

    fun setMode(mode: LinkMode) {
        if (_state.value.mode == mode) return
        stopCamera()
        stopMotion()
        store.setMode(mode)
        _state.update { it.copy(mode = mode, status = null, latencyMs = null) }
        if (mode == LinkMode.REMOTE) {
            startPolling()
            emit("已切换到远程访问：${_state.value.remoteHost}")
        } else {
            startPolling()
            emit("已切换到局域网直连")
        }
    }

    /** 远程服务器地址与账号；填完立刻重连一次，省得用户再去手动刷新。 */
    fun updateRemote(host: String, user: String, pass: String) {
        store.setRemote(host, user, pass)
        _state.update {
            it.copy(
                remoteHost = host.trim(),
                remoteUser = user.trim(),
                remotePass = pass.trim(),
                status = null,
                latencyMs = null
            )
        }
        if (_state.value.mode == LinkMode.REMOTE) {
            stopCamera()
            startPolling()
            refreshStatus()
        }
    }

    // ---------------- 设备 ----------------

    fun selectDevice(ip: String, notify: Boolean = true) {
        if (_state.value.currentIp == ip &&
            _state.value.mode == LinkMode.LAN &&
            pollJob != null
        ) return
        stopCamera()
        stopMotion()
        store.setCurrentIp(ip)
        // 选设备意味着肯定在局域网里，顺手把模式切回来，省掉用户再点一次
        store.setMode(LinkMode.LAN)
        _state.update {
            it.copy(currentIp = ip, mode = LinkMode.LAN, status = null, latencyMs = null)
        }
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
                runCatching { SparkApi.status(Endpoint.Lan(ip), 2000) }.getOrNull()
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
        if (_state.value.mode == LinkMode.REMOTE) {
            emit("远程模式不用扫描，切回局域网再扫")
            return
        }
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
        if (endpoint() == null) return
        pollJob = viewModelScope.launch {
            var failures = 0
            while (isActive) {
                val ep = endpoint() ?: break
                val begin = SystemClock.elapsedRealtime()
                val status = withContext(Dispatchers.IO) {
                    runCatching { SparkApi.status(ep, STATUS_TIMEOUT_MS) }.getOrNull()
                }
                val cost = (SystemClock.elapsedRealtime() - begin).toInt()
                if (status != null) {
                    failures = 0
                    _state.update { it.copy(status = status, latencyMs = cost) }
                } else {
                    // 设备忙（图传编码 / 停流后刷屏）时 /status 要 1~2 秒才回，
                    // 连着失败 3 次才算离线，否则方向键会莫名其妙变灰。
                    // 远程多两跳，再宽一次。
                    failures += 1
                    if (failures >= 3) {
                        _state.update { it.copy(status = null, latencyMs = null) }
                    }
                }
                delay(3000)
            }
        }
    }

    fun refreshStatus() {
        if (endpoint() == null) return
        viewModelScope.launch {
            val ep = endpoint() ?: return@launch
            val begin = SystemClock.elapsedRealtime()
            val status = withContext(Dispatchers.IO) {
                runCatching { SparkApi.status(ep, STATUS_TIMEOUT_MS) }.getOrNull()
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
        if (endpoint() == null) return
        holdJob?.cancel()
        _state.update { it.copy(holding = true, holdVector = x to y) }
        holdJob = viewModelScope.launch {
            var failures = 0
            var warned = false
            while (isActive) {
                val ep = endpoint() ?: break
                val speed = _state.value.speed
                val ok = withContext(Dispatchers.IO) {
                    runCatching { SparkApi.move(ep, x * speed, y * speed) }.isSuccess
                }
                if (ok) {
                    failures = 0
                } else {
                    // 底盘 500ms 没收到合法指令就自停，这里漏几包不影响安全，
                    // 只是连续失败时提醒一次，避免一路静默。
                    failures += 1
                    if (failures >= 3 && !warned) {
                        warned = true
                        emit("指令没送到设备，检查连接")
                    }
                }
                delay(200)   // 底盘 500ms 看门狗，200ms 重发留足余量
            }
        }
    }

    fun stopMotion() {
        _state.update { it.copy(holding = false, holdVector = null) }
        holdJob?.cancel()
        holdJob = null
        val ep = endpoint() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.stop(ep) }
        }
    }

    fun updateSpeed(value: Float) {
        _state.update { it.copy(speed = value) }
        store.setSpeed(value)
    }

    fun dance() {
        val ep = endpoint() ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.raw(ep, "d1") }
                .onFailure { emit("指令发送失败") }
        }
        emit("开始跳舞，约 17 秒内底盘不响应指令")
    }

    fun setLight(mode: Int) {
        val ep = endpoint() ?: return
        _state.update { it.copy(lightMode = mode) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { SparkApi.raw(ep, "w$mode") }
                .onFailure { emit("灯光指令发送失败") }
        }
    }

    /**
     * 切换画面方向 —— 直接下发到设备，App 不做任何图像处理。
     *
     * 固件在 Capture() 里每帧转 180°，所以改完下一帧就生效，不用重连图传；
     * LCD 预览、云端拍照、App 图传共用同一份数据，方向天然一致。
     * 开关状态存在设备 NVS（rot180），换手机/重装 App 也不会丢。
     */
    fun toggleDeviceRotation() {
        val ep = endpoint() ?: return
        if (!_state.value.online) {
            emit("设备离线，先检查连接")
            return
        }
        if (_state.value.rotBusy) return
        val target = !_state.value.deviceRot
        _state.update { it.copy(rotBusy = true) }
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching { SparkApi.setRotation(ep, target) }.getOrNull()
            }
            if (reply == null) {
                _state.update { it.copy(rotBusy = false) }
                emit("切换失败，检查设备连接")
                return@launch
            }
            // 固件回 {"rot":true/false}，以设备实际状态为准
            val actual = runCatching {
                org.json.JSONObject(reply).optBoolean("rot", target)
            }.getOrDefault(target)
            _state.update { st ->
                st.copy(rotBusy = false, status = st.status?.copy(rot = actual))
            }
            emit(if (actual) "设备画面已 180° 转正" else "设备画面已恢复原始方向")
        }
    }

    // ---------------- 图传 / 拍照 ----------------

    fun startCamera() {
        val ep = endpoint() ?: return
        if (streamer != null) return
        // 不拿 /status 当门槛：设备忙时状态查询会超时，但图传本身是好的，
        // 连不上由 MjpegStreamer 自己重试并报错。
        _state.update { it.copy(cameraOn = true, cameraError = null) }
        fpsMark = SystemClock.elapsedRealtime()
        frameCount = 0
        streamer = MjpegStreamer(
            endpoint = ep,
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
                    it.copy(frame = bitmap.asImageBitmap(), fps = fps, cameraError = null)
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
        val ep = endpoint() ?: return
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
                runCatching { SparkApi.snapshot(ep) }.getOrNull()
            }
            if (bytes == null || bytes.size < 1024) {
                emit("拍照失败，检查设备是否在线")
            } else {
                val path = runCatching {
                    withContext(Dispatchers.IO) {
                        // 方向已由设备端转好，照片原样存盘，不再二次编码
                        PhotoSaver.saveJpeg(getApplication(), bytes)
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
            // 控制台自己声明了 width=device-width 的 viewport，按它的来即可。
            // 开 overviewMode 会让 WebView 先按 980px 算初始缩放再回填重排，多一次跳变。
            loadWithOverviewMode = false
            useWideViewPort = true
            // 系统字体放大时 WebView 会跟着 textZoom，表单里的固定宽度输入框被撑破
            textZoom = 100
            // 页面本身是 user-scalable=no，禁掉缩放避免双击/双指缩放的残影
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
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
        installVhFix(view)
        return view
    }

    /**
     * 修小智控制台弹窗在 WebView 里塌成一条缝的问题。
     *
     * 根因不在页面，而在这个 WebView 内核：`100vh` / `100dvh` / `52vh` 全部算成 0，
     * 而 innerHeight 明明是 710（用 `height:400px` 做对照量得 400，说明只有视口单位是坏的）。
     * 控制台的配置弹窗恰好用 `supports-[height:100dvh]:h-[100dvh]` 和
     * `h-[min(46rem,calc(100vh-2rem))]` 定高，于是高度归零，标题、标签、取消保存
     * 全部溢出叠在一起。
     *
     * 内核改不了，只能在页面加载后把样式表里的 vh 单位就地改写成实测像素。
     * 原始值缓存起来，旋屏、键盘弹出时按新的视口高度重算。
     */
    private fun installVhFix(view: WebView) {
        val js = """(function(){
          var RULE=/([\d.]+)\s*(dvh|svh|lvh|vh)(?![\w-])/;
          var ALL=/([\d.]+)\s*(dvh|svh|lvh|vh)(?![\w-])/g;
          var cache=window.__wbVhCache||(window.__wbVhCache=new WeakMap());
          function fixAll(){
            var H=Math.round((window.visualViewport&&visualViewport.height)||innerHeight||0);
            if(!H) return 0;
            var n=0;
            function apply(rule,p,orig){
              var nv=orig.replace(ALL,function(_,num){
                return (parseFloat(num)/100*H).toFixed(2)+'px';
              });
              try{
                rule.style.setProperty(p,nv,rule.style.getPropertyPriority(p));
                n++;
              }catch(e){}
            }
            function walk(rules){
              for(var i=0;i<rules.length;i++){
                var r=rules[i];
                if(r.cssRules&&r.cssRules.length){walk(r.cssRules);continue;}
                var st=r.style; if(!st) continue;
                var orig=cache.get(r);
                if(orig){
                  for(var p in orig) apply(r,p,orig[p]);
                  continue;
                }
                var props=[];
                for(var k=0;k<st.length;k++) props.push(st[k]);
                var found=null;
                for(var k=0;k<props.length;k++){
                  var p=props[k], v=st.getPropertyValue(p);
                  if(!v||!RULE.test(v)) continue;
                  if(!found) found={};
                  found[p]=v;
                }
                if(found){
                  cache.set(r,found);
                  for(var p in found) apply(r,p,found[p]);
                }
              }
            }
            for(var s=0;s<document.styleSheets.length;s++){
              var rs=null;
              try{rs=document.styleSheets[s].cssRules}catch(e){continue}
              if(rs) walk(rs);
            }
            return n;
          }
          window.__wbVh=fixAll;
          var changed=fixAll();
          try{
            addEventListener('resize',function(){setTimeout(fixAll,150)});
            addEventListener('orientationchange',function(){setTimeout(fixAll,350)});
            // 控制台是 SPA，切路由可能再插 <style>，补一次
            new MutationObserver(function(){
              clearTimeout(window.__wbVhT);
              window.__wbVhT=setTimeout(fixAll,300);
            }).observe(document.head,{childList:true,subtree:true});
          }catch(e){}
          return changed;
        })()"""
        var n = 0
        val task = object : Runnable {
            override fun run() {
                n++
                if (n > 6) return
                view.evaluateJavascript(js) { r ->
                    android.util.Log.i("SPARKVH", "pass#$n changed=$r")
                }
                view.postDelayed(this, if (n <= 3) 700L else 2500L)
            }
        }
        view.postDelayed(task, 600)
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

        /** 设备空闲时 /status 只要几十毫秒，忙起来（图传编码、停流刷屏）能到 2 秒。 */
        private const val STATUS_TIMEOUT_MS = 5000

        private val IP_REGEX = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
    }
}
