# 给局域网机器人装一条回家的隧道

**用家里 NAS + frp + Nginx，把私网里的 ESP32 机器人搬到公网 HTTPS 后面。固件零改动。**

> 本文所有域名、IP、端口、凭据均为占位示例（`bot.example.com` / `192.168.1.50` / `<TOKEN>`）。
> 照抄前请全部替换成你自己的值——尤其是那串 `openssl rand` 出来的 token，别用文档里的。

---

## 0. 先说结果

**Before**：手机必须连上和机器人同一个 Wi-Fi 才能控制。出家门，App 上就是一个灰点。

**After**：4G/5G、公司 Wi-Fi、咖啡馆，随便什么网络，打开 App 就能推方向键、看实时画面。

实测数据（跨城，手机在 4G，服务器 2 核 2G，家宽千兆下行）：

| 项目 | 实测值 | 说明 |
|---|---|---|
| `GET /status` | 0.6~2.1s | 含 TLS 握手 + 隧道转发；轮询稳定后偏下限 |
| MJPEG 图传 | 5~6 秒约 300~400 KB | ≈ 480~640 kbps，占家宽上行 1~2% |
| `GET /move`、`/stop` | 与局域网几乎无差别 | 单次请求几百字节，延迟由 RTT 决定 |
| 服务端资源 | 最小规格实例即可 | 只做 TCP 转发，CPU 基本空转 |
| NAS 侧资源 | 一个 10MB 镜像的容器 | 极空间 Z2Pro（RK3568 / 4GB）无压力 |

关键在最后一行：**固件一行没改**。穿墙是外挂能力，不是长在设备里的功能。

---

## 1. 问题的形状

搜「ESP32 远程控制」，你会得到一堆 NAT 穿透教程。但真正卡住人的从来不是「怎么穿透」，而是三个更前置的问题：

**① 穿墙这件事，由谁来做？**

设备自己跑隧道客户端？路由器上跑？还是局域网里找一台常开机器代跑？

**② 鉴权放在哪一层？**

设备固件里的 httpd 没有密码，也没有 TLS。是给固件加密码，还是在它前面挡一层？

**③ 客户端怎么不被「网络位置」污染？**

局域网是 `http://192.168.1.50:8080`，远程是 `https://bot.example.com/bot`。如果这个差异渗进每一处调用，代码很快就没法看了。

这三个问题的答案，决定了方案长什么样。下面逐个交代。

---

## 2. 为什么最后选了 frp

先把候选方案摆开，逐个说明否决理由——比直接给结论有用。

| 方案 | 淘汰原因 |
|---|---|
| 公网 IP + 端口映射 | 家宽绝大多数是 CGNAT，IPv4 公网地址基本申请不到；就算有，把 8080 裸暴露在公网、还带一个无密码的 httpd，等于把车钥匙插在门上 |
| IPv6 直连 + DDNS | 听起来最干净，实际卡在三处：光猫/路由器防火墙默认拦入站、运营商前缀会变、手机侧不保证有 IPv6（尤其公司网络和部分移动网络） |
| WebRTC / P2P 打洞 | 要自建信令 + STUN，对称 NAT 下仍要 TURN 中继——中继服务器等于自己又搭了一个 frp，复杂度翻倍。而且我们的画面是 1.6fps 的 MJPEG，不是需要抗丢包的低延迟视频流，用不上这套 |
| 云厂商 IoT 平台 / 自建 MQTT | 控制指令走 MQTT 很合适，但图传要么 base64 塞消息（膨胀 33%，还会堵住队列），要么上传对象存储再发链接（延迟秒级）。工程量最大，收益最小 |
| 商业内网穿透服务 | 省事，但免费档限速限流量，MJPEG 这种长连接容易被判定异常掐掉；按月付费且受制于人 |
| **frp 自建** | 开源、TCP 层透传不关心上层协议、不限速、社区成熟、一条 TOML 就能跑 |

frp 的 TCP 代理是纯四层转发——它不知道你在传 HTTP 还是 MJPEG，也就不会对长连接做任何「优化」。这正是我们要的：**穿透层不要有主见**。

### 一个被放弃的分支：frp 的 http 代理

frp 本身支持 `type = "http"`，按域名/路径分流，看起来更优雅。没用它的原因很实际：

- 要让 frps 终结 TLS，得给它配证书并开 `vhostHTTPSPort`，证书续期要在 frps 和 Nginx 之间二选一，多一套要维护的东西；
- 服务器上**已经有** Nginx 在跑别的站，443 和证书都是现成的，加两个 `location` 的成本远低于动 frps。

结论：**frp 只管把端口搬到服务器回环上，剩下的 HTTP 语义交给 Nginx。** 这也是下面「代理端口必须绑 127.0.0.1」那条硬规则的由来。

---

## 3. 架构

```
                         ┌─────────────── 公网 ───────────────┐
 手机 (4G/5G)            │                                    │
    │  HTTPS 443         │        云服务器 (2C2G)             │
    └───────────────────>│  Nginx ── Basic Auth + TLS          │
                         │    ├─ /bot/    → 127.0.0.1:18080 ──┐│
                         │    └─ /botcam/ → 127.0.0.1:18081 ──┤│
                         │                                    ││
                         │  frps :7000  ←── 隧道（TLS + token）││
                         └────────────────────────────────────┼┘
                                                              │
                         ┌──────────── 家庭局域网 ────────────┼─┐
                         │                                    │ │
                         │  NAS (Docker: frpc) ───────────────┘ │
                         │    │  转发到 192.168.1.50:8080/8081  │
                         │    ▼                                 │
                         │  ESP32-S3 机器人                     │
                         │    ├─ :8080 控制（响应 <10ms）        │
                         │    └─ :8081 图传（MJPEG / 单帧 JPEG） │
                         └──────────────────────────────────────┘
```

### 三个设计决定

**① 设备不跑 frpc，NAS 跑。**

ESP32-S3 上跑一个 frp 客户端不是不能做（有 lwIP 移植），但代价是：TLS 握手 + 多路复用要吃几十 KB 内存、断线重连逻辑要自己写、以后每次升级穿透逻辑都得重新烧固件，而且**你失去了「穿透层挂了不影响设备本职功能」这条隔离**。

NAS 是 24 小时在线、有 Docker、配置改完重启容器就生效的机器。让它代跑，等于给设备外挂了一个「网络适配器」——设备自己完全不知道自己在公网后面。

**② 控制与图传必须分两个端口。**

ESP-IDF 的 `httpd` 是单任务串行处理的。抓一帧 + 软件 JPEG 编码约 300~500ms，如果推流和控制指令共用一个 httpd 实例，推流期间按方向键就要等半秒——手感上就是「按了没反应」。

拆成 8080（控制）/ 8081（图像）之后，控制端永远不被图像请求阻塞。这个坑在只做局域网时就已经踩过了，远程化只是把两个端口分别搬出去。

顺带一个限制：8081 也是单任务，**同一时刻只能有一个客户端拉流**。App 正在预览时，电脑再连 `/stream` 会一直阻塞。所以「拍照」功能在客户端侧是「停流 → 抓单帧 → 恢复流」，不是并行。

**③ 代理端口只绑回环。**

```toml
proxyBindAddr = "127.0.0.1"
```

这一行是整个方案的安全地基。不写它，frps 默认把 18080/18081 监听在 `0.0.0.0`——任何人扫到端口就能直接开你的车，Nginx 的鉴权形同虚设。**隧道出来的端口必须只对 Nginx 可见。**

---

## 4. 落地

### 4.0 前置：给设备绑静态 IP（不做这步，后面全是白费）

设备的 IP 是 DHCP 分配的，会漂。而 `frpc.toml` 里的 `localIP` 是写死的。一旦漂了，症状是：

> 隧道在线、App 显示服务器连得上、但设备永远离线。

因为隧道本身是通的（NAS ↔ 服务器这一段完好），只是 NAS 到设备这一段指向了空气。这种故障非常难查——所有指示灯都是绿的。

路由器后台 → DHCP 静态分配 / 地址保留 → 按设备 MAC 绑一个固定 IP → 重启设备确认没变。

### 4.1 服务端：frps

```bash
VERSION=0.71.1        # frps 与 frpc 版本保持一致，省得对着日志猜
cd /tmp
wget https://github.com/fatedier/frp/releases/download/v${VERSION}/frp_${VERSION}_linux_amd64.tar.gz
tar -xzf frp_${VERSION}_linux_amd64.tar.gz
install -m 755 frp_${VERSION}_linux_amd64/frps /usr/local/bin/frps

mkdir -p /etc/frp
openssl rand -hex 24      # 生成 token，frpc 与 frps 必须完全一致
```

`/etc/frp/frps.toml`：

```toml
bindPort = 7000

auth.method = "token"
auth.token = "<TOKEN>"

# 地基：代理端口只绑回环，公网不可达
proxyBindAddr = "127.0.0.1"

# 管理面板也只给本机看
webServer.addr = "127.0.0.1"
webServer.port = 7500
webServer.user = "admin"
webServer.password = "<TOKEN>"

# 减少重复握手，MJPEG 长连接场景下有用
transport.maxPoolCount = 5

log.to = "console"
log.level = "info"
log.maxDays = 7
```

systemd `/etc/systemd/system/frps.service`：

```ini
[Unit]
Description=frp server
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/frps -c /etc/frp/frps.toml
Restart=always
RestartSec=5
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload && systemctl enable --now frps
systemctl status frps
```

**安全组只放行 7000。** 18080/18081 绑的是回环，本来就出不去，别「顺手」放行——那等于绕过 Nginx 和鉴权。

### 4.2 云服务器：Nginx 套上 TLS 与鉴权

```bash
apt install -y apache2-utils
htpasswd -c /etc/nginx/.htpasswd botuser     # 按提示设密码，这个密码要填进 App

# 关键：属组必须是 nginx worker 能读的
chown root:www-data /etc/nginx/.htpasswd
chmod 640 /etc/nginx/.htpasswd
```

追加到已有 `server` 块（复用现成的 443 和证书）：

```nginx
# ---------- 控制 ----------
location /bot/ {
    auth_basic "Robot";
    auth_basic_user_file /etc/nginx/.htpasswd;

    # 尾斜杠会剥掉 /bot/ 前缀，对上游根路径
    proxy_pass http://127.0.0.1:18080/;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Connection "";

    # 控制指令要快，慢就是错，别让用户以为没按上
    proxy_connect_timeout 5s;
    proxy_read_timeout 15s;
}

# ---------- 图传 ----------
location /botcam/ {
    auth_basic "Robot";
    auth_basic_user_file /etc/nginx/.htpasswd;

    proxy_pass http://127.0.0.1:18081/;

    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header Connection "";

    # 这三行是 MJPEG 的关键，见「踩坑合集」
    proxy_buffering off;
    proxy_cache off;
    chunked_transfer_encoding on;

    # 长连接，不放大就是把流掐在 60s
    proxy_connect_timeout 5s;
    proxy_read_timeout 3600s;
    proxy_send_timeout 3600s;
}
```

```bash
nginx -t && systemctl reload nginx
```

此时隧道还没建立，所以：

```bash
curl -i https://bot.example.com/bot/status
# 期望 401 —— 没带凭据，鉴权生效
curl -i -u botuser:密码 https://bot.example.com/bot/status
# 期望 502 —— 鉴权通过，但没有客户端接入，正常
```

**先看到 401 再看到 502，才能说明鉴权层是对的。** 顺序反了（直接 502）说明 `auth_basic` 没生效。

### 4.3 NAS：Docker 跑 frpc

以极空间 Z2Pro 为例（其他品牌 NAS 同理，只是界面不同）：

| 坑 | 后果 |
|---|---|
| 文件名变成 `frpc.toml.txt` | 容器启动即退，日志只有 `config file not found`（Windows 隐藏已知扩展名导致） |
| 镜像没加国内源前缀 | 卡在 `Client.Timeout exceeded while awaiting headers` |
| 镜像只有 amd64 层 | 启动报 `exec format error`（ARM64 NAS 必须用带 arm64 的镜像） |
| 改完 `frpc.toml` 没重启容器 | 配置不热加载，改了半天不生效 |

`frpc.toml`：

```toml
serverAddr = "bot.example.com"
serverPort = 7000

auth.method = "token"
auth.token = "<TOKEN>"          # 与 frps 完全一致

transport.tls.enable = true

# 服务器暂时连不上时保持重试，而不是退出让 Docker 反复重启
loginFailExit = false

[[proxies]]
name = "robot-ctrl"
type = "tcp"
localIP = "192.168.1.50"        # 必须配合静态 IP 绑定
localPort = 8080
remotePort = 18080

[[proxies]]
name = "robot-cam"
type = "tcp"
localIP = "192.168.1.50"
localPort = 8081
remotePort = 18081
```

Compose（极空间「项目 → 新增项目」，**目录里不要预放 `docker-compose.yml`**，它自己会写一份，同名会报「目标路径下已有同名 yml 或 env 文件」）：

```yaml
services:
  frpc:
    image: docker.1panel.live/fatedier/frpc:v0.71.1
    container_name: frpc
    restart: unless-stopped
    volumes:
      - /path/to/your/share/Docker/frpc:/etc/frp:rw
```

> 极空间的共享目录在容器里要用**内部真实路径**（形如 `/tmp/zfsv3/sata1/<账号>/data/Docker/frpc`），
> 填 NAS 界面上的「我的文件/…」路径挂不出来。

启动成功的标志，日志里两行：

```
[start proxy success]  name: robot-ctrl
[start proxy success]  name: robot-cam
```

### 4.4 验证阶梯（按顺序，别跳步）

每步只验证一段链路，失败点立刻收敛：

```bash
# 1. 设备本身活在局域网上（在 NAS 所在网络里测）
curl http://192.168.1.50:8080/status

# 2. 隧道通（任意网络）
curl -u botuser:密码 https://bot.example.com/bot/status

# 3. 控制指令
curl -u botuser:密码 "https://bot.example.com/bot/move?x=0&y=1"
curl -u botuser:密码  https://bot.example.com/bot/stop

# 4. 图传（存下来用看图软件打开，别只看 HTTP 200）
curl -u botuser:密码 -o /tmp/shot.jpg https://bot.example.com/botcam/snapshot
file /tmp/shot.jpg      # 期望 JPEG image data, 640x480
```

第 4 步最容易自欺：返回 200 不代表字节流是完整 JPEG，一定要落到文件里打开看。

### 4.5 客户端：把「网络位置」收进一个类型

到这里链路已经通了。真正的问题是 App 里怎么接——这也是整个改造里唯一有设计含量的部分。

原始代码里，URL 是这么拼的：

```kotlin
// 到处都是这种字符串
"http://$host:8080$path"
```

如果为了远程再加一堆 `if (isRemote) "https://$server/bot$path" else ...`，逻辑会渗进每一个 API 方法和每一个页面。改成用一个密封接口表达「访问入口」：

```kotlin
sealed interface Endpoint {

    /** 局域网直连：设备自己的 httpd，无鉴权 */
    data class Lan(val ip: String) : Endpoint

    /** 远程：NAS 隧道 + 服务器 Nginx，带 Basic 鉴权 */
    data class Remote(val host: String, val user: String, val pass: String) : Endpoint

    fun ctrlUrl(path: String): String = when (this) {
        is Lan    -> "http://$ip:$CTRL_PORT$path"
        is Remote -> "https://$host/bot$path"
    }

    fun camUrl(path: String): String = when (this) {
        is Lan    -> "http://$ip:$CAM_PORT$path"
        is Remote -> "https://$host/botcam$path"
    }

    /** 只有远程需要 Basic 头；局域网设备本来就没有鉴权 */
    val authorization: String? get() = when (this) {
        is Lan -> null
        is Remote -> if (user.isEmpty() && pass.isEmpty()) null
        else "Basic " + Base64.encodeToString(
            "$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
    }

    /**
     * 远程多经两跳（隧道 + Nginx），TLS 握手也比明文慢，
     * 超时按系数放宽，否则会出现「连得上但总超时」。
     */
    fun timeout(baseMs: Int): Int = if (this is Remote) (baseMs * 2.5f).toInt() else baseMs
}
```

调用侧（`status` / `move` / `stop` / `snapshot` / MJPEG 读流）只接收 `Endpoint`，所有 URL 拼接和鉴权头都收在 `connect()` 一处：

```kotlin
private fun connect(endpoint: Endpoint, path: String, timeoutMs: Int, cam: Boolean = false) =
    (URL(if (cam) endpoint.camUrl(path) else endpoint.ctrlUrl(path))
        .openConnection() as HttpURLConnection).apply {
        val t = endpoint.timeout(timeoutMs)
        connectTimeout = t
        readTimeout = t
        requestMethod = "GET"
        useCaches = false
        setRequestProperty("Connection", "close")
        endpoint.authorization?.let { setRequestProperty("Authorization", it) }
    }
```

ViewModel 和四个页面**一行都不用改**——它们调的是 `SparkApi.snapshot(endpoint)`、`SparkApi.move(endpoint, x, y)` 这类方法，不关心背后是 Wi-Fi 还是 4G。

**这就是这套方案真正值得分享的地方：网络位置变成了 Endpoint 的一个属性，而不是散落在各处的条件判断。**

还有两处小细节，都是踩过才知道要加的：

**错误码得说人话。** 远程模式下 401 九成是账号密码填错，但用户看到「HTTP 401」只会去怀疑隧道、怀疑服务器、怀疑 NAS：

```kotlin
private fun describe(code: Int) = when (code) {
    401, 403 -> "HTTP $code：远程账号或密码不对"
    else -> "HTTP $code"
}
```

**切模式前先停掉正在跑的东西。** 图传是长连接、方向键是按住持续发指令。切换连接方式时必须先咔嚓断掉，否则旧连接会挂在新 endpoint 上产生一串难解释的超时：

```kotlin
fun setMode(mode: LinkMode) {
    if (state.mode == mode) return
    stopCamera()          // 先断流
    stopMotion()          // 先停车
    store.setMode(mode)
    startPolling()
}
```

UI 上就是一个两栏切换：「局域网 · 同一 Wi-Fi」/「远程 · 任何网络」。远程模式下多出一张卡片填服务器地址、账号、密码，填完立刻重连，不用用户再去点刷新。

---

## 5. 踩坑合集

这一节是本文最有价值的部分。每个坑都真实踩过，附带判别方法——**判别方法比结论重要**，因为你的环境一定和我不一样。

### 云网络层

| 现象 | 根因 | 判别 / 处理 |
|---|---|---|
| 安全组「明明放行了」端口就是不通 | 规则加到了**没绑这台实例**的安全组上。控制台的安全组列表是该地域全部安全组，很容易改错 | 挑一条同组内**系统创建**的规则（比如 8000）从外网测；如果连它也不通而 80/443 通，说明你改的组没生效。去「实例 → 选中实例 → 安全组」页签看真正绑的是哪个 |
| 同上，进一步确认 | — | `timeout 6 bash -c "cat < /dev/null > /dev/tcp/<公网IP>/7000" && echo 通 \|\| echo 不通`。从服务器连自己的公网 IP，若 ufw 已关、iptables 全 ACCEPT 仍不通，则一定是云上那层拦的 |

### 反向代理层

| 现象 | 根因 | 判别 / 处理 |
|---|---|---|
| 无凭据访问 401（正常），带**正确**凭据却是 500 | `/etc/nginx/.htpasswd` 是 `640 root:root`，而 worker 跑在 `www-data`，打不开文件 | `error.log` 只有一行 `[crit] open() "...htpasswd" failed (13: Permission denied)`。`chown root:www-data` + `640` |
| 画面一卡一卡，延迟越滚越大，像固件挂了 | Nginx 缓冲了 MJPEG，凑够一块才发 | 确认 `proxy_buffering off` / `proxy_cache off` / `chunked_transfer_encoding on` 三行都在 |
| 看了约 60 秒流自动断 | 默认 `proxy_read_timeout 60s` | 图传 location 放大到 3600s |
| `/bot/status` 请求打到了上游的错误路径 | `proxy_pass` 少了尾斜杠，`/bot` 前缀被原样传下去 | `proxy_pass http://127.0.0.1:18080/;`（结尾必须有 `/`） |

### 容器层

| 现象 | 根因 | 判别 / 处理 |
|---|---|---|
| 容器状态「运行中」，但隧道永远不通、日志无报错 | 某些社区镜像（如 `snowdreamtech/frpc`）的 entrypoint 是包装脚本，默认 `KEEPALIVE=1` 时执行 `tail -f /dev/null & wait`，**后面启动 frpc 的 exec 永远走不到** | 这个坑最阴——状态是绿的。必须三件事一起做：覆盖 `entrypoint: ["/usr/bin/frpc"]`、`command` 用**数组**形式、显式 `KEEPALIVE=0` |
| 覆盖 entrypoint 后仍报 `gosu: -c: no such file or directory` | 包装脚本最后是 `exec gosu 0:0 "$@"`，会把 `command` 当成一个完整命令名 | `command: ["-c", "/etc/frp/frpc.toml"]`，不要写成 `command: -c /etc/frp/frpc.toml` |
| 拉镜像 `Client.Timeout exceeded` | 国内直连 `registry-1.docker.io` 不通 | 加镜像源前缀：`docker.1panel.live/fatedier/frpc:v0.71.1`。备选 `docker.1ms.run`、`hub.rat.dev`、`docker.m.daocloud.io` |
| 镜像仓库里搜不到 frpc | **NAS 的「仓库搜索」走的是 `hub.docker.com` API**，和拉取是同一家在境内的可用性，一样超时 | 别在界面里搜，直接手动输入带前缀的完整镜像名 |
| 项目创建失败：「目标路径下已有同名 yml」 | 极空间建 Compose 项目时会自己往选定路径写 compose 文件 | 项目目录里只放 `frpc.toml`，compose 内容在界面编辑器里粘 |
| `Top-level object must be a mapping` | 把 `frpc.toml` 的内容粘进了 compose 编辑器。YAML 把 `serverAddr = "..."` 当普通字符串，顶层就不是 mapping 了 | compose 必须以 `services:` 开头 |

### 运行时层

| 现象 | 根因 | 判别 / 处理 |
|---|---|---|
| 隧道在线、服务器可达，设备一直离线 | 设备 DHCP 换了 IP，`localIP` 指向空气 | 回路由器绑静态 IP。这类故障所有指示灯都是绿的，唯一可靠办法是先在 NAS 容器里 `wget -qO- http://192.168.1.50:8080/status` |
| 容器反复重启 | token 两边不一致，或 7000 没放行 | 看日志第一条报错；比对 `frpc`/`frps` 的 token |
| 能控制不能看图 | 8081 的 httpd 是单任务，另一个客户端正占着流 | 关掉其他正在看图的客户端 |
| 图传只有 1~2 fps，怀疑网络慢 | **不是网络**。瓶颈在固件：GC2145 抓帧 + 软件 JPEG 编码约 500~600ms/帧 | 见下一节 |

### NAS 没有 SSH 怎么排障

不少消费级 NAS 不开放宿主机 SSH，但**Docker 容器内部可以进终端**（容器详情 → 终端 → `/bin/sh`），这足够定位绝大多数问题：

```sh
cat /etc/frp/frpc.toml                    # 挂载进没进来（最常见失败原因）
nslookup bot.example.com                  # 容器 DNS
wget -qO- http://192.168.1.50:8080/status # NAS → 设备这一段
nc -z -w 3 bot.example.com 7000 && echo "7000 通"   # 非 HTTP 协议只能用 nc 探
```

四条里哪条失败，问题就锁在哪一段——这比反复重启容器高效得多。

---

## 6. 性能：瓶颈到底在哪

远程化做完之后，第一个冒出来的疑问通常是「是不是隧道拖慢了图传」。

**不是。** 实测拆开看：

| 环节 | 实测 | 结论 |
|---|---|---|
| 设备端出帧 | 1.6 fps，单帧约 28 KB | 抓帧 + 编码 500~600ms/帧，这是**唯一的瓶颈** |
| 对应码率 | ≈ 360 kbps | 千兆下行 / 30~50Mbps 上行的家宽，占用 1% 量级 |
| 隧道转发 | 不构成限制 | frps 只做四层转发，CPU 空转 |
| Nginx | 不构成限制 | 只加 TLS 与鉴权，`buffering off` 后是直通 |

想真的提帧率，只能改固件：降到 320×240 + JPEG quality 60，能到 4~6 fps，带宽还会跟着降。这件事和「能不能远程看」是两件独立的事——**先把通路打通，再谈优化**，否则你会在一个 4fps 的画面上花三天，却没发现隧道压根没通。

同理，`/status` 的 0.6~2.1s 主要是 TLS 握手（每次新建连接）与跨城 RTT，不是隧道吞吐问题。客户端如果做轮询，复用连接或在握手后保持 keep-alive 能明显压低上限。

---

## 7. 安全边界（别跳过）

这套方案的安全模型非常朴素，必须说清楚：

**唯一的门是 Nginx 那一层 Basic Auth。** 设备固件没有密码，隧道是纯转发。**密码泄露 = 车能被别人开走。**

三条硬规则：

1. **`proxyBindAddr = "127.0.0.1"` 不许改。** 改成 `0.0.0.0` 等于把 18080 直接铺在公网，跳过 TLS 和鉴权。这是最容易为了「调试方便」而破的规矩。
2. **安全组只放 7000。** 18080/18081 就不该有公网可达性。
3. **密码别用弱口令，也别和别的服务复用。** frp 的 token 和 htpasswd 的密码是两回事，都要独立随机。

**Basic Auth 的已知局限**（用之前要想清楚）：

- 凭据是 base64 不是加密，**必须走 HTTPS**，明文 HTTP 下等于公开密码；
- 浏览器/WebView 里的 `<img src="...">` 带不上 `Authorization` 头，也没法在 URL 里塞 `user:pass@`（现代浏览器已禁用）。所以图传要么由客户端自己读字节流再解码（本项目的做法），要么改用 cookie/token 方案；
- 没有会话、没有失效机制，改密码要同时改服务端和所有客户端；
- App 里存的是明文（SharedPreferences）。自己用够了，**要给别人用得换 token**：服务端签发短期 JWT，客户端带 `Authorization: Bearer`，服务端做校验与吊销。这一层加上去之后，Basic Auth 就可以退场了。

---

## 8. 还能往哪走

这套骨架的可扩展性比看起来好：

| 方向 | 怎么做 |
|---|---|
| 多台机器人 | 每台加一组 `[[proxies]]`，`remotePort` 递增；或改用 frp 的 `type = "http"` 按子域名分流 |
| 合并成一个域名 | frp 的 http 代理支持 `locations`，`/a/bot/` → 机器人 A，`/b/bot/` → 机器人 B，Nginx 只留一个 location |
| 换掉 Basic Auth | 服务端签发短期 token，客户端 `Bearer`；Nginx 用 `auth_request` 子请求校验，或直接在 App 前置一层网关 |
| 更好的穿透 | WireGuard / Tailscale 也是同一类解法，优点是虚拟网段、不用逐端口映射；缺点是手机侧要装 VPN 配置、与「一个 App 开箱即用」的体验冲突。frp 更适合**面向终端用户交付**的场景 |
| 降低移动端功耗 | 轮询改长连接（SSE / WS），用 frp 的 TCP 隧道直接透传，无需额外改造 |

如果要给这套方案一句话总结：**穿透层、鉴权层、业务层各自独立，任何一层换实现都不影响另外两层。** 设备不知道自己在公网后面，App 不知道自己在走隧道，Nginx 不知道隧道里跑的是 MJPEG。

---

## 附：文件清单

| 文件 | 作用 |
|---|---|
| `frps.toml` | 服务端配置，注意 `proxyBindAddr` |
| `frpc.toml` | 客户端配置，注意 `localIP` 与静态 IP 绑定 |
| `nginx-robot.conf` | 两个 location + 鉴权 + MJPEG 三件套 |
| `docker-compose.yml` | NAS 侧容器定义 |
| `Endpoint.kt` | 客户端网络位置抽象 |

**部署顺序**：静态 IP → frps → Nginx（先验 401 再验 502）→ NAS frpc（等两行 `start proxy success`）→ 四步验证 → 客户端接入。

一次做完大概半小时，其中二十分钟会花在找「为什么端口不通」上——希望上面那张坑表能帮你省掉这二十分钟。
