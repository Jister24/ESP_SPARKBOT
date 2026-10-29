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

## 第一步：准备 frpc.toml

### 1.1 在电脑上改好配置

打开 `remote/frpc.toml`，需要改两处：

```toml
auth.token = "REPLACE_WITH_RANDOM_TOKEN"       # 改成和服务器 frps.toml 一致
webServer.password = "REPLACE_WITH_RANDOM_TOKEN"  # 同上
```

token 生成：

```bash
openssl rand -hex 24
```

完整配置（可直接复制改 token）：

```toml
serverAddr = "bot.example.com"
serverPort = 7000

auth.method = "token"
auth.token = "把上面生成的串粘这里"

transport.tls.enable = true
loginFailExit = false

webServer.addr = "0.0.0.0"
webServer.port = 7400
webServer.user = "admin"
webServer.password = "同一个串也行"

[[proxies]]
name = "sparkbot-ctrl"
type = "tcp"
localIP = "192.168.1.50"
localPort = 8080
remotePort = 18080

[[proxies]]
name = "sparkbot-cam"
type = "tcp"
localIP = "192.168.1.50"
localPort = 8081
remotePort = 18081
```

### 1.2 上传到 NAS

极空间客户端（手机或电脑版）→ 我的文件 → 新建文件夹 `docker` → 里面再建 `frpc` →
把 `frpc.toml` 传进去。

最终路径形如：`我的文件/docker/frpc/frpc.toml`

**这里有个必踩的坑：文件名不能变成 `frpc.toml.txt`。**

Windows 默认隐藏已知扩展名，你在记事本里"另存为 `frpc.toml`"实际产出的是
`frpc.toml.txt`。挂载进容器后 frpc 找不到 `/etc/frp/frpc.toml`，容器启动即退出，
日志只有一句 `config file not found`。

上传前在极空间文件列表里确认名字显示就是 `frpc.toml`，没有多余的 `.txt`。

---

## 第二步：拉取镜像

极空间桌面 → **Docker**（若没有，先去「应用中心」安装 Docker 组件）
→ 顶部 **镜像** → **仓库** → 搜索框输入 `fatedier/frpc` → 点搜索图标。

选中结果 → 点「下载」→ 标签选择 **`v0.71.1`**（不要选 latest）。

拉镜像这一步有三个常见卡点：

| 现象 | 原因 | 处理 |
|---|---|---|
| 搜不到 `fatedier/frpc` | 极空间仓库搜索只匹配部分字段 | 改用「添加镜像」手动输入 `fatedier/frpc:v0.71.1` |
| 下载卡住不动 | 国内直连 Docker Hub 不稳 | 设置里开镜像加速，或换 `snowdreamtech/frpc:v0.71.1` |
| 启动报 `exec format error` | 拉到了只有 amd64 的镜像 | Z2Pro 是 ARM64，必须用带 arm64 层的官方镜像 |

下载完成后会自动跳到「本地镜像」，能看到 `fatedier/frpc:v0.71.1`。

---

## 第三步：创建容器

在「本地镜像」里选中 `fatedier/frpc:v0.71.1` → 点 **添加到容器**。

按下面逐项填：

### 基础设置

| 项 | 填什么 |
|---|---|
| 容器名称 | `frpc` |
| 重启策略 | **总是**（NAS 重启、容器崩溃都自动拉起） |
| 开机自启 | **勾选** |
| 性能限制 | **取消勾选**（Z2Pro 只有 4GB 内存，但 frpc 只吃 20MB，限制反而容易出问题） |

### 文件夹（关键，决定成败）

点 **添加**：

| 项 | 填什么 |
|---|---|
| 主机路径 | 选 `我的文件/docker/frpc`（就是刚上传配置的目录） |
| 装载路径 | `/etc/frp` |
| 权限 | 读写 |

挂整个目录比挂单个文件稳。如果极空间这个版本只支持挂文件，那就挂
`frpc.toml` → `/etc/frp/frpc.toml`，效果一样。

### 端口

**不用加。** frpc 是主动连出去的客户端，不在 NAS 上监听任何端口。
如果界面强制要求至少一条，随便填 `7400 → 7400`（对应配置里的 webServer），不影响功能。

### 网络

保持默认 **bridge**。frpc 只需要能出网，不需要被外部访问。

如果后面日志报 DNS 解析失败（`lookup bot.example.com: no such host`），再改成 **host**。

### 环境变量

不需要。

### 命令

**先留空。** 官方镜像的 ENTRYPOINT 已经是 `/usr/bin/frpc`，CMD 是 `-c /etc/frp/frpc.toml`，
默认就能跑。

只有一种情况要手填：容器启动后三五秒就退出、日志里报参数错误。那时在命令/启动参数栏填：

```
-c /etc/frp/frpc.toml
```

全部确认后点 **应用**。

---

## 第四步：验证

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

## 第五步：用容器终端排障（极空间的隐藏能力）

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
