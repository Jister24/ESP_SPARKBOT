#!/usr/bin/env python3
"""把局域网遥控能力注入 xiaozhi-esp32 的 esp-sparkbot 板级代码。

用法:
    python3 apply.py /path/to/xiaozhi-esp32           # 打补丁
    python3 apply.py /path/to/xiaozhi-esp32 --revert  # 回滚
    python3 apply.py /path/to/xiaozhi-esp32 --check   # 只检查不写入

补丁内容:
    1. 新增 local_control.h / local_control.cc 到板级目录（GLOB 自动编译）
    2. Camera 基类增加 GetJpegFrame 虚方法（默认返回 false）
    3. EspVideo 实现 GetJpegFrame
    4. EspSparkBot 构造函数启动本地控制服务
    5. Camera 基类增加 SetRotate180 虚方法（默认 no-op），EspVideo 用软件
       整帧 180° 旋转实现（板载 GC2145 是倒装的，见 B 段补丁）
    6. 新增 chassis_battery.h / chassis_battery.cc：经板间 UART 向底盘 C2 问电量
    7. config.h 增加电池分压宏（GPIO14 / ADC2_CH3，两个 100k 等值电阻 1:1）
    8. EspSparkBot 增加电池采样类、10 秒采样任务，并覆写 Board::GetBatteryLevel
    9. 本地控制服务注入两块电池的 provider，/status 因此多出
       batt / batt_mv / charging 与 chassis_* 三兄弟
   10. 注册 MCP 工具 self.battery.get_status，语音也能问电量

注意: 屏幕右上角的电量角标属于 emote 动画显示方案（main/display/emote_display.cc
里挂的 gfx 子对象），官方默认的 LVGL 显示没有这套结构，所以本补丁不含角标。
电量在 App 与 /status 里都拿得到。

设计原则: 只做插入，不删改原有逻辑；所有改动幂等，可精确回滚。
"""

import argparse
import shutil
import sys
from pathlib import Path

MARK = "LOCAL_CONTROL_PATCH"
BOARD_DIR = "main/boards/espressif/esp-sparkbot"
COMMON_DIR = "main/boards/common"

# 实测板载摄像头为 GC2145（启动日志 PID=0x2145），上游板级 config.json 只启用了 OV2640
GC2145_ANCHOR = '"CONFIG_CAMERA_OV2640_DVP_YUV422_240X240_25FPS=y"'
GC2145_ADDITION = (',\n'
                   '                "CONFIG_CAMERA_GC2145=y",\n'
                   '                "CONFIG_CAMERA_GC2145_AUTO_DETECT_DVP_INTERFACE_SENSOR=y"')

# --------------------------------------------------------------------------
# 各处修改点：(相对路径, 锚点, 插入内容, 放在锚点前还是后, 说明)
# --------------------------------------------------------------------------

CAMERA_H_ANCHOR = "    virtual std::expected<std::string, std::string> Explain(const std::string& question) = 0;"
CAMERA_H_ADDITION = f"""
    // {MARK}: 局域网遥控取图接口。默认实现返回 false，
    // 不支持该能力的板型无需改动。
    virtual bool GetJpegFrame(std::vector<uint8_t>& out) {{
        (void)out;
        return false;
    }}"""

ESP_VIDEO_H_ANCHOR = "    virtual std::expected<std::string, std::string> Explain(const std::string& question) override;"
ESP_VIDEO_H_ADDITION = f"""
    // {MARK}: 抓取一帧并编码为 JPEG，供局域网遥控使用
    virtual bool GetJpegFrame(std::vector<uint8_t>& out) override;"""

ESP_VIDEO_CC_ANCHOR = "bool EspVideo::Capture() {"
ESP_VIDEO_CC_ADDITION = f"""// {MARK}: 本地取图。先 Capture 一把拿到最新帧，再用与云端 拍照 相同的
// image_to_jpeg_cb 编码，保证两条链路走的是同一份画质参数。
bool EspVideo::GetJpegFrame(std::vector<uint8_t>& out) {{
    out.clear();
    if (!Capture()) {{
        ESP_LOGE(TAG, "GetJpegFrame: capture failed");
        return false;
    }}
    if (frame_.data == nullptr || frame_.len == 0) {{
        ESP_LOGE(TAG, "GetJpegFrame: empty frame");
        return false;
    }}
    uint16_t width = frame_.width ? frame_.width : 320;
    uint16_t height = frame_.height ? frame_.height : 240;
    bool ok = image_to_jpeg_cb(
        frame_.data, frame_.len, width, height, frame_.format, 80,
        [](void* arg, size_t index, const void* data, size_t len) -> size_t {{
            auto* buf = static_cast<std::vector<uint8_t>*>(arg);
            if (index == 0 && data != nullptr && len > 0) {{
                buf->assign(static_cast<const uint8_t*>(data),
                            static_cast<const uint8_t*>(data) + len);
            }}
            return len;
        }},
        &out);
    if (!ok) {{
        ESP_LOGE(TAG, "GetJpegFrame: jpeg encode failed");
        out.clear();
        return false;
    }}
    return !out.empty();
}}

"""

BOARD_CC_INCLUDE_ANCHOR = '#include "config.h"'
BOARD_CC_INCLUDE_ADDITION = '''#include "chassis_battery.h"  // LOCAL_CONTROL_PATCH: 底盘电量走 UART
#include "local_control.h"  // LOCAL_CONTROL_PATCH
#include <esp_adc/adc_cali.h>  // LOCAL_CONTROL_PATCH: 电池分压采样
#include <esp_adc/adc_cali_scheme.h>
#include <esp_adc/adc_oneshot.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>'''

# --------------------------------------------------------------------------
# C. 电量：主体板电池 + 底盘电池
#
# 官方固件完全不读电池。主体板的引脚是实测扫出来的：ADC1/ADC2 所有空闲通道
# 逐个采，只有 GPIO14（ADC2_CH3）读数稳定且量级像电池分压——满电插 USB 时
# 引脚 2084 mV，抖动 <10 mV。等值电阻 1:1 分压，电芯电压 = 引脚 × 2。
# 底盘那颗电池在 C2 一侧，只能通过板间 UART 问，见 chassis_battery.cc。
# --------------------------------------------------------------------------

CONFIG_H_INCLUDE_ANCHOR = "#include <driver/uart.h>"
CONFIG_H_INCLUDE_ADDITION = "#include <esp_adc/adc_oneshot.h>  // LOCAL_CONTROL_PATCH"

CONFIG_H_BATTERY_ANCHOR = "#define BUF_SIZE                (1024)"
CONFIG_H_BATTERY_ADDITION = '''

/* LOCAL_CONTROL_PATCH: 主体板电池检测
 * 官方固件不读电池，原理图也没公开，靠实测确定：GPIO14（ADC2_CH3）是唯一
 * 电压稳定、且随电池变化的引脚（满电实测 2084 mV，抖动 <10 mV）。
 * 按两个 100k 等值电阻分压还原，电芯 = 引脚电压 × 2。
 * 注意 ADC2 在 ESP32 上会与 Wi-Fi 冲突，S3 上实测不会，所以不必在 Wi-Fi 前采样。 */
#define BATTERY_ADC_UNIT          ADC_UNIT_2
#define BATTERY_ADC_CHANNEL       ADC_CHANNEL_3
#define BATTERY_UPPER_RESISTOR    (100000.0f)
#define BATTERY_LOWER_RESISTOR    (100000.0f)'''

BOARD_BATTERY_CLASS_ANCHOR = '#define TAG "esp_sparkbot"'
BOARD_BATTERY_CLASS = r'''// ===========================================================================
// LOCAL_CONTROL_PATCH: 电池检测（主体板）
//
// 电量百分比按开路电压曲线插值，充电状态没有硬件指示脚，只能用电压趋势推断。
// ===========================================================================
class SparkBotBattery {
public:
    bool Begin() {
        adc_oneshot_unit_init_cfg_t unit_cfg = {};
        unit_cfg.unit_id = BATTERY_ADC_UNIT;
        unit_cfg.ulp_mode = ADC_ULP_MODE_DISABLE;
        esp_err_t err = adc_oneshot_new_unit(&unit_cfg, &unit_);
        if (err != ESP_OK) {
            ESP_LOGE(kTag, "ADC 单元创建失败: %s", esp_err_to_name(err));
            return false;
        }

        adc_oneshot_chan_cfg_t chan_cfg = {};
        chan_cfg.atten = ADC_ATTEN_DB_12;
        chan_cfg.bitwidth = ADC_BITWIDTH_DEFAULT;
        err = adc_oneshot_config_channel(unit_, BATTERY_ADC_CHANNEL, &chan_cfg);
        if (err != ESP_OK) {
            ESP_LOGE(kTag, "ADC 通道配置失败: %s", esp_err_to_name(err));
            return false;
        }

        // 曲线拟合校准：没有校准表也能出数，只是绝对精度差几十 mV，
        // 对电量百分比影响不大，所以失败不致命。
        adc_cali_curve_fitting_config_t cali_cfg = {};
        cali_cfg.unit_id = BATTERY_ADC_UNIT;
        cali_cfg.atten = ADC_ATTEN_DB_12;
        cali_cfg.bitwidth = ADC_BITWIDTH_DEFAULT;
        if (adc_cali_create_scheme_curve_fitting(&cali_cfg, &cali_) != ESP_OK) {
            cali_ = nullptr;
        }

        ready_ = true;
        ESP_LOGI(kTag, "电池检测就绪，cali=%d", cali_ != nullptr);
        return true;
    }

    bool IsReady() const { return ready_; }

    // 采样一次并更新平滑值。16 次取平均可以把 ±5 LSB 的抖动压到 1 LSB 量级。
    void Update() {
        if (!ready_) {
            return;
        }
        int sum = 0;
        int ok = 0;
        for (int i = 0; i < kSamples; i++) {
            int raw = 0;
            if (adc_oneshot_read(unit_, BATTERY_ADC_CHANNEL, &raw) != ESP_OK) {
                continue;
            }
            sum += raw;
            ok++;
        }
        if (ok == 0) {
            return;
        }
        int pin_mv = sum / ok;
        if (cali_ != nullptr) {
            int mv = 0;
            if (adc_cali_raw_to_voltage(cali_, sum / ok, &mv) == ESP_OK) {
                pin_mv = mv;
            }
        }
        const float cell = pin_mv * (BATTERY_UPPER_RESISTOR + BATTERY_LOWER_RESISTOR) /
                           BATTERY_LOWER_RESISTOR;
        // 一阶低通：充电/放电都慢，没必要让显示跟着负载跳。
        cell_mv_ = (cell_mv_ <= 0) ? static_cast<int>(cell)
                                   : static_cast<int>(cell_mv_ * 0.7f + cell * 0.3f);

        history_[history_index_] = cell_mv_;
        history_index_ = (history_index_ + 1) % kHistory;
        if (history_count_ < kHistory) {
            history_count_++;
        }
    }

    int CellMillivolts() const { return cell_mv_; }

    int Percent() const {
        if (cell_mv_ <= 0) {
            return 0;
        }
        // 锂电开路电压 → 剩余电量，取自 adc_battery_estimation 的默认曲线
        static const struct {
            int mv;
            int pct;
        } kOcv[] = {
            {4160, 100}, {4070, 90}, {3990, 80}, {3900, 70}, {3820, 60}, {3720, 50},
            {3610, 40},  {3530, 30}, {3380, 20}, {3200, 10}, {2850, 0},
        };
        constexpr int kCount = sizeof(kOcv) / sizeof(kOcv[0]);
        if (cell_mv_ >= kOcv[0].mv) {
            return 100;
        }
        for (int i = 0; i < kCount - 1; i++) {
            if (cell_mv_ <= kOcv[i].mv && cell_mv_ >= kOcv[i + 1].mv) {
                const int dv = kOcv[i].mv - kOcv[i + 1].mv;
                const int dp = kOcv[i].pct - kOcv[i + 1].pct;
                return kOcv[i + 1].pct + (cell_mv_ - kOcv[i + 1].mv) * dp / dv;
            }
        }
        return 0;
    }

    // 纯启发式：插着 USB 时电压会被充电电路顶高，3 分钟内上涨超过 12 mV，
    // 或已经贴在满电电压上，就认为在充电。拔掉后电压回落即判定为放电。
    bool IsCharging() const {
        if (cell_mv_ <= 0) {
            return false;
        }
        if (cell_mv_ >= kFullMv - 10) {
            return true;
        }
        if (history_count_ < kHistory) {
            return false;
        }
        // history_index_ 指向下一个要写入的槽位，也就是 3 分钟前那一个样本
        const int oldest = history_[history_index_];
        return cell_mv_ - oldest >= 12;
    }

private:
    static constexpr const char* kTag = "sparkbot_batt";
    static constexpr int kSamples = 16;
    static constexpr int kHistory = 36;  // 5s × 36 = 3 分钟
    static constexpr int kFullMv = 4160;

    adc_oneshot_unit_handle_t unit_ = nullptr;
    adc_cali_handle_t cali_ = nullptr;
    bool ready_ = false;
    int cell_mv_ = -1;
    int history_[kHistory] = {};
    int history_index_ = 0;
    int history_count_ = 0;
};
// ===== BATTERY END =====

'''

BOARD_BATTERY_MEMBER_ANCHOR = "    EspVideo* camera_;"
BOARD_BATTERY_MEMBER = '''    SparkBotBattery battery_;             // LOCAL_CONTROL_PATCH
    ChassisBattery chassis_battery_;      // LOCAL_CONTROL_PATCH: 走 UART 问底盘那颗 C2'''

BOARD_BATTERY_INIT_ANCHOR = "    void InitializeTools() {"
BOARD_BATTERY_INIT = r'''    // LOCAL_CONTROL_PATCH: 采样不必很勤，电量本身变化慢，10 秒一次足够
    void InitializeBattery() {
        // 底盘电量走 UART，与本体电池互不影响，先起起来（读不到就静默降级）
        chassis_battery_.Begin();
        if (!battery_.Begin()) {
            return;
        }
        // 先同步采一次，保证第一个 /status 就有数
        battery_.Update();
        xTaskCreate(
            [](void* arg) {
                auto* self = static_cast<EspSparkBot*>(arg);
                while (true) {
                    vTaskDelay(pdMS_TO_TICKS(10000));
                    self->battery_.Update();
                }
            },
            "battery", 3072, this, 2, nullptr);
    }

'''

BOARD_BATTERY_TOOL_ANCHOR = "        auto& mcp_server = McpServer::GetInstance();"
BOARD_BATTERY_TOOL = r'''
        // LOCAL_CONTROL_PATCH: 语音也能问电量。两块电池分别判断可用性，
        // 读不到就直说读不到，不返回假值。
        mcp_server.AddTool("self.battery.get_status", "获取电池电量和电压（本体与底盘各一块）",
                           PropertyList(),
                           [this](const PropertyList& properties) -> ReturnValue {
                               std::string body;
                               if (battery_.IsReady()) {
                                   battery_.Update();
                                   body = "本体电量 " + std::to_string(battery_.Percent()) +
                                          "%，电压 " + std::to_string(battery_.CellMillivolts()) +
                                          " mV";
                               } else {
                                   body = "本体电池检测不可用";
                               }
                               if (chassis_battery_.IsValid()) {
                                   body += "；底盘电量 " +
                                           std::to_string(chassis_battery_.Percent()) + "%，电压 " +
                                           std::to_string(chassis_battery_.Millivolts()) + " mV" +
                                           (chassis_battery_.IsCharging() ? "（充电中）" : "");
                               } else {
                                   body += "；底盘电量读不到";
                               }
                               return body;
                           });
'''

BOARD_BATTERY_CALL_ANCHOR = "        InitializeEchoUart();"
BOARD_BATTERY_CALL = "\n        InitializeBattery();  // LOCAL_CONTROL_PATCH"

BOARD_GET_BATTERY_LEVEL_ANCHOR = "    virtual Camera* GetCamera() override { return camera_; }"
BOARD_GET_BATTERY_LEVEL = r'''

    // LOCAL_CONTROL_PATCH: 把板级电池读数接进小智自己的电量体系，
    // 电量查询、低电提醒都走这里。
    virtual bool GetBatteryLevel(int& level, bool& charging, bool& discharging) override {
        if (!battery_.IsReady()) {
            return false;
        }
        const bool is_charging = battery_.IsCharging();
        charging = is_charging;
        discharging = !is_charging;
        level = battery_.Percent();
        return true;
    }'''


# --------------------------------------------------------------------------
# B. 图像方向：板载 GC2145 装反了，默认整帧旋转 180°
#
# esp_video / esp_cam_sensor 里 V4L2_CID_HFLIP / VFLIP 都没有实现（grep 全仓无
# 匹配），原有 SetHMirror/SetVFlip 的 ioctl 一定失败；ESP32-S3 也没有 PPA，
# 硬件旋转不可用。所以这里退到软件：180° = 所有像素首尾颠倒，可以就地双指针
# 对换，零额外内存。转正放在 Capture() 里，LCD 预览 / 局域网图传 / 云端拍照
# 三处同时生效，效果一致。
# --------------------------------------------------------------------------

ROTATE_CAMERA_H_ANCHOR = "    virtual bool SetSwapBytes(bool enabled) { return false; }  // Optional, default no-op"
ROTATE_CAMERA_H_ADDITION = f"""
    // {MARK}: 图像方向。板载传感器倒装，固件默认整帧旋转 180°。
    // 不支持软件旋转的板型保持 no-op 即可。
    virtual void SetRotate180(bool enabled) {{ (void)enabled; }}"""

ROTATE_VIDEO_H_ANCHOR = "    virtual bool GetJpegFrame(std::vector<uint8_t>& out) override;"
ROTATE_VIDEO_H_ADDITION = f"""

    // {MARK}: 整帧 180° 旋转（传感器倒装）。纯软件实现，
    // 同时作用于 LCD 预览、局域网图传与云端拍照。
    virtual void SetRotate180(bool enabled) override;

private:
    void RotateFrame180();
    bool rotate180_ = true;

public:"""

ROTATE_VIDEO_CC_IMPL = f"""// {MARK}: 整帧旋转 180°。180° 等于水平镜像 + 垂直镜像，也就是把像素首尾
// 颠倒，可以就地双指针对换，不需要额外缓冲。板载 GC2145 是倒装的，转正放在
// Capture() 里做，LCD 预览 / 局域网图传 / 云端拍照共用同一份已转正的数据。
void EspVideo::SetRotate180(bool enabled) {{
    rotate180_ = enabled;
    ESP_LOGI(TAG, "rotate180 = %d", (int)enabled);
}}

void EspVideo::RotateFrame180() {{
    if (frame_.data == nullptr || frame_.len < 4) {{
        return;
    }}
    switch (frame_.format) {{
        case V4L2_PIX_FMT_RGB565: {{
            auto* pixels = reinterpret_cast<uint16_t*>(frame_.data);
            size_t count = frame_.len / 2;
            for (size_t i = 0, j = count - 1; i < j; ++i, --j) {{
                uint16_t tmp = pixels[i];
                pixels[i] = pixels[j];
                pixels[j] = tmp;
            }}
            break;
        }}
        case V4L2_PIX_FMT_GREY: {{
            uint8_t* bytes = frame_.data;
            for (size_t i = 0, j = frame_.len - 1; i < j; ++i, --j) {{
                uint8_t tmp = bytes[i];
                bytes[i] = bytes[j];
                bytes[j] = tmp;
            }}
            break;
        }}
        case V4L2_PIX_FMT_RGB24: {{
            uint8_t* pixels = frame_.data;
            size_t count = frame_.len / 3;
            for (size_t i = 0, j = count - 1; i < j; ++i, --j) {{
                uint8_t* a = pixels + i * 3;
                uint8_t* b = pixels + j * 3;
                uint8_t r = a[0], g = a[1], bl = a[2];
                a[0] = b[0];
                a[1] = b[1];
                a[2] = b[2];
                b[0] = r;
                b[1] = g;
                b[2] = bl;
            }}
            break;
        }}
        case V4L2_PIX_FMT_YUYV:
        case V4L2_PIX_FMT_UYVY: {{
            // 4 字节一个宏像素（两个像素共用一组色度）。整帧按 4 字节倒排之后，
            // 组内两个 Y 还得对调，否则亮度会左右错开一个采样点。
            const bool yuyv = (frame_.format == V4L2_PIX_FMT_YUYV);
            auto swap_luma = [yuyv](uint32_t v) -> uint32_t {{
                if (yuyv) {{  // 内存布局 Y0 U Y1 V -> 交换第 0、2 字节
                    return (v & 0xFF00FF00u) | ((v & 0x000000FFu) << 16) |
                           ((v >> 16) & 0x000000FFu);
                }}
                // 内存布局 U Y0 V Y1 -> 交换第 1、3 字节
                return (v & 0x00FF00FFu) | ((v & 0x0000FF00u) << 16) |
                       ((v >> 16) & 0x0000FF00u);
            }};
            auto* words = reinterpret_cast<uint32_t*>(frame_.data);
            size_t count = frame_.len / 4;
            if (count == 0) {{
                break;
            }}
            for (size_t i = 0, j = count - 1; i < j; ++i, --j) {{
                uint32_t head = swap_luma(words[i]);
                uint32_t tail = swap_luma(words[j]);
                words[i] = tail;
                words[j] = head;
            }}
            if (count & 1) {{  // 中间那组没有配对元素，只做组内对调
                words[count / 2] = swap_luma(words[count / 2]);
            }}
            break;
        }}
        default:
            ESP_LOGW(TAG, "rotate180: format 0x%08lx not supported, skipped",
                     frame_.format);
            break;
    }}
}}

"""

ROTATE_VIDEO_CC_CALL_ANCHOR = "    // 显示预览图片"
ROTATE_VIDEO_CC_CALL = f"""    // {MARK}: 传感器倒装，预览/编码前先整帧转正。
#if !defined(CONFIG_XIAOZHI_ENABLE_ROTATE_CAMERA_IMAGE)
    if (rotate180_) {{
        RotateFrame180();
    }}
#endif

"""

BOARD_CC_INIT_ANCHOR = "        InitializeTools();"
BOARD_CC_INIT_ADDITION = r'''        // LOCAL_CONTROL_PATCH: 在板级完成 UART 与摄像头初始化后，起局域网遥控服务。
        // lambda 捕获 this 以复用 private 的 SendUartMessage，无需开放可见性。
        // 后两个 provider 让 /status 带上两块电池的读数，读不到就返回 false，
        // 由 local_control 自己省略字段——不给 App 假值。
        LocalControlStart([this](const char* cmd) { SendUartMessage(cmd); },
                          [this](int& cell_mv, int& percent, bool& charging) {
                              if (!battery_.IsReady() || battery_.CellMillivolts() <= 0) {
                                  return false;
                              }
                              cell_mv = battery_.CellMillivolts();
                              percent = battery_.Percent();
                              charging = battery_.IsCharging();
                              return true;
                          },
                          [this](int& cell_mv, int& percent, bool& charging) {
                              if (!chassis_battery_.IsValid()) {
                                  return false;
                              }
                              cell_mv = chassis_battery_.Millivolts();
                              percent = chassis_battery_.Percent();
                              charging = chassis_battery_.IsCharging();
                              return true;
                          });'''


class Patcher:
    def __init__(self, root: Path, check_only: bool):
        self.root = root
        self.check_only = check_only
        self.changes = []
        self.errors = []
        # 同一文件常常要连改好几处（如 esp_video.h 先加声明再加覆写），
        # --check 不落盘时后续锚点就读不到前一步的插入，所以统一走内存缓存。
        self._cache = {}

    def read(self, rel: str) -> str:
        if rel in self._cache:
            return self._cache[rel]
        path = self.root / rel
        if not path.exists():
            self.errors.append(f"文件不存在: {rel}")
            return ""
        return path.read_text(encoding="utf-8")

    def write(self, rel: str, content: str):
        self._cache[rel] = content
        if self.check_only:
            return
        (self.root / rel).write_text(content, encoding="utf-8")

    def insert_after(self, rel: str, anchor: str, addition: str, label: str, marker: str):
        """在 anchor 所在行的下一行插入（保留原缩进结构）。"""
        text = self.read(rel)
        if not text:
            return
        if marker in text:
            self.changes.append(f"跳过（已打过）: {label}")
            return
        if anchor not in text:
            self.errors.append(f"锚点未找到 [{label}]，固件版本可能不一致: {rel}")
            return
        idx = text.index(anchor) + len(anchor)
        new_text = text[:idx] + "\n" + addition + text[idx:]
        self.write(rel, new_text)
        self.changes.append(f"已修改: {label}")

    def insert_before(self, rel: str, anchor: str, addition: str, label: str, marker: str):
        text = self.read(rel)
        if not text:
            return
        if marker in text:
            self.changes.append(f"跳过（已打过）: {label}")
            return
        if anchor not in text:
            self.errors.append(f"锚点未找到 [{label}]: {rel}")
            return
        idx = text.index(anchor)
        new_text = text[:idx] + addition + text[idx:]
        self.write(rel, new_text)
        self.changes.append(f"已修改: {label}")

    def add_include(self, rel: str, anchor: str, addition: str, label: str,
                    marker=None):
        text = self.read(rel)
        if not text:
            return
        # 多行 include 块用 marker 判断是否已插入，避免旧版单行补丁又被插一次
        if (marker or addition) in text:
            self.changes.append(f"跳过（已打过）: {label}")
            return
        if anchor not in text:
            self.errors.append(f"锚点未找到 [{label}]: {rel}")
            return
        new_text = text.replace(anchor, anchor + "\n" + addition, 1)
        self.write(rel, new_text)
        self.changes.append(f"已修改: {label}")

    def enable_gc2145(self):
        """实测板载摄像头为 GC2145（PID 0x2145），但上游板级配置只启用 OV2640。

        esp_cam_sensor 里 gc2145 驱动是现成的，只是没被 Kconfig 选中，
        不启用的话 esp_video 初始化时找不到任何支持的 sensor，图传和小智拍照都会失效。
        """
        rel = f"{BOARD_DIR}/config.json"
        text = self.read(rel)
        if not text:
            return
        if "CONFIG_CAMERA_GC2145=y" in text:
            self.changes.append("跳过（已打过）: config.json 启用 GC2145")
            return
        if GC2145_ANCHOR not in text:
            self.errors.append(f"锚点未找到 [config.json 启用 GC2145]: {rel}")
            return
        idx = text.index(GC2145_ANCHOR) + len(GC2145_ANCHOR)
        new_text = text[:idx] + GC2145_ADDITION + text[idx:]
        self.write(rel, new_text)
        self.changes.append("已修改: config.json 启用 GC2145 驱动")

    def copy_module(self):
        for name in ("local_control.h", "local_control.cc",
                     "chassis_battery.h", "chassis_battery.cc"):
            src = Path(__file__).parent / name
            dst = self.root / BOARD_DIR / name
            if not src.exists():
                self.errors.append(f"缺少补丁源文件: {name}")
                continue
            if dst.exists() and dst.read_text(encoding="utf-8") == src.read_text(encoding="utf-8"):
                self.changes.append(f"跳过（已存在）: {name}")
                continue
            if not self.check_only:
                shutil.copy2(src, dst)
            self.changes.append(f"已拷贝: {BOARD_DIR}/{name}")

    def run(self):
        board_cc = f"{BOARD_DIR}/esp_sparkbot_board.cc"
        config_h = f"{BOARD_DIR}/config.h"
        self.add_include(board_cc, BOARD_CC_INCLUDE_ANCHOR, BOARD_CC_INCLUDE_ADDITION,
                         "board.cc include local_control.h", marker="chassis_battery.h")
        self.insert_after(board_cc, BOARD_CC_INIT_ANCHOR, BOARD_CC_INIT_ADDITION,
                          "board.cc 启动本地服务", "LocalControlStart(")

        # 电量：config.h 的引脚宏 → board.cc 的采样类/成员/初始化/MCP 工具
        self.add_include(config_h, CONFIG_H_INCLUDE_ANCHOR, CONFIG_H_INCLUDE_ADDITION,
                         "config.h include adc_oneshot", marker="adc_oneshot.h")
        self.insert_after(config_h, CONFIG_H_BATTERY_ANCHOR, CONFIG_H_BATTERY_ADDITION,
                          "config.h 电池分压宏", "BATTERY_ADC_UNIT")
        self.insert_before(board_cc, BOARD_BATTERY_CLASS_ANCHOR, BOARD_BATTERY_CLASS,
                           "board.cc 电池采样类", "class SparkBotBattery")
        self.insert_after(board_cc, BOARD_BATTERY_MEMBER_ANCHOR, BOARD_BATTERY_MEMBER,
                          "board.cc 电池成员", "SparkBotBattery battery_;")
        self.insert_before(board_cc, BOARD_BATTERY_INIT_ANCHOR, BOARD_BATTERY_INIT,
                           "board.cc 电池初始化", "void InitializeBattery()")
        self.insert_after(board_cc, BOARD_BATTERY_TOOL_ANCHOR, BOARD_BATTERY_TOOL,
                          "board.cc MCP 电量工具", "self.battery.get_status")
        self.insert_after(board_cc, BOARD_BATTERY_CALL_ANCHOR, BOARD_BATTERY_CALL,
                          "board.cc 调用电池初始化", "InitializeBattery();")
        self.insert_after(board_cc, BOARD_GET_BATTERY_LEVEL_ANCHOR, BOARD_GET_BATTERY_LEVEL,
                          "board.cc GetBatteryLevel 覆写", "GetBatteryLevel(int& level")

        self.add_include(f"{COMMON_DIR}/camera.h", "#include <string>",
                         "#include <vector>  // " + MARK,
                         "camera.h include <vector>")
        self.insert_after(f"{COMMON_DIR}/camera.h", CAMERA_H_ANCHOR, CAMERA_H_ADDITION,
                          "camera.h GetJpegFrame 接口", "virtual bool GetJpegFrame")

        self.insert_after(f"{COMMON_DIR}/esp_video.h", ESP_VIDEO_H_ANCHOR,
                          ESP_VIDEO_H_ADDITION, "esp_video.h 声明",
                          "virtual bool GetJpegFrame(std::vector<uint8_t>& out) override;")
        self.insert_before(f"{COMMON_DIR}/esp_video.cc", ESP_VIDEO_CC_ANCHOR,
                           ESP_VIDEO_CC_ADDITION, "esp_video.cc 实现",
                           "bool EspVideo::GetJpegFrame")

        # 图像方向（180° 转正）
        self.insert_after(f"{COMMON_DIR}/camera.h", ROTATE_CAMERA_H_ANCHOR,
                          ROTATE_CAMERA_H_ADDITION, "camera.h SetRotate180 接口",
                          "void SetRotate180(bool enabled)")
        self.insert_after(f"{COMMON_DIR}/esp_video.h", ROTATE_VIDEO_H_ANCHOR,
                          ROTATE_VIDEO_H_ADDITION, "esp_video.h SetRotate180 声明",
                          "virtual void SetRotate180(bool enabled) override;")
        self.insert_before(f"{COMMON_DIR}/esp_video.cc", ROTATE_VIDEO_CC_CALL_ANCHOR,
                           ROTATE_VIDEO_CC_CALL, "esp_video.cc 每帧转正",
                           "if (rotate180_) {")
        self.insert_before(f"{COMMON_DIR}/esp_video.cc", ESP_VIDEO_CC_ANCHOR,
                           ROTATE_VIDEO_CC_IMPL, "esp_video.cc 旋转实现",
                           "void EspVideo::RotateFrame180() {")

        self.copy_module()
        self.enable_gc2145()


def revert(root: Path):
    """从 .bak 恢复被改过的文件，并删除新增模块。"""
    targets = [
        f"{BOARD_DIR}/esp_sparkbot_board.cc",
        f"{BOARD_DIR}/config.h",
        f"{COMMON_DIR}/camera.h",
        f"{COMMON_DIR}/esp_video.h",
        f"{COMMON_DIR}/esp_video.cc",
        f"{BOARD_DIR}/config.json",
    ]
    for rel in targets:
        bak = root / (rel + ".bak")
        if bak.exists():
            shutil.move(str(bak), str(root / rel))
            print(f"已恢复: {rel}")
        else:
            print(f"无备份，跳过: {rel}")
    for name in ("local_control.h", "local_control.cc",
                 "chassis_battery.h", "chassis_battery.cc"):
        path = root / BOARD_DIR / name
        if path.exists():
            path.unlink()
            print(f"已删除: {BOARD_DIR}/{name}")


def main():
    parser = argparse.ArgumentParser(description="为 xiaozhi-esp32 esp-sparkbot 注入局域网遥控")
    parser.add_argument("repo", help="xiaozhi-esp32 仓库根目录")
    parser.add_argument("--revert", action="store_true", help="回滚改动")
    parser.add_argument("--check", action="store_true", help="只检查锚点，不写文件")
    args = parser.parse_args()

    root = Path(args.repo).expanduser().resolve()
    if not (root / "main" / "CMakeLists.txt").exists():
        sys.exit(f"看起来不是 xiaozhi-esp32 仓库根目录: {root}")

    if args.revert:
        revert(root)
        return

    # 先备份，便于回滚
    if not args.check:
        for rel in [f"{BOARD_DIR}/esp_sparkbot_board.cc", f"{BOARD_DIR}/config.h",
                    f"{COMMON_DIR}/camera.h", f"{COMMON_DIR}/esp_video.h",
                    f"{COMMON_DIR}/esp_video.cc", f"{BOARD_DIR}/config.json"]:
            bak = root / (rel + ".bak")
            src = root / rel
            if src.exists() and not bak.exists():
                shutil.copy2(src, bak)

    patcher = Patcher(root, args.check)
    patcher.run()

    for line in patcher.changes:
        print(line)
    if patcher.errors:
        print("\n失败:")
        for err in patcher.errors:
            print("  " + err)
        sys.exit(1)

    print("\n补丁完成。下一步:")
    print("  cd", root)
    print("  idf.py set-target esp32s3")
    print("  idf.py build")
    print("  idf.py -p /dev/cu.usbmodem* flash monitor")
    print("\n注意: 板级源文件用 file(GLOB) 收集，新增 .cc 后务必重新 configure")
    print("（idf.py build 会自动重跑；若未生效，删掉 build/ 再编译）")


if __name__ == "__main__":
    main()
