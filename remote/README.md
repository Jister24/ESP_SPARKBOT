# 远程访问配置模板

一套「NAS 代跑 frpc + 云服务器 frps + Nginx 收口」的配置模板，用来把局域网里的
ESP32 机器人搬到公网 HTTPS 后面。**固件不需要任何改动。**

完整的方案说明——为什么不用公网 IP / WebRTC / 商业穿透、架构设计、按层分组的踩坑合集、
客户端 `Endpoint` 抽象——见 **[`../docs/remote-access.md`](../docs/remote-access.md)**。

## 文件

| 文件 | 放在哪 | 作用 |
|---|---|---|
| `frps.toml` | 云服务器 `/etc/frp/` | 7000 收隧道，代理端口绑 `127.0.0.1` |
| `nginx-sparkbot.conf` | 云服务器现有 server 块 | `/bot/` 控制、`/botcam/` 图传，套 TLS + Basic 鉴权 |
| `frpc.toml` | NAS 共享目录，挂到容器 `/etc/frp` | 把设备 `8080/8081` 隧道出去 |
| `docker-compose.yml` | NAS 的 Compose 项目 | 起 frpc 容器 |
| `nas-deploy.md` | — | 极空间 Z2Pro 的逐项操作与容器内排障 |

## 部署顺序

1. 路由器给设备绑静态 IP（不做这步，后面全是白费功夫）
2. 云服务器装 frps 并 enable
3. Nginx 加两个 location，生成 htpasswd 文件
4. NAS 起 frpc 容器，等日志出现两行 `start proxy success`
5. 按四步验证：设备 → 隧道 → 控制 → 图传

## 必须替换的占位符

| 占位符 | 换成什么 |
|---|---|
| `bot.example.com` | 你自己的域名（已配 HTTPS 证书） |
| `192.168.1.50` | 设备在局域网里的固定 IP |
| `REPLACE_WITH_RANDOM_TOKEN` | `openssl rand -hex 24` 的输出，**frpc 与 frps 必须完全一致** |
| `/PATH_TO_DOCKER` | NAS 上共享目录的**容器内真实路径** |

## 三条不能破的规则

1. `proxyBindAddr = "127.0.0.1"` 不许改成 `0.0.0.0`——那样代理端口直接铺在公网，绕过全部鉴权。
2. 云安全组只放行 7000。18080/18081 绑的是回环，本来就不该有公网可达性。
3. htpasswd 文件必须 `root:www-data` + `640`，否则 nginx worker 打不开，症状是「带正确密码反而 500」。

极空间 Z2Pro 的具体按钮在哪、容器终端怎么进、拉不到镜像怎么换源，见 [`nas-deploy.md`](./nas-deploy.md)。
