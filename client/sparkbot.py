#!/usr/bin/env python3
"""ESP-SparkBot 局域网控制客户端。

配合固件补丁 local_control.cc 使用：
    8080 端口 — 控制（/move /stop /cmd /status）
    8081 端口 — 图像（/stream /snapshot）

用法:
    python3 sparkbot.py status                     查看状态
    python3 sparkbot.py drive                      键盘遥控（推荐）
    python3 sparkbot.py move forward --speed 0.5   单步移动
    python3 sparkbot.py stop                       急停
    python3 sparkbot.py snap shot.jpg              抓一张图
    python3 sparkbot.py watch live.jpg             持续接收图传，每帧覆盖写入
    python3 sparkbot.py watch live.jpg -n 30       只收 30 帧
    python3 sparkbot.py rot                        切换画面方向（默认已转正 180°）
    python3 sparkbot.py rot on|off                 显式指定画面方向，写 NVS 掉电保存

设备地址默认从环境变量 SPARKBOT_HOST 或参数 -H 读取。
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

CTRL_PORT = 8080
CAM_PORT = 8081

# 局域网直连必须绕开系统代理，否则请求会被 http_proxy 拦走
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

MOVES = {
    "forward": (0.0, 1.0),
    "back": (0.0, -1.0),
    "left": (-1.0, 0.0),
    "right": (1.0, 0.0),
}


def _url(host: str, path: str, port: int = CTRL_PORT) -> str:
    return f"http://{host}:{port}{path}"


def call(host: str, path: str, timeout: float = 3.0, port: int = CTRL_PORT) -> str:
    with _OPENER.open(_url(host, path, port), timeout=timeout) as resp:
        return resp.read().decode("utf-8", "replace")


def move(host: str, x: float, y: float, timeout: float = 3.0) -> str:
    return call(host, f"/move?x={x:.2f}&y={y:.2f}", timeout)


def hold(host: str, x: float, y: float, seconds: float, period: float = 0.2) -> None:
    """持续走一段时间。

    底盘 500ms 收不到 x/y 指令就自动停车（C2 侧看门狗），所以按住期间必须
    每 200ms 重发一次，否则一次指令最多走半秒。
    """
    deadline = time.time() + seconds
    while time.time() < deadline:
        move(host, x, y)
        time.sleep(period)
    stop(host)


def stop(host: str, timeout: float = 3.0) -> str:
    return call(host, "/stop", timeout)


def status(host: str) -> dict:
    return json.loads(call(host, "/status"))


def rotate(host: str, on=None, timeout: float = 3.0) -> dict:
    """画面方向开关。on=None 表示切换。"""
    path = "/rot" if on is None else f"/rot?on={1 if on else 0}"
    return json.loads(call(host, path, timeout))


def snapshot(host: str, out_path: str, timeout: float = 8.0) -> int:
    with _OPENER.open(_url(host, "/snapshot", CAM_PORT), timeout=timeout) as resp:
        data = resp.read()
    if not data.startswith(b"\xff\xd8"):
        raise RuntimeError("返回内容不是 JPEG，检查摄像头是否初始化")
    with open(out_path, "wb") as f:
        f.write(data)
    return len(data)


def watch(host: str, out_path: str, count: int = 0, timeout: float = 10.0) -> None:
    """持续读取 MJPEG 流，按 JPEG 的 SOI/EOI 标记切帧（比解析 boundary 稳）。"""
    with _OPENER.open(_url(host, "/stream", CAM_PORT), timeout=timeout) as resp:
        buf = b""
        got = 0
        last_report = time.time()
        while True:
            chunk = resp.read(8192)
            if not chunk:
                break
            buf += chunk
            # 丢弃已消费的历史标记
            while True:
                start = buf.find(b"\xff\xd8")
                end = buf.find(b"\xff\xd9", start + 2) if start >= 0 else -1
                if start < 0 or end < 0:
                    # 防止缓冲区无限增长
                    if len(buf) > 4 * 1024 * 1024:
                        buf = buf[-2:]
                    break
                jpeg = buf[start:end + 2]
                buf = buf[end + 2:]
                with open(out_path, "wb") as f:
                    f.write(jpeg)
                got += 1
                if count and got >= count:
                    return
                now = time.time()
                if now - last_report >= 1.0:
                    last_report = now
                    print(f"\r已接收 {got} 帧 · 最新帧 {len(jpeg) // 1024}KB", end="", flush=True)


# --------------------------------------------------------------------------
# 键盘遥控
# --------------------------------------------------------------------------

def _read_key() -> str:
    """读一个逻辑按键，方向键被归一化成 up/down/left/right。"""
    import termios
    import tty

    fd = sys.stdin.fileno()
    old = termios.tcgetattr(fd)
    try:
        tty.setcbreak(fd)
        ch = sys.stdin.read(1)
        if ch == "\x1b":
            seq = sys.stdin.read(1)
            if seq == "[":
                code = sys.stdin.read(1)
                return {"A": "up", "B": "down", "C": "right", "D": "left"}.get(code, "")
            return ""
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, old)

    if ch in ("w", "W"):
        return "up"
    if ch in ("s", "S"):
        return "down"
    if ch in ("a", "A"):
        return "left"
    if ch in ("d", "D"):
        return "right"
    return ch.lower()


KEY_TO_VECTOR = {
    "up": (0.0, 1.0),
    "down": (0.0, -1.0),
    "left": (-1.0, 0.0),
    "right": (1.0, 0.0),
}


def drive(host: str, speed: float, pulse: float) -> None:
    print(f"ESP-SparkBot 键盘遥控 · {host}")
    print("  W/A/S/D 或方向键 = 移动（单次脉冲 %.2fs）" % pulse)
    print("  +/- 调速  空格 急停  0 跳舞  L 灯光  q 退出\n")

    try:
        info = status(host)
        print(f"已连接 · IP {info.get('ip')} · 已运行 {info.get('uptime_s', 0)}s\n")
    except Exception as exc:  # noqa: BLE001
        print(f"连接失败: {exc}\n请确认设备与电脑同一局域网、固件已patch并重启。")
        return

    while True:
        print(f"\r速度 {int(speed * 100):3d}%  > ", end="", flush=True)
        try:
            key = _read_key()
        except KeyboardInterrupt:
            break

        if key in ("q", "\x03"):
            break
        if key in KEY_TO_VECTOR:
            x, y = KEY_TO_VECTOR[key]
            try:
                hold(host, x * speed, y * speed, pulse)
            except Exception as exc:  # noqa: BLE001
                print(f"\n指令失败: {exc}")
                break
            continue
        if key == " ":
            stop(host)
            continue
        if key in ("+", "="):
            speed = min(1.0, round(speed + 0.1, 2))
            continue
        if key in ("-", "_"):
            speed = max(0.1, round(speed - 0.1, 2))
            continue
        if key == "0":
            call(host, "/cmd?c=d1")
            continue
        if key == "l":
            call(host, "/cmd?c=w2")
            continue

    try:
        stop(host)
    except Exception:  # noqa: BLE001
        pass
    print("\n已停止，退出。")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="ESP-SparkBot 局域网控制客户端",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("command",
                        choices=["status", "drive", "move", "stop", "snap",
                                 "watch", "raw", "rot"],
                        help="要执行的动作")
    parser.add_argument("target", nargs="?", default="",
                        help="move 的方向名 / snap 的输出文件 / watch 的输出文件 / "
                             "raw 的指令 / rot 的 on|off")
    parser.add_argument("-H", "--host",
                        default=os.environ.get("SPARKBOT_HOST", "192.168.1.50"),
                        help="设备 IP，默认读 SPARKBOT_HOST")
    parser.add_argument("--speed", type=float, default=0.6,
                        help="移动速度 0.1~1.0，默认 0.6")
    parser.add_argument("--pulse", type=float, default=0.35,
                        help="键盘遥控单次移动时长（期间每 200ms 重发，可超过底盘 500ms 看门狗）")
    parser.add_argument("-n", "--frames", type=int, default=0,
                        help="watch 模式接收帧数上限，0 表示一直收")
    args = parser.parse_args()

    host = args.host
    try:
        if args.command == "status":
            info = status(host)
            print(json.dumps(info, ensure_ascii=False, indent=2))
        elif args.command == "drive":
            drive(host, args.speed, args.pulse)
        elif args.command == "move":
            name = args.target or "forward"
            if name not in MOVES:
                sys.exit(f"方向只能是: {', '.join(MOVES)}")
            x, y = MOVES[name]
            print(move(host, x * args.speed, y * args.speed))
        elif args.command == "stop":
            print(stop(host))
        elif args.command == "raw":
            if not args.target:
                sys.exit("raw 需要指令，例如: raw 'd1'")
            print(call(host, f"/cmd?c={urllib.parse.quote(args.target)}"))
        elif args.command == "rot":
            arg = (args.target or "").strip().lower()
            on = None if arg in ("", "toggle") else arg in ("on", "1", "true", "yes")
            result = rotate(host, on)
            print("画面方向:", "已转正 180°" if result.get("rot") else "原始（未旋转）")
        elif args.command == "snap":
            out = args.target or "snapshot.jpg"
            size = snapshot(host, out)
            print(f"已保存 {out}（{size // 1024}KB）")
        elif args.command == "watch":
            out = args.target or "live.jpg"
            print(f"接收图传，每帧覆盖写入 {out}（Ctrl+C 结束）")
            try:
                watch(host, out, args.frames)
            except KeyboardInterrupt:
                pass
            print("\n结束。")
    except urllib.error.URLError as exc:
        sys.exit(f"无法连接 {host}: {exc.reason}\n"
                 f"排查: 设备是否在线、固件是否已打补丁并重启、防火墙是否放行 8080/8081")
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
