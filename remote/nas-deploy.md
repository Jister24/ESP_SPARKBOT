# 极空间 Z2Pro 部署 frpc 详细步骤

目标：在 NAS 上跑一个 frpc 容器，把设备（ESP32-S3）的 8080 / 8081 隧道到阿里云。

先说明一个容易误解的点：**极空间自带的「远程访问」帮不上忙**。那个功能是给它自己的相册、
影视、文件服务用的，只能访问 NAS 本机的应用。我们的 8080 端口属于 ESP32，不是 NAS 的服务，
所以必须自己跑隧道客户端。

---

## 前置检查（三项，缺一项后面都白做）

| 检查项 | 怎么查 | 期望 |
|---|---|---|
| 设备 IP 已固定 | 路由器后台 → DHCP 静态分配，按 MAC `aa:bb:cc:dd:ee:ff` 绑 `192.168.1.50` | 重启设备后 IP 不变 |
| NAS 能访问设备 | 见下方「容器终端自测」 | 能拿到 JSON |
| 服务器 frps 已启动 | 阿里云 `systemctl status frps` | active (running) |

NAS 的局域网 IP 顺手记一下（极空间客户端 → 设备监控 → 网络信息），后面排障要用。

---

## 第一步：准备配置文件并填 token

在 NAS 共享目录下建一个文件夹（极空间里是 `我的文件/Docker/frpc`，注意目录名 `Docker`
首字母大写），把仓库里的 `frpc.toml` 放进去，容器会把它挂载到 `/etc/frp`。

打开 `frpc.toml`，替换三处占位符：

| 占位符 | 换成什么 |
|---|---|
| `bot.example.com` | 你的服务器域名 |
| `192.168.1.50` | 设备在局域网里的固定 IP |
| `REPLACE_WITH_RANDOM_TOKEN` | `openssl rand -hex 24` 的输出 |

`auth.token` 和 `webServer.password` 用同一个随机串，并且**必须与服务器上 `frps.toml`
里的值完全一致**——不一致的表现是容器反复重启，日志里刷
`token in login doesn't match`。

以后要换 token（比如怀疑泄露）：改两边配置 → 重启 NAS 容器 → 重启服务器 frps。

**历史坑存档**：文件名不能是 `frpc.toml.txt`。Windows 隐藏已知扩展名，记事本里
"另存为 frpc.toml" 实际产出 `frpc.toml.txt`，挂载进容器后 frpc 找不到配置、启动即退，
日志只有一句 `config file not found`。用「显示文件扩展名」或在 NAS 里直接改后缀核对。

---

## 第二步：创建容器

极空间支持 Compose，比在图形界面里逐项点快得多。配置文件放在共享目录里，
容器通过卷挂载读取，不需要打进镜像：

| 文件 | 用途 |
|---|---|
| `frpc.toml` | 容器读取的隧道配置，需自己填 token |
| `frps.toml` | 给服务器用的服务端配置，token 与上面完全一致 |

⚠️ **项目目录里要留空，不能预放 `docker-compose.yml`。**
极空间建项目时会自己往选定路径写一份 compose 文件，目录里已有同名文件就报
「目标路径下已有同名 yml 或 env 文件」，项目根本建不起来。所以 `Docker/frpc/`
里只放两个 toml，compose 内容在界面上粘。

操作：极空间桌面 → **Docker** → **项目**（或「Compose」）→ **新增项目** →
项目名填 `frpc` → 存储路径选 `Docker/frpc` → 把下面的内容粘进编辑器 → 创建并启动。

镜像会由 compose 自动拉取，首次约 20MB，耐心等半分钟。

### 注意：粘的是 compose 内容，不是 frpc.toml

`frpc.toml` 就在同一个目录里，很容易打开它复制。粘成 `frpc.toml` 的内容会报：

```
parsing whatever/docker-compose.yml: Top-level object must be a mapping
```

原因：`frpc.toml` 的第一行有效内容是 `serverAddr = "bot.example.com"`，
YAML 把它当成一个普通字符串，顶层就不是 mapping 了。

真正的 compose 应该长这样（以 `services:` 开头）：

```yaml
services:
  frpc:
    image: snowdreamtech/frpc:latest
    container_name: frpc
    restart: unless-stopped
    entrypoint: ["/usr/bin/frpc"]
    command: ["-c", "/etc/frp/frpc.toml"]
    environment:
      - KEEPALIVE=0
    volumes:
      - /tmp/zfsv3/sata1/<账号>/data/Docker/frpc:/etc/frp:rw
```

### 镜像必须带镜像源前缀

国内直连 `registry-1.docker.io` 会超时，报：

```
frpc Error Get "https://registry-1.docker.io/v2/":
net/http: request canceled while waiting for connection
(Client.Timeout exceeded while awaiting headers)
```

**极空间自带的「仓库」搜索也救不了** —— 它调的是 `hub.docker.com` 的 API，
和拉取用的是同一家在境内的可用性，同样超时。实测这台机器上两个域名都是
8 秒零响应，所以搜不到、也拉不动。

两个可选镜像：

| 镜像 | 体积 | 说明 |
|---|---|---|
| `fatedier/frpc:v0.71.1` | 10MB | 官方，ENTRYPOINT 直接就是 frpc，只需前面加镜像源前缀 |
| `snowdreamtech/frpc:latest` | 104MB | 社区版，含 debian 基础镜像，版本同为 0.71.1，但要覆盖 entrypoint |

官方镜像走国内源写成 `docker.1panel.live/fatedier/frpc:v0.71.1`。实测该源免鉴权、
arm64 层完整、下行约 1.2MB/s。备选源：`docker.1ms.run`、`hub.rat.dev`、
`docker.m.daocloud.io`，用法一样，都是把域名拼在 `fatedier/frpc` 前面。

### snowdreamtech 镜像必须覆盖 entrypoint

这个镜像的 `ENTRYPOINT` 不是 frpc，而是一个包装脚本 `docker-entrypoint.sh`，
里面有两个会让人白等的设计：

```sh
if [ "${KEEPALIVE}" = "1" ]; then
  tail -f /dev/null & wait     # 死循环，后面 exec 永远走不到
fi
...
exec gosu "${PUID:-0}:${PGID:-0}" "$@"
```

镜像默认 `KEEPALIVE=1`，所以**按常规写法容器会一直显示「运行中」，但 frpc 从未启动**，
隧道永远是断的。这种故障最难查：容器状态是绿的，日志里也没有报错。

而且它最后 `exec gosu 0:0 "$@"`，把 `command` 当成一个完整命令名，
写 `command: -c /etc/frp/frpc.toml` 会报 `gosu: -c: no such file or directory`。

所以用这个镜像时必须三件事一起做：覆盖 `entrypoint` 为 `/usr/bin/frpc`、
`command` 用数组形式、并把 `KEEPALIVE=0` 显式写上。

### 其他拉取异常

| 现象 | 原因 | 处理 |
|---|---|---|
| 报 `exec format error` | 拉到了只有 amd64 的镜像 | Z2Pro 是 ARM64，必须用带 arm64 层的镜像 |
| 换源后仍超时 | 该镜像源当日不可用 | 换下一个备选源，不要反复重试同一个 |

---

## 备选：图形界面逐项创建

如果这个 ZOS 版本没有 Compose 入口，或者想手动控制每一项。

**先拉镜像**：Docker → 镜像 → 仓库搜不到（原因同上），改用「添加镜像」手动输入
`docker.1panel.live/fatedier/frpc:v0.71.1`。拉完在「本地镜像」里能看到。

**再建容器**：选中镜像 → 添加到容器：

| 项 | 填什么 |
|---|---|
| 容器名称 | `frpc` |
| 重启策略 | **总是** |
| 开机自启 | **勾选** |
| 性能限制 | **取消勾选**（4GB 内存，frpc 只吃 20MB） |
| 文件夹 | `我的文件/Docker/frpc` → 装载 `/etc/frp`，读写 |
| 端口 | **不用加**（frpc 是出站客户端，不监听） |
| 网络 | 默认 bridge |
| 命令 | 留空；若启动即退，填 `-c /etc/frp/frpc.toml` |

注意目录名是 **`Docker`**（首字母大写），不是 `docker`。

如果用的是 `snowdreamtech/frpc` 而不是官方镜像，图形界面这条路要多做两步：
环境变量加 `KEEPALIVE=0`，入口（ENTRYPOINT）覆盖为 `/usr/bin/frpc`，
命令填 `-c /etc/frp/frpc.toml`。原因见上面那节，漏了会卡在「容器运行中但隧道不通」。

---

## 第三步：验证

### 4.1 看容器日志

容器列表里点开 `frpc` → 日志。正常的输出长这样：

```
[I] [service.go:295] try to connect to server...
[I] [service.go:337] [xxxxx] login to server success, get run id [xxxxx]
[I] [proxy_manager.go:156] [xxxxx] proxy added: [sparkbot-ctrl sparkbot-cam]
[I] [control.go:170] [sparkbot-ctrl] start proxy success
[I] [control.go:170] [sparkbot-cam] start proxy success
```

看到两行 `start proxy success` 就说明隧道通了。

### 4.2 看服务器侧

阿里云上：

```bash
journalctl -u frps -f
```

应同步出现：

```
[sparkbot-ctrl] proxy added
[sparkbot-cam] proxy added
```

### 4.3 从外网验证

手机切到 4G/5G（**关掉 Wi-Fi**，否则测的还是局域网），用任意 HTTP 工具：

```bash
curl -u sparkbot:你设的密码 https://bot.example.com/bot/status
```

返回 `{"ok":true,"ip":"192.168.1.50",...}` 就彻底通了。

---

## 第四步：用容器终端排障（极空间的隐藏能力）

极空间不开放宿主机 SSH，但 **Docker 容器内部可以进终端**——这足够定位绝大多数问题。

容器详情页 → **SSH**（或「终端」）→ 命令选 `/bin/sh` → 连接。

进去后依次执行：

```sh
# 1. 配置有没有挂进来（验证挂载点，最常见的失败原因）
cat /etc/frp/frpc.toml

# 2. DNS 能不能解析服务器
nslookup bot.example.com

# 3. 从 NAS 到设备这一段通不通
wget -qO- http://192.168.1.50:8080/status

# 4. 到服务器的 7000 端口通不通（frps 不是 HTTP 协议，只能用 nc 探）
nc -z -w 3 bot.example.com 7000 && echo "7000 通"
```

四条里哪条失败，问题就锁定在哪一段：

| 失败的命令 | 说明 |
|---|---|
| `cat` 报 No such file | 挂载路径填错，或文件名成了 `.txt` |
| `nslookup` 无结果 | 容器 DNS 问题，网络模式改 host |
| `wget` 设备超时 | 设备 IP 不对，或设备没开机 |
| 7000 不通 | 阿里云安全组没放行 7000 |

---

## 日常维护

| 场景 | 操作 |
|---|---|
| 改了 frpc.toml | **必须重启容器**，配置文件不会热加载 |
| 设备换了 IP | 改 `localIP` 后重启容器（所以最好一开始就绑静态） |
| 升级 frp | 拉新版本镜像，删旧容器重建；frps 和 frpc 版本尽量一致 |
| 容器一直重启 | 看日志第一条报错，九成是 token 不匹配或配置文件没挂上 |
| NAS 重启后隧道断 | 检查「开机自启」是否勾选，以及容器重启策略是不是「总是」 |

## 安全提醒

容器跑起来之后，**任何能访问 `https://bot.example.com/bot/` 的人都能开你的车**。
唯一的门是 Nginx 那层 Basic Auth，密码别用弱口令，也别把 `frps.toml` 里的
`proxyBindAddr` 改成 `0.0.0.0`——那样 18080 会直接暴露在公网，绕过全部鉴权。
