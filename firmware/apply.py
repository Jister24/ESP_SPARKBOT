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
BOARD_CC_INCLUDE_ADDITION = f'#include "local_control.h"  // {MARK}'

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
BOARD_CC_INIT_ADDITION = f"""        // {MARK}: 在板级完成 UART 与摄像头初始化后，起局域网遥控服务。
        // lambda 捕获 this 以复用 private 的 SendUartMessage，无需开放可见性。
        LocalControlStart([this](const char* cmd) {{ SendUartMessage(cmd); }});"""


class Patcher:
    def __init__(self, root: Path, check_only: bool):
        self.root = root
        self.check_only = check_only
        self.changes = []
        self.errors = []

    def read(self, rel: str) -> str:
        path = self.root / rel
        if not path.exists():
            self.errors.append(f"文件不存在: {rel}")
            return ""
        return path.read_text(encoding="utf-8")

    def write(self, rel: str, content: str):
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

    def add_include(self, rel: str, anchor: str, addition: str, label: str):
        text = self.read(rel)
        if not text:
            return
        if addition in text:
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
        for name in ("local_control.h", "local_control.cc"):
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
        self.add_include(board_cc, BOARD_CC_INCLUDE_ANCHOR, BOARD_CC_INCLUDE_ADDITION,
                         "board.cc include local_control.h")
        self.insert_after(board_cc, BOARD_CC_INIT_ANCHOR, BOARD_CC_INIT_ADDITION,
                          "board.cc 启动本地服务", "LocalControlStart(")

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
    for name in ("local_control.h", "local_control.cc"):
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
        for rel in [f"{BOARD_DIR}/esp_sparkbot_board.cc", f"{COMMON_DIR}/camera.h",
                    f"{COMMON_DIR}/esp_video.h", f"{COMMON_DIR}/esp_video.cc",
                    f"{BOARD_DIR}/config.json"]:
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
