#!/usr/bin/env python3
"""ESP-SparkBot 六轴 IMU 标定工具。

配合固件里的 bmi270_imu.cc 使用。设备在 8080 端口暴露两个端点：
    GET /imu       当前读数（JSON）
    GET /imu/zero  把当前姿态记为零点

两种用法：
    python3 firmware/tools/imu_calib.py -H 192.168.1.23           # 实时看倾斜
    python3 firmware/tools/imu_calib.py -H 192.168.1.23 --calib   # 引导式标定

--calib 会带你朝前/后/左/右各倾一次，最后算出一张 2×2 映射表。这步不能省：
板子上 BMI270 的贴片方向和机器人朝向不一定一致，不实测很容易出现
「往左倾、球往右滚」——而且这种错在代码里看着完全合理，只能靠测量发现。

只依赖标准库。设备地址也可以用环境变量 SPARKBOT_HOST 给。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

# 局域网直连必须绕开系统代理，否则请求会被 http_proxy 拦走
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PORT = 8080


def fetch(host: str, path: str, timeout: float = 2.0) -> dict:
    url = f"http://{host}:{PORT}{path}"
    with _OPENER.open(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


def bar(value: float, full: float = 30.0, width: int = 21) -> str:
    """把倾角画成条状，中心是零点，左负右正。终端里比纯数字直观得多。"""
    mid = width // 2
    norm = max(-1.0, min(1.0, value / full))
    mag = round(abs(norm) * mid)
    cells = []
    for i in range(width):
        if i == mid:
            cells.append("|")
        elif i < mid:
            cells.append("<" if value < 0 and (mid - i) <= mag else ".")
        else:
            cells.append(">" if value > 0 and (i - mid) <= mag else ".")
    return "".join(cells)


def sample(host: str, seconds: float = 2.0) -> dict | None:
    """稳定采样一段时间取平均，压掉手持抖动和单帧噪声。"""
    rows = []
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            d = fetch(host, "/imu")
            if d.get("ready"):
                rows.append(d)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.05)
    if not rows:
        return None
    n = len(rows)
    return {
        "tilt_x": sum(r["tilt_x"] for r in rows) / n,
        "tilt_y": sum(r["tilt_y"] for r in rows) / n,
        "roll": sum(r["roll"] for r in rows) / n,
        "pitch": sum(r["pitch"] for r in rows) / n,
        "n": n,
    }


def watch(host: str) -> int:
    print("实时倾斜（Ctrl-C 退出）。把机器人往各个方向倾一下，看哪根条跟着动。\n")
    try:
        while True:
            d = fetch(host, "/imu")
            if not d.get("ready"):
                print(f"\r  未就绪: {d}", end="", flush=True)
                time.sleep(0.3)
                continue
            tx, ty = d["tilt_x"], d["tilt_y"]
            ax, ay, az = d["acc_g"]
            print(
                f"\r x {tx:+6.1f} [{bar(tx)}]  y {ty:+6.1f} [{bar(ty)}]  "
                f"a[{ax:+.2f} {ay:+.2f} {az:+.2f}]g  # {d['seq']:5d}",
                end="",
                flush=True,
            )
            time.sleep(0.08)
    except KeyboardInterrupt:
        print("\n")
        return 0


def _prompt(msg: str) -> None:
    print(msg)
    try:
        input("   准备好后按回车… ")
    except EOFError:
        pass


def calibrate(host: str) -> int:
    print("=" * 62)
    print("六轴倾斜标定")
    print("=" * 62)
    print("接下来会让你把机器人朝 4 个方向倾斜，每个方向采 2 秒。")
    print("每次大约 20°，不用特别精确，方向对了就行。\n")

    base = sample(host, 1.5)
    if base is None:
        print("✗ 采不到数据。先确认 /imu 能通：curl http://%s:8080/imu" % host)
        return 1
    print(f"   平放时 tilt_x={base['tilt_x']:+.1f}° tilt_y={base['tilt_y']:+.1f}°")

    # 先把当前姿态定成零点，后面四个方向都相对它算
    try:
        fetch(host, "/imu/zero")
        print("   ✓ 已把当前姿态记为零点\n")
    except (urllib.error.URLError, TimeoutError):
        print("   ! /imu/zero 调用失败，继续用平放值做基准\n")

    dirs = [
        ("forward", "把机器人**朝前**倾约 20°（车头朝下压）"),
        ("back", "把机器人**朝后**倾约 20°（车尾朝下压）"),
        ("left", "把机器人**朝左**倾约 20°（左侧压低）"),
        ("right", "把机器人**朝右**倾约 20°（右侧压低）"),
    ]
    got: dict[str, dict] = {}
    for key, desc in dirs:
        _prompt(f"→ {desc}")
        s = sample(host, 2.0)
        if s is None:
            print("   ✗ 这轮没采到数据，跳过")
            continue
        got[key] = s
        print(f"   x={s['tilt_x']:+.1f}°  y={s['tilt_y']:+.1f}°  ({s['n']} 帧)")

    if len(got) < 4:
        print("\n✗ 方向没采全，重跑一次。")
        return 1

    # 前后相减得到「前进」在传感器平面上的方向，左右相减同理。
    # 这么做而不是单看去程，是因为来回相减能自动抵消零点残余偏移。
    fx = (got["forward"]["tilt_x"] - got["back"]["tilt_x"]) / 2.0
    fy = (got["forward"]["tilt_y"] - got["back"]["tilt_y"]) / 2.0
    rx = (got["right"]["tilt_x"] - got["left"]["tilt_x"]) / 2.0
    ry = (got["right"]["tilt_y"] - got["left"]["tilt_y"]) / 2.0

    fmag = (fx * fx + fy * fy) ** 0.5
    rmag = (rx * rx + ry * ry) ** 0.5

    print("\n" + "-" * 62)
    if fmag < 5 or rmag < 5:
        print("✗ 某个方向几乎没变化（幅度 < 5°），可能没倾到位或轴被认错。")
        print(f"  forward 幅度 {fmag:.1f}°，right 幅度 {rmag:.1f}°")
        return 1

    # 正交性检查：前后和左右在传感器平面上应该近似垂直。
    dot = (fx * rx + fy * ry) / (fmag * rmag + 1e-6)
    print("映射向量（单位：度，对应你实际倾斜 20° 时的读数）")
    print(f"  前进方向  forward = ({fx:+7.2f}, {fy:+7.2f})   幅度 {fmag:.1f}°")
    print(f"  右转方向  right   = ({rx:+7.2f}, {ry:+7.2f})   幅度 {rmag:.1f}°")
    print(f"  正交性 cos = {dot:+.3f}", end="")
    if abs(dot) < 0.2:
        print("  ✓ 合格")
    else:
        print("  ⚠ 偏差偏大，建议重测一次（可能倾的方向不纯）")

    print("\n把下面这张表贴进游戏代码（tilt 是 /imu 给的 tilt_x/tilt_y）：")
    print("```")
    print(f"# 每 20° 倾斜对应 1.0 的操作量，超过就饱和")
    print(f"FWD = ({fx:.3f}f, {fy:.3f}f)")
    print(f"RGT = ({rx:.3f}f, {ry:.3f}f)")
    print(f"FWD_N2 = {fmag * fmag:.1f}f   # |FWD|²")
    print(f"RGT_N2 = {rmag * rmag:.1f}f   # |RGT|²")
    print("")
    print("# 投影到两个方向再归一化，得到迷宫球的加速度（-1 ~ +1）")
    print("ay = clamp((tilt_x*FWD[0] + tilt_y*FWD[1]) / FWD_N2 * 20.0, -1, 1)")
    print("ax = clamp((tilt_x*RGT[0] + tilt_y*RGT[1]) / RGT_N2 * 20.0, -1, 1)")
    print("```")
    print("-" * 62)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(
        description="ESP-SparkBot 六轴 IMU 标定",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument(
        "-H",
        "--host",
        default=os.environ.get("SPARKBOT_HOST", ""),
        help="设备 IP（默认读环境变量 SPARKBOT_HOST）",
    )
    ap.add_argument("--calib", action="store_true", help="引导式四方向标定")
    ap.add_argument("--status", action="store_true", help="只打印一次原始 JSON")
    args = ap.parse_args()

    if not args.host:
        print("需要设备地址：-H 192.168.x.x 或设置 SPARKBOT_HOST", file=sys.stderr)
        return 2

    try:
        if args.status:
            print(json.dumps(fetch(args.host, "/imu"), ensure_ascii=False, indent=2))
            return 0
        return calibrate(args.host) if args.calib else watch(args.host)
    except urllib.error.URLError as exc:
        print(f"\n连不上 {args.host}:{PORT} — {exc}", file=sys.stderr)
        print("检查：设备在同一网段？/status 能通？固件烧的是带 IMU 的版本？",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
