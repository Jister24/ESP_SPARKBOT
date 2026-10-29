# 远程访问部署手册

把「只有同一局域网能用」升级成「手机在任何网络下都能遥控 + 看图」。

固件**零改动**——NAS 上的 frp 客户端替设备完成穿墙，服务器侧统一收口。

```
手机 ──HTTPS──> bot.example.com ──> Nginx(鉴权/TLS) ──> frps:18080 ──隧道──> NAS frpc ──> 设备:8080
                                        └──────────> frps:18081 ──隧道──> NAS frpc ──> 设备:8081
```

## 前置条件（必须先做，否则后面全是白费功夫）

**给设备绑定静态 IP。** 设备是 DHCP，目前 `192.168.1.50`，会漂移。NAS 里的
`frpc.toml` 写死了这个地址，一旦设备换 IP，隧道会指向空气——症状是「隧道正常、App 显示
服务器在线、但设备一直离线」，非常难查。

路由器后台 → DHCP 静态分配 → MAC `aa:bb:cc:dd:ee:ff` → 固定成 `192.168.1.50`。

顺手确认设备 IP 没变过：浏览器打开 `http://192.168.1.50:8080/status` 能出 JSON 就说明地址对。

---

## 第一步：阿里云装 frps

```bash
# 用 root 或 sudo
VERSION=0.71.1        # 与 NAS 上的 frpc 版本保持一致
cd /tmp
wget https://github.com/fatedier/frp/releases/download/v${VERSION}/frp_${VERSION}_linux_amd64.tar.gz
tar -xzf frp_${VERSION}_linux_amd64.tar.gz
install -m 755 frp_${VERSION}_linux_amd64/frps /usr/local/bin/frps

mkdir -p /etc/frp
# 把 remote/frps.toml 传上来，改掉两处 REPLACE_WITH_RANDOM_TOKEN
openssl rand -hex 24          # 生成 token，两边的 frpc/frps 必须一致
cp frps.toml /etc/frp/frps.toml
```

systemd 单元 `/etc/systemd/system/frps.service`：

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
systemctl daemon-reload
systemctl enable --now frps
systemctl status frps
```

**阿里云安全组只放行 7000。** 18080/18081 绑的是 `127.0.0.1`，本来就不对外，
不要手贱去放行——放行了等于绕过 Nginx 和鉴权，直接裸奔。

## 第二步：Nginx 加鉴权和反向代理

```bash
# 只有 2 核 2G，装轻量包就够
apt install -y apache2-utils
htpasswd -c /etc/nginx/.sparkbot sparkbot    # 按提示设密码，这个密码要填进 App
```

把 `remote/nginx-sparkbot.conf` 里的两个 `location` 追加到 bot.example.com 现有 server 块中，
然后：

```bash
nginx -t && systemctl reload nginx
```

先不接隧道验证鉴权本身是通的（此时会 502，正常）：

```bash
curl -i https://bot.example.com/bot/status
# 期望 401
curl -i -u sparkbot:密码 https://bot.example.com/bot/status
# 期望 502（frps 还没有客户端接入）
```

## 第三步：极空间 Z2Pro 跑 frpc

Z2Pro 是 RK3568（ARM64），极空间官方支持 Docker 但**默认不开放 SSH**，所以只能走
Docker 图形界面。容器只占 20~30MB 内存，4GB 的机器无压力。

这一步的界面点击、挂载配置、容器终端排障都展开写在
**[`nas-deploy.md`](./nas-deploy.md)**，照着做即可。这里只留三个最容易翻车的点：

| 坑 | 后果 |
|---|---|
| `frpc.toml` 变成 `frpc.toml.txt` | 容器启动即退，日志 `config file not found` |
| 镜像没加国内源前缀 | 卡在 `client.Timeout exceeded while awaiting headers`，Docker Hub 直连不通 |
| 镜像选了只有 amd64 的 | 启动报 `exec format error`，必须用带 arm64 层的官方镜像 |
| 改完配置忘了重启容器 | 配置不热加载，改了半天没生效 |

镜像用 `docker.1panel.live/fatedier/frpc:v0.71.1`（前缀是国内镜像源，去掉就拉不动），
挂载 `我的文件/docker/frpc` → `/etc/frp`，网络保持 bridge，端口不用加。
启动成功的标志是日志里两行 `start proxy success`。

## 第四步：验证（按顺序，别跳步）

```bash
# 1. 设备本身通不通（在 NAS 所在局域网内测）
curl http://192.168.1.50:8080/status

# 2. 隧道通不通（任意网络）
curl -u sparkbot:密码 https://bot.example.com/bot/status

# 3. 控制指令
curl -u sparkbot:密码 "https://bot.example.com/bot/move?x=0&y=1"
curl -u sparkbot:密码 https://bot.example.com/bot/stop

# 4. 图传（存下来看能不能打开）
curl -u sparkbot:密码 -o /tmp/remote.jpg https://bot.example.com/botcam/snapshot
file /tmp/remote.jpg        # 应该是 JPEG image data, 640x480
```

四步全过，剩下的就只是 App 改 baseUrl 了。

## 第五步：App 切换远程

`SparkApi.kt` 里现在硬编码 `http://$host:$port$path`，改成按 Endpoint 计算：

```kotlin
sealed interface Endpoint {
    data class Lan(val ip: String) : Endpoint
    data class Remote(val host: String) : Endpoint
}

fun Endpoint.ctrl(path: String) = when (this) {
    is Endpoint.Lan    -> "http://$ip:8080$path"
    is Endpoint.Remote -> "https://$host/bot$path"
}

fun Endpoint.cam(path: String) = when (this) {
    is Endpoint.Lan    -> "http://$ip:8081$path"
    is Endpoint.Remote -> "https://$host/botcam$path"
}
```

两处都还要加鉴权头（`Endpoint.Remote` 时）：

```kotlin
setRequestProperty("Authorization", "Basic " + Base64.encodeToString(
    "sparkbot:密码".toByteArray(), Base64.NO_WRAP))
```

`AppViewModel` 和四个页面**一行都不用改**——它们拿到的是封装好的方法，不知道背后走的是
局域网还是公网。这也是为什么这套方案值得做：网络位置变成了 Endpoint 的一个属性，
而不是散落在各处的 if 判断。

---

## 排障表

| 现象 | 原因 | 处理 |
|---|---|---|
| 容器反复重启 | token 两边不一致，或 7000 没放行 | 看容器日志，比对 frpc/frps 的 token |
| `/bot/status` 一直 502 | frps 没有客户端接入 | `journalctl -u frps -f` 看有没有 `proxy added` |
| `/bot/status` 401 | 请求没带 Basic 头 | App 里加 Authorization |
| 隧道在、设备一直离线 | 设备 IP 漂了 | 回前端绑静态 IP |
| `/botcam/stream` 越看越卡 | Nginx 缓存了 MJPEG | 确认 `proxy_buffering off` 已生效 |
| 能控制不能看图 | 8081 是单任务 httpd | 关掉其他正在看图的客户端 |
| 图传 1~2fps | 固件瓶颈，不是网络 | 见下方说明 |

## 关于帧率：别怀疑网络

设备实测图传 **1.6fps**（640×480，单帧 28KB ≈ 360kbps）。瓶颈在 GC2145 抓帧 +
软件 JPEG 编码的 500~600ms/帧，**不在带宽**。千兆下行配 30~50Mbps 上行的家宽，
360kbps 只占 1% 左右，隧道不会是限制项。

想真的提帧率只能改固件：降到 320×240 + quality 60，能到 4~6fps，带宽也会跟着降。
这件事和远程化是两件独立的事，先把通路打通再谈优化。

## 安全边界

- 设备本身没有密码，**鉴权完全靠 Nginx 的 Basic Auth**。密码泄露 = 车能被别人开。
- 18080/18081 只绑 `127.0.0.1`，公网扫不到，但别为了图方便改成 `0.0.0.0`。
- 整条链路是 WSS/HTTPS，方向键不会被运营商或中间节点看到。
- App 里存的是明文密码（SharedPreferences），自己用够了；要给别人用得换 token 方案。
