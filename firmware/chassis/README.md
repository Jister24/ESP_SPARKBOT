# 底盘固件改动：让 C2 上报电量

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

## 为什么不能用底盘上的 USB 口刷

底盘那颗是 ESP32-C2，**芯片本身没有 USB 外设**。PCB 上的 USB 口实测只接供电，
插上后 Mac 的 USB 树里没有任何新设备（换过数据线也一样），所以它不是下载口。

底盘与主体板之间的 4P 磁吸接口只有 `5V / GND / GPIO48 / GPIO38`，也就是 UART 的
TX/RX，没有 `EN` 和 `GPIO9(BOOT)`，进不了 ROM 下载模式。

要刷这个固件，需要在**底盘 PCB 上找 C2 的 UART0、EN、GPIO9 测试点**，用 3.3V 的
USB-TTL 接上去烧；或者临时把这几根线引出来。

## 编译

上游工程是 2024 年的，用 IDF **5.5**（不是主体板那份 6.1）：

```bash
source ~/esp/esp-idf/export.sh
cd example/tank/c2_tracked_chassis
idf.py build
```

用 IDF 6 编译会在 `led_strip 2.5.5` / `led_indicator` 上报缺头文件（`esp_heap_caps.h`、
`driver/ledc.h` 的传递包含在 6.0 被移除了）。

## 烧录（需要先接出测试点）

```bash
idf.py -p /dev/cu.usbserial-XXXX flash
```

刷完把线拆掉，把底盘装回去。主体板那边只要固件是新的，会自动开始每 5 秒问一次；
`/status` 里出现 `chassis_batt` 就说明通了。
