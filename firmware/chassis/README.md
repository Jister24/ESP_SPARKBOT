# 底盘固件改动：让 C2 上报电量

> **2026-11-01 更新：已经刷进底盘并验证。源码独立成仓 → [Jister24/esp-sparkbot-chassis](https://github.com/Jister24/esp-sparkbot-chassis)**
> 那个仓能直接 `idf.py build`，`v1.0.0` Release 里放了实际烧过的 `tracked_chassis.bin`。
> 本目录的补丁保留作历史记录，新改动请去新仓。

底盘主控是 ESP32-C2，官方固件其实一直在采自己的电池，但只拿去控制 RGB 灯，数据不对外。
这里加一条 UART 查询，让主体板（S3）能问出电量和充电状态。

对应上游工程：`gitee.com/esp-friends/esp_sparkbot` → `example/tank/c2_tracked_chassis`
基线 commit：`d42c5b4`

## 改了什么

| 文件 | 改动 |
|---|---|
| `components/power_management/include/power_management.h` | 新增 `battery_voltage_mv()` / `battery_percent()` / `battery_is_charging()` |
| `components/power_management/power_management.c` | 把采样结果存进静态快照（含 0~100 夹取），对外提供读取函数 |
| `main/tracked_chassis.c` | 新增 `b` 命令解析；**去掉原来的原样回显**（主体板开始读 UART 后回显是纯噪声） |

补丁文件：`chassis-battery.patch`，在上游工程目录下 `git apply` 即可。

## 协议

```
主体板 → 底盘：b0
底盘 → 主体板：B:<毫伏>:<百分比>:<充电中>\n     例：B:4035:85:0
```

- 命令字母统一小写，应答用大写 `B` 开头，主体板靠这个区分，不需要转义
- `b0` 走的是原来的 `sscanf("%c%f")` 分支（`b` + 数值 0），不喂运动看门狗，
  所以问电量不会把正在跑的车停住
- 电量百分比沿用官方曲线的线性映射 `(mV - 3300) / 900 × 100`，只做 0~100 夹取

## 用底盘自带的 USB 口刷（2026-11-01 更正）

> 早先这里写的是「USB 口只接了供电、必须飞线到测试点」，**这个判断是错的**，实测底盘 USB 口
> 是完整的数据口，整套刷机流程就是从它跑通的。

C2 芯片本身没有 USB 外设，但底盘 PCB 上**板载了一颗 USB-UART 桥：CH340，`0x1A86:0x7523`**。
USB 口接这颗桥，桥的另一侧接 C2 的 **UART0 控制台（GPIO20/19）**，而且 DTR/RTS 接到了
`EN` / `GPIO9`，带自动下载电路。所以插上一根线就能：

| 用途 | 怎么做 |
|---|---|
| 看启动日志 | 74880 8N1 打开 `/dev/cu.usbserial-XXXX` |
| 进下载模式 | `esptool` 自动拉 EN/BOOT，不用手按也不用飞线 |
| 刷固件 | `idf.py -p /dev/cu.usbserial-XXXX app-flash` |

macOS 26 对 CH340 免驱，`systemextensionsctl list` 空着也照样出端口。**认口看 VID**：
`0x1A86` 是底盘（`usbserial-XXXX`），`0x303A` 是主体板 S3 的原生 USB-Serial-JTAG
（`usbmodemXXXX`）——两根线都插着时会同时出现，别烧错。

4P 磁吸口那边只有 `5V / GND / GPIO48 / GPIO38`（UART1 的 TX/RX），确实没有 `EN` 和 `GPIO9`，
但那不影响刷机：USB 口走的是 UART0 + 自动下载电路，跟磁吸口是两条独立通路。
发运动指令仍然只能走磁吸口，USB 那一侧不解析输入。

## 编译

上游工程是 2024 年的，用 IDF **5.5**（不是主体板那份 6.1）：

```bash
source ~/esp/esp-idf/export.sh
cd example/tank/c2_tracked_chassis
idf.py build
```

用 IDF 6 编译会在 `led_strip 2.5.5` / `led_indicator` 上报缺头文件（`esp_heap_caps.h`、
`driver/ledc.h` 的传递包含在 6.0 被移除了）。

## 烧录（2026-11-01 实测流程）

插底盘的 USB 口即可，不用飞线：

```bash
# 1. 先备份整片，出事能回去
esptool.py -p /dev/cu.usbserial-XXXX -b 115200 read_flash 0 0x200000 chassis_backup.bin

# 2. 只刷 app 分区，不碰 bootloader
idf.py -p /dev/cu.usbserial-XXXX -b 115200 app-flash
```

两个坑：**CH340 在 460800 下会丢字节**（`expected 0x1000 but received 0xffc`），读写都要 115200；
**控制台是 74880 不是 115200**（`CONFIG_ESP_CONSOLE_UART_BAUDRATE`），用 115200 读出来是乱码。

刷完把线拆掉，把底盘装回去。主体板那边只要固件是新的，会自动开始每 5 秒问一次；
`/status` 里出现 `chassis_batt` 就说明通了。刷机时想确认成功，直接在 74880 的控制台上看
有没有 `Tracked-Chassis: Command: b`（那是主体板在轮询）。
