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

## 第一步：配置已就位

三份文件已经放进 NAS 的 `Docker/frpc/`，token 是生成好的 48 位随机串，
`frpc.toml` 和 `frps.toml` 里是同一个值。**这一步不需要你做任何事。**

以后如果想换 token（比如怀疑泄露）：

1. 同时改 `frpc.toml` 和 `frps.toml` 的 `auth.token` 与 `webServer.password`，两边必须一致
2. 重启 NAS 上的 frpc 容器
3. 重启阿里云的 frps

生成新 token：`openssl rand -hex 24`

**历史坑存档**：文件名不能是 `frpc.toml.txt`。Windows 隐藏已知扩展名，记事本里
"另存为 frpc.toml"实际产出 `frpc.toml.txt`，挂载进容器后 frpc 找不到配置、启动即退，
日志只有一句 `config file not found`。这次是直接写入 NAS 的，不存在这个问题。

---

## 第二步：创建容器

极空间支持 Compose，比在图形界面里逐项点快得多。文件已经放进 NAS 的
`Docker/frpc/` 了，不用再上传：

| 文件 | 用途 |
|---|---|
| `docker-compose.yml` | 一键建容器，挂载路径已按本机实际路径填好 |
| `frpc.toml` | 容器读取的隧道配置，token 已生成并填入 |
| `frps.toml` | 给阿里云用的服务端配置，token 与上面完全一致 |

操作：极空间桌面 → **Docker** → **项目**（或「Compose」）→ **新增项目** →
项目名填 `frpc` → 把 `docker-compose.yml` 的内容粘进去 → 创建并启动。

镜像会由 compose 自动拉取，首次约 20MB，耐心等半分钟。

### 注意：粘的是 docker-compose.yml，不是 frpc.toml

两个文件都在同一个目录里，很容易拿错。粘成 `frpc.toml` 的内容会报：

```
parsing whatever/docker-compose.yml: Top-level object must be a mapping
```

原因：`frpc.toml` 的第一行有效内容是 `serverAddr = "bot.example.com"`，
YAML 把它当成一个普通字符串，顶层就不是 mapping 了。

真正的 compose 应该长这样（以 `services:` 开头）：

```yaml
services:
  frpc:
    image: fatedier/frpc:v0.71.1
    container_name: frpc
    restart: unless-stopped
    command: -c /etc/frp/frpc.toml
    volumes:
      - /tmp/zfsv3/sata1/<账号>/data/Docker/frpc:/etc/frp:rw
```

### 镜像拉取失败怎么办

| 现象 | 原因 | 处理 |
|---|---|---|
| 一直在 pull 卡住 | 国内直连 Docker Hub 不稳 | 设置里开镜像加速，或重启项目重试 |
| 报 `exec format error` | 拉到了只有 amd64 的镜像 | Z2Pro 是 ARM64，必须用 `fatedier/frpc` 官方镜像 |
| 找不到 `fatedier/frpc` | 仓库搜索不全 | 在 Compose 里直接写死 `fatedier/frpc:v0.71.1`，不走搜索 |

---

## 备选：图形界面逐项创建

如果这个 ZOS 版本没有 Compose 入口，或者想手动控制每一项。

**先拉镜像**：Docker → 镜像 → 仓库 → 搜 `fatedier/frpc` → 标签选 `v0.71.1`
（不要选 latest）。拉完在「本地镜像」里能看到。

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
