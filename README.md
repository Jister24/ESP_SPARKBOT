# ESP-SparkBot 局域网控制（保留小智 AI）

在**不改动小智原有 AI 对话链路**的前提下，给 ESP-SparkBot 增加局域网直连的运动控制与图传。

---

## 一、为什么原本连不上

对 `192.168.1.50` 的实测结果：

| 检查项 | 结果 |
|---|---|
| ping | 通（36~219ms） |
| MAC | `aa:bb:cc:dd:ee:ff`（乐鑫） |
| TCP 1–10000 | **全部关闭** |
| mDNS | 无广播 |

设备在线，但**本地没有任何服务在监听**。原因是官方小智固件连上 WiFi 后只做一件事：主动向小智云发起 WebSocket 长连接，控制指令走云端 MCP 工具下发，设备侧不开放任何本地端口。

官方另有一个 `example/tank/sparkbot_motion_control` 固件带 Web 控制台，但源码里写死：

```c
wifi_mode_t wifi_mode = WIFI_MODE_AP;   // web_server.c:40
```

它**只开热点、不连路由器**，访问地址是 `192.168.4.1`。所以「用 `192.168.1.x` 访问」这条路官方本来就没提供——必须自己加。

---

## 二、本方案做了什么

新增一个 `LocalControl` 模块，挂在板级初始化之后，复用已有的 UART 底盘通道和摄像头，与云端链路并存：

```
        ┌─ 小智云 WebSocket（原有，不动）→ 语音对话 / 云端 MCP
ESP32-S3┤
        └─ LocalControl（新增）
             ├─ :8080  控制，响应 <10ms
             └─ :8081  图像，MJPEG 流 / 单帧 JPEG
                     ↓
              UART → ESP32-C2 履带底盘（原有）
```

**为什么拆两个端口**：ESP-IDF 的 `httpd` 是单任务串行处理。抓一帧 + JPEG 编码约 300~500ms，如果和控制指令放同一个实例，推流期间按方向键会有半秒延迟。拆开后控制端口永远不被图像请求阻塞。

### 接口

| 端口 | 路径 | 说明 |
|---|---|---|
| 8080 | `GET /` | 控制页面（方向键、速度、实时画面） |
| 8080 | `GET /move?x=&y=` | 运动，取值 -1.0~1.0（**y 为正前进**，y 为负后退，x 为正右转，x 为负左转，且 \|x\|+\|y\|≤1） |
| 8080 | `GET /stop` | 急停 |
| 8080 | `GET /cmd?c=` | 原始底盘指令：`d1` 跳舞、`w2` 灯光、`x0.5 y0.5` |
| 8080 | `GET /rot?on=1\|0` | 画面方向，默认 `1`（转正 180°），省略参数即切换，写 NVS 掉电保存 |
| 8080 | `GET /status` | 状态 JSON（表情风格下额外带 `flush` / `last_flush_ms`，见第九节） |
| 8081 | `GET /stream` | MJPEG 连续流，浏览器 `<img>` 可直接显示 |
| 8081 | `GET /snapshot` | 单帧 JPEG |

---

## 三、前置条件

- ✅ ESP-SparkBot 本体 + **履带底盘**（4P 磁吸口 + ESP32-C2 主控）。没有底盘就只能取图，动不了。
- ✅ ESP-IDF v5.4 或更高（ESP32-S3 目标）
- ✅ 设备与电脑在同一局域网
- ⚠️ 建议在路由器上给设备绑定静态 IP，否则 DHCP 续租后 IP 会变

本机目前**没有安装 ESP-IDF**，需要先装。

---

## 四、操作步骤

### 1. 装 ESP-IDF

```bash
git clone -b v5.5 --recursive https://github.com/espressif/esp-idf.git ~/esp/esp-idf
cd ~/esp/esp-idf && ./install.sh esp32s3
. ~/esp/esp-idf/export.sh
```

### 2. 拉固件源码

```bash
git clone --recursive https://github.com/78/xiaozhi-esp32.git
cd xiaozhi-esp32
```

### 3. 打补丁

```bash
python3 /Users/chendanyang/WorkBuddy/Esp-sparkbot/firmware/apply.py .
```

脚本只做插入、不删改原逻辑，幂等可重复执行。改动 4 个文件 + 新增 2 个：

| 文件 | 改动 |
|---|---|
| `boards/common/camera.h` | 加 `GetJpegFrame` / `SetRotate180` 虚方法（默认 no-op） |
| `boards/common/esp_video.h/.cc` | 实现 `GetJpegFrame`、软件 180° 旋转 |
| `boards/espressif/esp-sparkbot/esp_sparkbot_board.cc` | 构造函数里启动服务 |
| `boards/espressif/esp-sparkbot/local_control.h/.cc` | 新增模块 |

回滚：`python3 apply.py . --revert`（从 `.bak` 恢复）

### 4. 选板子并编译

```bash
idf.py set-target esp32s3
idf.py menuconfig
#   Xiaozhi Assistant → Board Type → esp-sparkbot
idf.py build
```

> 板级源文件用 `file(GLOB ...)` 收集，新增 `.cc` 后需要重新 configure。
> `idf.py build` 一般会自动重跑；若报 undefined reference，删掉 `build/` 再编译。

### 5. 烧录

```bash
idf.py -p /dev/cu.usbmodem* flash monitor
```

拔插 USB 时**按住板上的 BOOT 键**，否则会反复重启（官方 FAQ 明确提到）。

### 6. 验证

开机后串口应出现：

```
I (xxxx) local_ctrl: device ip = 192.168.1.50
I (xxxx) local_ctrl: control server on :8080
I (xxxx) local_ctrl: camera server on :8081
```

浏览器打开 `http://192.168.1.50:8080/` 即可遥控。

---

## 五、客户端

```bash
cd /Users/chendanyang/WorkBuddy/Esp-sparkbot/client

python3 sparkbot.py status                    # 看状态
python3 sparkbot.py drive                     # 键盘遥控 WASD/方向键
python3 sparkbot.py move forward --speed 0.5  # 单步移动
python3 sparkbot.py snap shot.jpg             # 抓一张图
python3 sparkbot.py watch live.jpg            # 持续接图传，每帧覆盖写入
```

默认设备 `192.168.1.50`，可用 `-H` 或环境变量 `SPARKBOT_HOST` 覆盖。

> **注意**：客户端已内置绕过系统代理。本机设置了 `HTTP_PROXY`，若不绕开会像用 curl 那样拿到 502 假响应。

---

## 六、已知限制

| 限制 | 说明 |
|---|---|
| **摄像头互斥** | 云端拍照（`self.camera.xxx`）和本地图传共用同一份帧缓冲，已加互斥锁。语音让它"看看这是什么"和图传同时进行时，其中一个会等另一个。 |
| **CPU 占用** | 图传约 4~5fps，编码在 PSRAM 上跑。高帧率推流期间语音响应可能变慢，已在每帧后留 60ms 间隔。 |
| **底盘依赖** | 运动指令是发给 ESP32-C2 底盘的 UART，没接底盘时指令会被丢弃，不报错。 |
| **底盘看门狗** | C2 侧 500ms 收不到 `x y` 指令就自动停车。控制页和 CLI 已改成"按住期间每 200ms 重发"；直接调一次 `/move` 属于脉冲，最多走半秒。 |
| **IP 漂移** | DHCP 分配，建议路由器绑定静态 IP。 |
| **回退** | 想恢复纯小智固件，重新 `git clone` 官方源码烧一次即可，硬件无改动。 |

---

## 七、如果想先不动固件

官方 `example/tank/sparkbot_motion_control` 可以直接烧，零改码：

- 设备开热点 `esp-sparkbot-XXXX`（无密码）
- 手机连该热点，浏览器开 `192.168.4.1`
- 有完整的遥控界面和图传

代价是设备脱离家里 WiFi，也就没有小智 AI 对话了。适合先验证硬件是否正常。

---

## 八、实测落地记录（2026-10-28，已烧录验证通过）

### 实际硬件

| 项目 | 实测值 |
|---|---|
| 芯片 | ESP32-S3 rev v0.2，8MB Octal PSRAM，16MB Flash |
| MAC | `aa:bb:cc:dd:ee:ff` |
| **摄像头** | **GC2145**（PID 0x2145，I2C 0x3c），不是 OV2640 |
| 屏幕 | 240×240 LCD + LVGL |
| 音频 | ES8311 |
| 原厂固件 | 小智 v1.9.4（ESP-IDF v5.5） |
| 现固件 | 小智 v2.5.3 + LocalControl |
| 当前 IP | `192.168.1.50`（DHCP，会漂移） |

### 分区布局变了，不能只烧 app

上游 v2.5.3 的分区表和 v1.x 完全不同：

| 分区 | v1.x（原厂） | v2.5.3（新） |
|---|---|---|
| nvs | 0x9000 / 16K | 0x9000 / 16K ← 相同 |
| otadata | 0xd000 / 8K | 0xd000 / 8K ← 相同 |
| phy_init | 0xf000 / 4K | 0xf000 / 4K ← 相同 |
| app | ota_0 0x100000 / 6M | ota_0 **0x20000** / 4.1M |
| 资源 | model 0x10000 / 960K | assets 0x800000 / 8M |

好消息是 **nvs/otadata/phy_init 偏移与大小完全一致，所以 WiFi 配网在换布局后依然保留**。

首次换固件必须烧全套（顺序和偏移不能错）：

```bash
cd ~/esp/xiaozhi-esp32 && . ~/esp/esp-idf-v6.1/export.sh
esptool.py -p /dev/cu.usbmodem14401 --baud 921600 --chip esp32s3 write_flash \
  --flash_mode dio --flash_freq 80m --flash_size 16MB \
  0x0      build/bootloader/bootloader.bin \
  0x8000   build/partition_table/partition-table.bin \
  0xd000   build/ota_data_initial.bin \
  0x20000  build/xiaozhi.bin \
  0x800000 build/generated_assets.bin
```

之后再改代码只需重烧 app：`write_flash ... 0x20000 build/xiaozhi.bin`

### 落地时修掉的两个真实 bug

**1. GC2145 驱动未启用**

上游 `config.json` 的 `sdkconfig_append` 只有 `CONFIG_CAMERA_OV2640=y`，与实物不符。
不修的话 `esp_video_init` 会报 `Failed to detect camera sensor`，图传和云端拍照一起失效。
`esp_cam_sensor` 里 gc2145 驱动是现成的，补上 `CONFIG_CAMERA_GC2145=y` 即可。已固化进 `firmware/apply.py`。

**2. `BootstrapTask` 取 netif 句柄过早**

原实现把 `esp_netif_get_handle_from_ifkey("WIFI_STA_DEF")` 放在循环外。该任务在板级构造期
（约 200ms）就创建，而 WiFi 网络接口要到 800ms 才初始化，此时拿到的是 `nullptr`，
之后 60 秒循环里一直与空值比较，必然超时：

```
E (60688) local_ctrl: no IP after 60s, local control service skipped
```

改为每轮循环内重新取句柄，并延长到 120s。修复后：

```
I (2689) local_ctrl: device ip = 192.168.1.50
I (2689) local_ctrl: control server on :8080
I (2689) local_ctrl: camera server on :8081
I (2689) local_ctrl: local control ready: http://192.168.1.50:8080/
```

### 验证结果

| 测试 | 结果 |
|---|---|
| `GET :8080/status` | `{"ok":true,"ip":"192.168.1.50","x":0,"y":0,"uptime_s":32,"camera":true}` |
| `GET :8080/` 控制页 | HTTP 200，4340 字节 |
| `GET :8080/stop` | `stopped` |
| `GET :8080/move?x=0&y=0` | `ok` |
| `GET :8081/snapshot` | HTTP 200，32324 字节，**640×480 JPEG**，`file` 识别正常 |
| 摄像头识别 | `gc2145: Detected Camera sensor PID=0x2145` → `EspVideo: Camera init success` |
| 抓图画面 | 实际场景清晰、颜色正常（无绿屏/偏色） |
| 控制响应耗时 | **0.031s** |
| 抓图耗时 | **0.647s**（不影响控制端口） |
| WiFi 配网 | 换布局后仍保留，自动连回 example-wifi |
| 设备 UUID | 未变（NVS 保留的证据） |

### 备份

`backup/full_flash_16MB_20261028.bin`（16777216 字节，MD5 `78ee9845f83a8ff9b7ec9b8d20a5729d`）
为换固件前 v1.9.4 的整片备份，回滚直接 `write_flash 0x0` 即可。

---

## 九、画面倒立 → 默认转正 180°（2026-10-29）

### 现象与根因

摄像头抓图上下颠倒（笔记本 Dock 栏跑到画面顶部）。原因是**板载 GC2145 是倒装的**，
传感器输出本身就是倒像，需要在出图链路里补一次 180°。

### 为什么不用现成的两条路

| 方案 | 结论 |
|---|---|
| `SetHMirror` / `SetVFlip`（原厂已有代码） | **不可用**。全仓 grep `V4L2_CID_HFLIP`/`VFLIP`，`esp_video` 和 `esp_cam_sensor` 里 **没有任何实现**，GC2145 驱动也没有 mirror/flip 的 ioctl，这两个函数调用必然 `set HFLIP failed` |
| xiaozhi 自带的 `CONFIG_XIAOZHI_ENABLE_ROTATE_CAMERA_IMAGE` | **不可用**。它只支持 90°/270°，而且是给 ESP32-P4 的 PPA 准备的分支；ESP32-S3 没有 PPA，走的是 `esp_imgfx_rotate` 软件旋转，`degree` 只接受 90/270，没有 180 |

### 实际做法：软件整帧翻转，零额外内存

180° = 水平镜像 + 垂直镜像 = **所有像素首尾颠倒**，正好可以就地双指针对换，
不需要第二块 614KB 缓冲。实现放在 `EspVideo::Capture()` 里、填充完 `frame_` 之后：

```cpp
if (rotate180_) {
    RotateFrame180();
}
```

这样一来 **LCD 预览 / 局域网图传 / 云端拍照（`Explain`）吃的是同一份已转正数据**，
三条链路口径一致，不会出现"本地正了、让小智看图还是倒的"。

按像素格式分别处理：

| 格式 | 处理 |
|---|---|
| `RGB565`（本机实际模式 `..._DVP_RGB565_BE_640X480_15FPS`） | 按 `uint16` 整帧逆序 |
| `GREY` | 按字节逆序 |
| `RGB24` | 按 3 字节像素逆序 |
| `YUYV` / `UYVY` | 按 4 字节宏像素逆序，**组内两个 Y 还要对调**（YUYV 换第 0/2 字节，UYVY 换第 1/3 字节）。不换会让亮度左右错开一个采样点 |

### 开关（想切回原始方向不用重烧）

| 方式 | 用法 |
|---|---|
| 控制页 | 底部「画面方向：已转正 180°」按钮，点一下切回原始并自动重连图传 |
| HTTP | `GET :8080/rot?on=0` / `?on=1`，省略参数即切换 |
| 命令行 | `python3 sparkbot.py rot [on\|off]` |
| 状态 | `GET :8080/status` 多了 `"rot":true` |

状态写在 NVS（namespace `local_ctrl`，key `rot180`），**掉电、换固件 app 分区都不丢**，
默认值 `true`。

### 实测验证

| 测试 | 结果 |
|---|---|
| 关/开各抓一帧，转 180° 后比对 | 直接对齐平均像素差 **98.0**；按 180° 对齐后 **2.9**（JPEG 噪声级）→ 确认是精确翻转 |
| 画面内容 | 关闭时 Dock 栏在屏幕上方（倒立），开启后 Dock 栏在下方、键盘在屏幕下方（正常） |
| 抓图耗时 | `rot=1` 0.59~0.65s，`rot=0` 0.56~0.63s → 差异在噪声内，JPEG 编码仍是瓶颈 |
| 掉电保持 | `/rot?on=0` 后硬复位，`status` 仍返回 `"rot":false` |
| 控制页 | 5141 字节，含方向按钮与 `/rot` 请求 |

编译烧录（只改了 app，不用动其他分区）：

```bash
cd ~/esp/xiaozhi-esp32 && . ~/esp/esp-idf-v6.1/export.sh
python scripts/build.py espressif/esp-sparkbot --language zh-CN
esptool.py -p /dev/cu.usbmodem14401 --baud 921600 --chip esp32s3 write_flash \
  --flash_mode dio --flash_freq 80m --flash_size 16MB 0x20000 build/xiaozhi.bin
```

---

## 十、底盘指令协议与方向修正（2026-10-29）

### C2 底盘真正认的指令

底盘主控是 ESP32-C2，跑官方 `example/tank/c2_tracked_chassis`。它用 `sscanf` 解析，
**只认下面 4 条**，其余一律 `Unknown command` 丢弃：

| 指令 | 作用 | 说明 |
|---|---|---|
| `x<float> y<float>` | 运动 | 必须两个都在、**单个空格**分隔。底盘侧 `left = y+x`、`right = y-x`（×100 当占空比）。**y>0 前进，y<0 后退；x>0 右转，x<0 左转** |
| `w<float>` | 灯光 | 0 充电呼吸 / 1 低电 / 2 常亮 / 3 闪烁 / 4 白光慢呼吸 / 5 白光快呼吸 / 6 流水 / 7 灯效秀 / 8 睡眠 |
| `d<float>` | 跳舞 | 值不参与逻辑，`d1` 是习惯写法。**阻塞约 17 秒**，期间不读串口 |
| `c<float>` | 电机校准 | 负数每次把左轮系数 −0.025，正数把右轮 −0.025，下限 0.7；发对方每次重置为 1.0 |

两个必须知道的机制：

- **看门狗 500ms**：连续 25 次循环（每次 `uart_read_bytes` 超时 20ms）没收到合法的 `x y`，
  C2 会自动 `spark_bot_motion_control(0,0)` 停车。所以"按住一直走"必须**周期重发**。
- **只有正向 clamp**：`left/right > 100` 会被截到 100，负数没有截。
  `x=-1,y=-1` 会算出 `-200`，`duty = 16384` 超出 13 位 PWM 上限，行为未定义。
  单轴或 `|x|+|y|≤1` 才安全。

### 修掉的两个问题

**1. y 方向反了**

官方语音工具 `self.chassis.go_forward` 发的是 `x0.0 y1.0`，即 **y 为正才是前进**。
而我们的控制页 ▲ 和 CLI `move forward` 原来发的是 `y=-1`，按协议是**后退**。

| 位置 | 改前 | 改后 |
|---|---|---|
| 控制页 ▲ / W / ↑ | `0,-1` | `0,1` |
| 控制页 ▼ / S / ↓ | `0,1` | `0,-1` |
| CLI `move forward` | `y=-1` | `y=+1` |
| CLI `move back` | `y=+1` | `y=-1` |

`x` 方向没动（正=右转，与底盘 `left=y+x` 一致）。

**2. 按住不会持续走**

原来只在 `pointerdown`/`keydown` 发一次，500ms 后底盘看门狗自动停车，实际是脉冲。
现在按住期间每 **200ms** 重发一次（`REPEAT_MS`），松手发 `/stop`；键盘自动重复已被
`held` 集合拦掉，不会叠加多个定时器。CLI 侧新增 `hold()`，`--pulse` 可以超过 0.5s。

固件侧同时做了日志去重：重复指令**照发**（否则压不住看门狗），但只在 x/y 真正变化时打
`local_ctrl: move`，避免按住时刷屏。

### 验证

| 测试 | 结果 |
|---|---|
| 串口抓包 | `/move?x=0&y=1` → `Sent command: x0.00 y1.00`；`/stop` → `x0.00 y0.00` |
| 模拟按住 1 秒（5 次 200ms 重发） | UART 实际发送 **5 次**，`local_ctrl: move` 只打 **1 次** → 看门狗压得住且不刷屏 |
| 连续请求耗时 | 正常 0.025~0.05s，偶发 0.5~0.7s 毛刺（启动期与云端 WebSocket 抢占），仍低于 500ms 看门狗 |
| 控制页校验 | ▲=`0,1`、▼=`0,-1`、W/↑=`0,1`、`REPEAT_MS=200` |
| **方向** | ⚠️ 仍**未实机目视确认**，接上履带后用 `python3 sparkbot.py move forward --speed 0.3` 看一眼 |

底盘还没接时，可以先用 `curl` 之类直接验证指令是否被接受（不会报错，只是没动作）：

```bash
python3 sparkbot.py raw 'x0 y1'      # 前进
python3 sparkbot.py raw 'd1'         # 跳舞
python3 sparkbot.py raw 'w3'         # 灯光切闪烁
```

---

## 十一、表情风格：换成乐鑫 emote 动画（2026-10-29 已烧录验证）

原固件用的是 `SpiLcdDisplay`（LVGL 那套），表情是 emoji 图片贴在屏上。这次换成
**乐鑫 emote 动画系统**（`esp_emote_expression` + `gfx_anim` + `.eaf` 资源包），
也就是 `github.com/espressif2022/esp_emote_gen_player` 那套规范里的**黑脸 + 白色五官**风格：

```
assets 分区（8MB，0x800000）
  ├─ index.json        布局 + 情绪映射表 + 字体/唤醒词索引
  ├─ *.eaf             10 个表情动画（Happy/Sad/angry/confused/cry/sleep/shocked/neutral/winking/listen）
  ├─ font_puhui_common_16_4.bin
  └─ srmodels.bin      唤醒词模型（构建时用当前 sdkconfig 覆盖）
            ↓ mmap
  emote::EmoteDisplay → gfx_anim → esp_lcd_panel_draw_bitmap → ST7789 240×240
```

### 为什么不是简单换个图

表情动画**不经 LVGL**：emote 组件自己开渲染任务，按 `.eaf` 的调色板/RLE 解帧，
再直接调 `esp_lcd_panel_draw_bitmap` 推屏。所以板级要**换显示类**，不是换图片目录。
而且官方只发了 `320_240 / 360_360 / 1024_600` 三套布局，本机是 **240×240**，得自己写一套。

### 改了哪 5 处

| 文件 | 改动 |
|---|---|
| `main/Kconfig.projbuild` | `USE_EMOTE_MESSAGE_STYLE` 的 `depends on` 加上 `BOARD_TYPE_ESP_SPARKBOT`；`choice DISPLAY_STYLE` 加 `default USE_EMOTE_MESSAGE_STYLE if BOARD_TYPE_ESP_SPARKBOT` |
| `main/CMakeLists.txt` | SparkBot 分支加 `EMOTE_RESOLUTION=240_240`、`EMOTE_EXTERNAL_PATH=<板级 assets>`；并在 configure 阶段清旧包 + 把三个 json 纳入 `CMAKE_CONFIGURE_DEPENDS`（打包命令只声明依赖 `build_all.py`，否则改布局不会重打包） |
| `main/boards/espressif/esp-sparkbot/assets/240_240/` | 新增 `layout.json`（240 宽重排）、`emote.json`（21 条情绪映射）、`config.json`（字体/emoji 集/唤醒词） |
| `.../esp_sparkbot_board.cc` | `InitializeDisplay()` 里按 `CONFIG_USE_EMOTE_MESSAGE_STYLE` 二选一：`emote::EmoteDisplay(panel, panel_io, w, h)` 或原 `SpiLcdDisplay` |
| `.../local_control.cc` | `/status` 增加 `flush` / `last_flush_ms`，远程就能判断屏幕是否在刷新 |

配套 sdkconfig 三项（`idf.py menuconfig` 或直接改）：

```
CONFIG_USE_EMOTE_MESSAGE_STYLE=y     # 原来是 USE_DEFAULT_MESSAGE_STYLE
CONFIG_FLASH_EXPRESSION_ASSETS=y     # 原来是 FLASH_DEFAULT_ASSETS
CONFIG_MMAP_FILE_NAME_LENGTH=32      # 原来是 16，字体文件名 27 字符会被截断
```

`MMAP_FILE_NAME_LENGTH` 这条最容易踩：默认 16，打包时文件名**直接截断**，
`font_puhui_common_16_4.bin` 变成 `font_puhui_common_1`，运行时找不到字体，
日志里只看到 `Found 21 emoji items` 却没有 `Foundfont`。

### 240×240 布局怎么算的

`gfx_anim` 的 auto-mirror 是"左右各画一半"：

```
mirror_offset = parent_w - (width + x) * 2     // esp_emote_gfx/src/widget/anim/gfx_anim.c:425
总宽度 = width * 2 + mirror_offset
```

`emoji_large` 的半张脸是 125×160，所以

| 屏幕 | layout 的 `x` | 结果 |
|---|---|---|
| 320×240（官方） | 10 | offset 50，整体 300 宽，居中 |
| **240×240（本机）** | **-5** | offset 0，整体 250 宽，两边各裁 5px（脸是黑底，看不出来） |

竖直方向：数学居中位置是 `y=40`（(240-160)/2），但实测看着偏下，**实际用了 `y=24`**
（上移 16px，顶部留 24、底部留 56），视觉重心更舒服。`listen_anim` 是 100×46 的小动画，
放 `y=4`。想再调就改 `eye_anim` / `emerg_dlg` 两处的 `y`，两个必须一起改，否则弹窗动画会跳。

### 验证结果（实测）

```
I (2329) Expression_load: Found index.json, size: 3642
I (2339) Expression_load: Found 21 emoji items
I (2339) Expression_load: Found 8 icon items
I (2339) Expression_load: Found 10 layout items
I (2359) Expression_load: Foundfont: font_puhui_common_16_4.bin
I (2379) EmoteDisplay: SetEmotion: robot_2      ← 映射到 neutral.eaf，无报错
I (3949) EmoteDisplay: SetStatus: 待命
I (3959) EmoteDisplay: SetEmotion: neutral
```

| 项目 | 结果 |
|---|---|
| assets 分区挂载 | `mmap_assets: Partition 'assets' successfully created` |
| 情绪映射 | 21 条全部命中（含 `robot_2`，开机时应用会先请求它） |
| 刷屏 | `GET /status` → `"flush":1212`，`last_flush_ms` 随后停止增长（idle 是 `loop:false`，播完就停在最后一帧，不空转） |
| 语音链路 | 唤醒词 `wn9_nihaoxiaozhi_tts` 正常加载，MQTT 连上 `api.tenclass.net` |
| 局域网遥控 | 8080 / 8081 正常，`local_ctrl: local control ready` |
| 摄像头 | 不受影响，GC2145 正常 |

### 已知取舍

| 项目 | 说明 |
|---|---|
| **图标在挂载前报错** | 开机到 `Expression_load` 之间会刷 `Not found:"icon_tips"`。那时资源包还没挂上，属上游行为，挂载后不再出现 |
| **表情包占 2.36MB** | `assets` 分区 8MB 够用；换更花的素材主要盯这个数 |
| **idle 不循环** | `neutral`/`idle` 是 `loop:false`，画面静止不刷新，省 CPU；想让它一直动就把 `loop` 改 `true` |
| **回滚** | `sdkconfig.bak.emote` 是改之前的 sdkconfig，恢复后重编译重烧即回 LVGL 风格 |

`firmware/emote_style/240_240/` 是本工作区留档的三份 json，改布局直接在那里改再拷进固件仓库。
`apply.py` 目前**不覆盖这次改动**（它只做插入式补丁，而显示类是二选一替换），所以这几处要按上表手工改。

---

## 十二、Android 客户端（2026-10-29）

`android/` 是一个 Kotlin + Jetpack Compose 的原生 App（包名 `com.shike.sparkbot`），
把固件的局域网接口做成了完整界面，并把 xiaozhi.me 官方控制台内嵌进来。

### 功能

| 页面 | 能力 |
|---|---|
| 设备 | 自动扫描当前网段（并发探测 8080 的 `/status`，254 个地址约 2 秒）、手动添加 IP、多设备保存与切换、在线状态 / 延迟 / 运行时长 / 摄像头可用性 |
| 遥控 | 方向键按住持续行走（200ms 重发压住底盘 500ms 看门狗，松手立即 `/stop`）、速度 10%~100%、急停、跳舞、灯光 0~8、画面方向切换 |
| 相机 | MJPEG 实时预览（自解析 SOI/EOI 切帧，不依赖 multipart 边界）、拍照写入 `Pictures/SparkBot`、重连、方向切换、打开相册 |
| 账号 | 内嵌 xiaozhi.me 官方控制台，登录态持久（Cookie + WebView 实例复用），角色/音色/声纹/OTA/历史对话全部走官方页面 |

### 为什么账号页是内嵌网页

xiaozhi.me 没有公开 API，登录是手机号验证码 + 私有 token。全原生重写等于逆向一套随时会变的私有接口，
且无法在没有真实账号的情况下验证。内嵌官方控制台可以 100% 覆盖官方功能并自动跟随官方改版；
遥控、图传、拍照这些高频、低延迟的操作仍然是纯原生实现。

### 目录

```
android/
├── app/src/main/java/com/shike/sparkbot/
│   ├── MainActivity.kt        底部导航 + 生命周期（退到后台自动停车/断流）
│   ├── AppViewModel.kt        状态机：轮询、按住重发、图传、拍照、WebView 持有
│   ├── net/SparkApi.kt        8080 控制 / 8081 图传的 HTTP 封装
│   ├── net/Discovery.kt       /24 网段并发扫描（Wi-Fi 接口优先）
│   ├── net/MjpegStreamer.kt   MJPEG 切帧 + 解码（上限 15fps）
│   ├── media/PhotoSaver.kt    MediaStore 写相册（API 29+ 用 RELATIVE_PATH）
│   └── ui/                    设备 / 遥控 / 相机 / 账号 四个页面
└── sparkbot-1.0.0-debug.apk   已编译好的安装包
```

### 编译与安装

```bash
cd android
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以用 `adb shell am start -n com.shike.sparkbot/.MainActivity -e ip 192.168.1.50` 直接指定设备地址。

### 几个实现要点

- **8081 是单任务 httpd**：MJPEG 流没断开时 `/snapshot` 会一直排队。实测「流未断开时抓图」8 秒超时，
  所以拍照流程是 `停流 → 等 250ms → 抓图 → 恢复流`，实测断开 250ms 后抓图 1.5s 成功。
- **按住重发**：底盘 500ms 无合法 `x y` 就自动停车，页面按住期间每 200ms 重发一次，松手/滑出/切页/退后台都发 `/stop`。
- **方向**：▲ 发 `y=+1`（前进），与官方 `go_forward` 一致；x 不变（正=右转）。
- **图标零依赖**：底部导航和状态点都是 Canvas 手绘，没有引入 material-icons-extended。

### 实测（Android 15 模拟器，Pixel 6，1080×2400）

| 测试 | 结果 |
|---|---|
| 安装启动 | 冷启动直接进遥控页（已有设备），设备卡片显示在线、摄像头可用 |
| 状态轮询 | `/status` 每 3 秒刷新，延迟 1000~1500ms（模拟器 NAT 导致，真机为几十毫秒） |
| 按住前进 | 按住期间设备 `/status` 返回 `y=1.00`，松手后回到 `y=0.00` |
| 图传 | 预览 640×480 实时画面，LIVE 角标 1~2fps（软件渲染 + 解码，真机 8~10fps） |
| 拍照 | 相册生成 `Pictures/SparkBot/SPARK_20261029_013104.jpg`，640×480，5854B，JPEG 头正确 |
| 方向切换 | 点「画面翻转」后设备 `rot:false`，再点回 `rot:true`，图传不用重连、下一帧即变（详见第十四节） |
| 官方控制台 | 加载出登录页（短信验证码 / 用户名密码、图形验证码、用户协议），切页再回来不重新加载 |

### 已知限制

- Debug 签名，换机安装需先卸载同包名旧版本；上架/长期分发需要自己的 keystore。
- 账号页是 WebView，需要联网；离线时只能做局域网控制。
- 拍照瞬间预览会黑一下（8081 单任务导致，约 1 秒）。
- 跳舞指令阻塞底盘 17 秒，期间方向键无效，页面已有提示。

---

## 十三、真机实测（小米 12S / Android 15）

机型 `2206122SC`，Android 15 / SDK 35，1440×3200 @ 560dpi，Wi-Fi IP `192.168.1.100`
（与机器人同 `192.168.1.0/24`，路由器 `aa:bb:cc:dd:ee:01`）。

### 结果

| 测试 | 结果 |
|---|---|
| 安装 | `adb install -r` Success，冷启动进设备页，无崩溃 |
| 局域网扫描 | 找到 `SparkBot 212`，显示在线 / 延迟 79ms / 运行 4m / 摄像头可用 / 已转正 |
| 按住前进 | 按住期间设备 `/status` 连续返回 `y=1.00`，松手后回到 `y=0.00` |
| 拍照 | 写入 `Pictures/SparkBot/SPARK_20261029_075850.jpg`，640×480，28617B，SOI/EOI 正确，MediaStore 已登记（相册可见） |
| 画面方向 | 点「恢复原始方向」→ 设备 `rot:false`；再点回 → `rot:true` |
| 灯光 | 点「3 · 闪烁」高亮生效，无异常日志 |
| 官方控制台 | 登录页完整加载（短信验证码 / 用户名密码、图形验证码、协议勾选） |

### 修掉的一个真机问题：扫描超时太短

原来 `Discovery.probe` 的 TCP connect 超时是 **260ms**。机器人刚开机、WiFi 处于省电模式时，
单次往返实测 **700~950ms**，于是整台设备在扫描里被漏掉（第一次真机扫描「未发现设备」就是这个原因）。

改成**两轮扫描**：

1. 第一轮 254 个地址，connect 300ms / status 900ms，并发 64 —— 快速排除大部分地址；
2. 第二轮只对第一轮没应答的地址重试，connect 1400ms / status 3000ms，并发 96。

机器人稳定后延迟回落到 66~79ms，第二轮基本是空跑。代价是扫描总时长从约 2s 增加到约 4~6s。

### 图传帧率的真实上限

绕过 App 直连 `8081/stream` 测 8.6 秒收到 **14 帧**（约 **1.6 fps**，单帧约 28KB）。
所以 App 上显示的 "1 fps" 是**固件侧**（GC2145 抓帧 + JPEG 编码约 500~600ms/帧）的限制，
不是手机解码慢。想提帧率要改固件（降分辨率 / 走硬件 JPEG），不在本次范围。

### 8081 只能有一个客户端

App 正在预览时，从电脑再连 `8081/stream` 会一直阻塞（httpd 单任务）。
这印证了拍照必须「停流 → 抓图 → 恢复流」的设计。

---

## 十四、画面方向：全部交给设备端（2026-10-29 修订）

### 结论先行

**App 不再对画面做任何图像处理。** 方向完全由设备的 `/rot` 开关控制，
App 只提供一个按钮，直接下发 `GET /rot?on=0|1`。

固件里 `EspVideo::Capture()` 每帧转 180°，LCD 预览、局域网图传、
云端拍照共用同一份已转正的数据 —— 所以**三处方向天然一致**，
不像 App 侧翻转那样会出现「手机上看正了、机器人小屏还是倒的」。

### 演进过程（两次修正）

1. 最初以为传感器倒装、固件加 `RotateFrame180`（默认开）；
2. 后来反馈「画面左右也被颠倒了」，改成 App 侧按位组合翻转
   （`水平翻转 = 设备180°`、`垂直翻转 = 设备180° XOR 想要翻转`），
   代价是 App 要逐帧解码重编码、拍照二次压缩、且与设备屏幕不一致；
3. **最终决定**：去掉 App 侧所有翻转代码，方向交回设备，
   App 只留一个开关按钮。

已删除 `media/ImageOps.kt`、`AppViewModel.flipFlags()`、
SharedPreferences 里的 `flip_vertical` —— 开关状态改由设备 NVS（`rot180`）保存，
换手机、重装 App 都不会丢。

### 现在的实现

| 位置 | 行为 |
|---|---|
| 图传预览 | 原样显示，零处理（解开 JPEG 直接上屏） |
| 拍照存相册 | 抓到的 JPEG 原样写 MediaStore，不重新编码，画质无损 |
| 相机页按钮 | `画面翻转` / `恢复原始方向`，直接调 `/rot?on=` |
| 遥控页按钮 | 同款开关，和「跳舞 d1」并排，不进相机页也能切 |
| 状态角标 | `设备 180°` / `原始方向`（读 `/status` 的 `rot` 字段） |

`/rot` 是幂等写：`on=1` 转正、`on=0` 原始，不带参数则切换。
App 先按当前 `status.rot` 取反再显式下发目标值，避免连点导致状态漂移；
调用期间按钮置灰（`rotBusy`）。

### 验证

| 测试 | 结果 |
|---|---|
| 接口 | `GET /rot?on=0` → `{"rot":false}`，`/status` 同步 `rot:false`；`on=1` → `{"rot":true}` |
| 帧内容 | 两帧直接比对差 **97.6**；把原始帧转 180° 再比只剩 **5.08**（JPEG 噪声级）→ 确认开关确实改的是设备输出 |
| 免重启 | 旋转在 `Capture()` 里逐帧生效，图传不用重连，下一帧即变 |

---

## 十五、版本管理：两个 git 仓库（2026-10-29 建立）

这套东西横跨「上游开源固件」和「我们自己的补丁与客户端」，所以分成两个仓库，各自职责明确：

| 仓库 | 路径 | 分支 | 管什么 |
|---|---|---|---|
| 固件仓库 | `~/esp/xiaozhi-esp32` | **`sparkbot-local`** | 所有 C++/CMake/Kconfig 代码改动、板级 `assets/240_240/*.json`、`local_components/` |
| 工作区仓库 | `~/WorkBuddy/Esp-sparkbot` | `main` | 文档、`firmware/`（补丁脚本与资源留档）、`client/`、`android/` |

固件仓库的 `main` **保持上游原样**（`git log main` 里只有 `78/xiaozhi-esp32` 的提交），
本地改动全部落在 `sparkbot-local` 分支上。这样两件事都成立：

```bash
cd ~/esp/xiaozhi-esp32
git diff main sparkbot-local --stat     # 看清楚"我们到底改了什么"
git diff main sparkbot-local -- main/   # 逐行看代码差异
git checkout main                      # 一秒回到纯上游状态（排查"是不是我们改坏的"）
git checkout sparkbot-local            # 再切回来
```

上游更新时：

```bash
git fetch origin
git rebase origin/main        # 冲突基本只会出现在 main/CMakeLists.txt 与 Kconfig.projbuild
```

### 提交约定

一次逻辑改动一个 commit，message 用「类型(范围): 说明」，正文写清**为什么**改：

```
feat(sparkbot): 表情改为 emote 动画风格
fix(sparkbot): 修 240×240 布局下脸部被裁边
docs: 补充烧录与回滚步骤
```

改动完的顺序固定为：**改代码 → `idf.py build` 通过 → `git commit` → 烧录验证 → 有问题再补一个 commit**。
不要"先烧了再说、成功再补提交"——中间态一旦被覆盖就找不回来。

### 被忽略的东西（故意的）

| 忽略项 | 原因 |
|---|---|
| `sdkconfig` | 上游本来就忽略；本板需要的三项已写进 `boards/espressif/esp-sparkbot/config.json` 的 `sdkconfig_append`，跟着代码走 |
| `*.bak` / `sdkconfig.bak.*` | 手工备份文件，历史交给 git |
| `build/`、`managed_components/`、`*.bin` | 构建产物，几百 MB |
| `android/app/build/`、`*.apk` | 同上 |

`local_components/mqtt/` 是**要入库**的例外：上游组件包含真实测试私钥会被安全策略拦截，
所以换成去掉 test/examples 的本地副本，不入库就 clone 不下来、编不过。

### 回滚

```bash
git checkout -- main/boards/espressif/esp-sparkbot/assets/240_240/layout.json   # 单个文件
git revert <commit>                                                            # 撤销某次提交并留痕
git reset --hard <commit> && idf.py build && idf.py flash                      # 整体回到某个已知可用版本
```

最后一条最实用：任何一个"当时是好的"的 commit，都能原地重建成一个可烧录的固件。

---

## 十六、拍照后在屏幕上回看 5 秒（2026-10-30 实现）

需求：**设备拍照时，屏幕显示刚拍的照片，5 秒后变回表情。**

### 先说踩到的坑：拍照工具被显示风格顺手关掉了

换 emote 风格后，对着设备说「拍张照」没有任何反应。查开机日志：

```
MCP: 15 tools registered: 11 visible to the model, 4 user-only
MCP: Add tool: self.camera.set_camera_flipped      ← 有摄像头相关工具
（没有 self.camera.take_photo）                     ← 但唯独没有拍照
```

摄像头本身是好的（`8081/snapshot` 一直能出图），问题在 `mcp_server.cc`：
`self.camera.take_photo` 的注册代码躺在 `#ifdef HAVE_LVGL` 块里，而
`display.h:7` 是 `#ifndef CONFIG_USE_EMOTE_MESSAGE_STYLE` 才定义 `HAVE_LVGL`。
**开了 emote 就没了这个宏，拍照工具跟着整块消失。**

修法是把摄像头那段移出 `#ifdef`（`set_theme` 依赖 `LvglThemeManager`，仍留在里面）。
修完 `16 tools registered`，`self.camera.take_photo` 回来了。

> 教训：`HAVE_LVGL` 是"显示风格"开关，不是"有没有摄像头"开关。以后往这个块里加东西要小心。

### 怎么把照片显示出来

`EmoteDisplay::SetPreviewImage` 原来是打印一行日志的空实现，而 `EspVideo::Capture()`
拍完确实会调用它，所以以前拍照在屏幕上零反馈。

**不能直接 `esp_lcd_panel_draw_bitmap` 写 panel。** 表情由 emote 的渲染任务逐帧重绘，
绕开它写的像素下一帧就被冲掉；而渲染任务的刷屏等待没有公开的暂停接口，硬抢会把它卡死。

改成 **gfx 的自定义 image 对象**：

| 做法 | 效果 |
|---|---|
| 对象在 `SetPreviewImage` 里懒创建一次 | 后创建 → 排在子对象链末尾 → 后绘制 → 盖在表情上面 |
| 收起时 `gfx_obj_set_visible(obj, false)` | 该对象 `invalidate` 整块区域，`gfx_render_part_area` 先用背景色填、再重画表情层 |
| 5 秒用一次性 `esp_timer` | 连拍时先 `stop` 再 `start`，不会让第一张的计时器提前收掉第二张 |

背景填充这件事有源码依据，不是猜的：

```
gfx_disp.c:52          disp->style.bg_enable = true;        // 默认开
gfx_render.c:223-230   if (disp->style.bg_enable) { ... gfx_sw_blend_fill_area(buf, ..., bg); }
```

emote 初始化只调 `gfx_disp_set_bg_color` 设颜色，没关 `bg_enable`，所以收起照片时
表情没覆盖到的边角也会被刷干净，不留残影。

### 几个实现细节

| 项目 | 做法 |
|---|---|
| 尺寸 | 屏幕是方的、照片不是：**cover** 处理，短边铺满、长边居中裁切，不变形不留黑边 |
| 缓冲 | 240×240×2 = 115KB，优先 PSRAM，首次拍照分配后复用 |
| 格式 | 只画 RGB565（YUV/RGB24/JPEG 在 `esp_video.cc` 里早就转好了），其他格式打警告跳过 |
| 撕裂 | 写像素前若上一张还挂着，先 `visible=false`，否则渲染任务会读到半新半旧 |
| 改时长 | `emote_display.cc` 的 `kPreviewDurationMs`，默认 5000 |

### 实测

```
I (709) MCP: Add tool: self.camera.take_photo      ← 修复后回来了
I (729) MCP: 16 tools registered: 12 visible to the model, 4 user-only
I (3349) EmoteDisplay: SetStatus: 登录服务器...
I (3939) EmoteDisplay: SetEmotion: neutral
```

拍照时会打印：

```
EmoteDisplay: preview: 240x240 cover-cropped from 240x240, shown for 5000 ms
```

### 第二个坑：满屏雪花 = 字节序

第一版烧上去屏上全是雪花。原因不在拷贝逻辑，在**字节序**：

```c
emote_config_t.flags.swap = true;     // 本板 emote 初始化，帧缓冲要翻转过的 RGB565
```

EAF 解码器每吐一个像素都会过一遍 `gfx_color_to_native_u16(color, swap)`，
而 gfx 的**图像**绘制路径（`gfx_sw_blend_img_draw` 无 mask 分支）是裸 `memcpy`，
源码里写着 `src_buf is expected to already be in native framebuffer order` ——
它不替你做转换。摄像头给的是小端 RGB565，直接拷进去就跟帧缓冲反了。

修法是拷贝时翻一次字节：`dst[x] = (px >> 8) | (px << 8);`

> 凡是往 gfx 里塞自己生成的像素，都要先转成 native 顺序。这条对后续加任何 overlay 都适用。

### 顺带纠正一个之前的错判

`8081/snapshot` **会**触发预览：`GetJpegFrame()` 内部先调 `Capture()`，而预览就在
`Capture()` 末尾。所以可以用 `curl http://设备IP:8081/snapshot` 远程触发一次拍照来验证，
不必每次都开口说话。另外抓到的 JPEG 是 **640×480**，而预览帧也是 640×480 → 中心 480×480
裁切再缩到 240×240，不是直接拷。

三种异常的对症改法：

| 现象 | 原因 | 改法 |
|---|---|---|
| 颜色偏蓝/偏红 | RGB565 字节序 | 拷贝时 `__builtin_bswap16` |
| 画面上下颠倒 | 拍照路径的 `rotate180_` 没生效 | 查 `EspVideo::Capture` 的旋转分支 |
| 裁掉太多 | cover 太激进 | 改成留黑边的 fit（改 `crop_w/crop_h` 计算） |

`firmware/emote_style/240_240/layout.json` 也同步了这次 `y=24` 的上移。
