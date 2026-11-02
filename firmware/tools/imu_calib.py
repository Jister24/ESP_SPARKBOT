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

# 标定结果落盘的位置，--stick 会读这份来实时验证
MAP_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tilt_map.json")


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


def _unwrap(prev: float, cur: float) -> float:
    """把 yaw 的跳变折回 ±180，否则跨零点时会算出巨大的假漂移。"""
    d = cur - prev
    while d > 180.0:
        d -= 360.0
    while d < -180.0:
        d += 360.0
    return d


def drift(host: str, seconds: float) -> int:
    """静止漂移测量。

    这是「要不要上视觉方案」的唯一依据：
      yaw 只能靠陀螺积分，加速度计帮不上忙（没有磁力计），
      所以零偏会 1:1 变成每秒的指向误差。
    同时用 acc 的方差判断设备到底有没有动——动了这次测量就作废。
    """
    print(f"静置测量 {seconds:.0f} 秒，期间不要碰设备…\n")
    rows: list[tuple[float, dict]] = []
    t0 = time.time()
    while time.time() - t0 < seconds:
        left = seconds - (time.time() - t0)
        print(f"\r  采样中 {left:4.0f}s  已收 {len(rows):4d} 帧", end="", flush=True)
        try:
            d = fetch(host, "/imu")
            if d.get("ready"):
                rows.append((time.time(), d))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.02)
    print("\r" + " " * 40 + "\r", end="")

    if len(rows) < 20:
        print("✗ 采样太少，检查网络或 /imu 是否正常")
        return 1

    n = len(rows)
    span = rows[-1][0] - rows[0][0]
    hz = (n - 1) / span if span > 0 else 0.0

    def col(getter) -> list[float]:
        return [float(getter(d)) for _, d in rows]

    ax, ay, az = col(lambda d: d["acc_g"][0]), col(lambda d: d["acc_g"][1]), col(
        lambda d: d["acc_g"][2])
    gx, gy, gz = col(lambda d: d["gyr_dps"][0]), col(lambda d: d["gyr_dps"][1]), col(
        lambda d: d["gyr_dps"][2])
    mag = [(x * x + y * y + z * z) ** 0.5 for x, y, z in zip(ax, ay, az)]

    def mean(v: list[float]) -> float:
        return sum(v) / len(v)

    def std(v: list[float]) -> float:
        m = mean(v)
        return (sum((x - m) ** 2 for x in v) / len(v)) ** 0.5

    yaw_acc = 0.0
    prev = float(rows[0][1]["yaw"])
    for _, d in rows[1:]:
        cur = float(d["yaw"])
        yaw_acc += _unwrap(prev, cur)
        prev = cur

    bias = (mean(gx), mean(gy), mean(gz))
    rate = 60.0 * yaw_acc / span if span > 0 else 0.0   # °/min，实测（含固件死区）
    # 固件对 yaw 设了 0.5 dps 死区，静止时根本不积分，所以实测 yaw 会显示「零漂移」。
    # 这是假象：一旦开始动，零偏照样按比例累积。用 z 零偏直接换算出真实速率。
    raw_rate = 60.0 * bias[2]

    print(f"帧数 {n}  实际 {hz:.1f} Hz  时长 {span:.1f}s")
    print(f"加速度  均值 {mean(mag):.3f}g  标准差 {std(mag) * 1000:.1f} mg")
    print(f"        x {mean(ax):+.3f}  y {mean(ay):+.3f}  z {mean(az):+.3f} g")
    print(f"陀螺零偏 x {bias[0]:+.3f}  y {bias[1]:+.3f}  z {bias[2]:+.3f} dps")
    print(f"        噪声 x {std(gx):.2f}  y {std(gy):.2f}  z {std(gz):.2f} dps")
    print(f"yaw 实测累积 {yaw_acc:+.2f}°（固件死区 0.5dps 会吃掉静止漂移）")
    print(f"真实漂移率 {raw_rate:+.2f} °/min ({raw_rate / 60:+.3f} °/s)，按 z 零偏换算")
    print(f"         等价于每 {abs(60.0 / raw_rate) if abs(raw_rate) > 0.01 else 0:.0f} 秒偏 1°")

    # 静止判定：设备一动，零偏和漂移率全都是垃圾数据
    still = std(mag) < 0.025 and max(std(gx), std(gy), std(gz)) < 1.5
    print()
    if not still:
        print("⚠ 数据看起来不像静止：加速度标准差或陀螺噪声偏大。")
        print("  设备可能在充电时抖动 / 被人碰了，重跑一次再下结论。")
        return 1

    print("结论：", end="")
    if abs(raw_rate) < 6:
        print(f"漂移 {abs(raw_rate):.1f}°/min，纯 IMU 空鼠可用（每十几秒回中一次即可）")
    elif abs(raw_rate) < 36:
        print(f"漂移 {abs(raw_rate):.1f}°/min，纯 IMU 勉强能玩，但会明显跑偏")
    else:
        print(f"漂移 {abs(raw_rate):.1f}°/min（{abs(raw_rate) / 60:.2f}°/s），纯 IMU 不可用，"
              "必须上视觉或 UWB 外部参考")
    return 0


def _prompt(msg: str) -> None:
    print(msg)
    try:
        input("   准备好后按回车… ")
    except EOFError:
        pass


def _dominant(s: dict) -> tuple[str, int]:
    """看出一次倾斜主要落在哪个轴、正负如何。

    只关心方向，所以倾 5° 和倾 30° 判出来完全一样——不需要控角。"""
    tx, ty = s["tilt_x"], s["tilt_y"]
    if abs(tx) >= abs(ty):
        return "tilt_x", (1 if tx > 0 else -1)
    return "tilt_y", (1 if ty > 0 else -1)


def _peak(host: str, key: str, seconds: float = 1.5) -> float | None:
    """采一段，取 |key| 的 90 分位当「满舵」。

    让用户倾到自己玩起来最舒服的最大幅度就行，多少度由他定，
    我们只负责把这个幅度记下来当 1.0。"""
    vals: list[float] = []
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            d = fetch(host, "/imu")
            if d.get("ready"):
                vals.append(abs(float(d[key])))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.05)
    if len(vals) < 5:
        return None
    vals.sort()
    return vals[int(len(vals) * 0.9)]


def calibrate(host: str) -> int:
    print("=" * 62)
    print("倾斜映射标定（不需要控制角度）")
    print("=" * 62)
    print("只回答两件事：哪个轴管前后、哪个轴管左右。倾多少度无所谓，")
    print("倾 5° 和倾 30° 判出来的结果一样。最后一步再把你自己的最大幅度")
    print("定为满舵，尺度由你定，不由 20° 这个数字定。\n")

    base = sample(host, 1.2)
    if base is None:
        print("✗ 采不到数据。先确认 /imu 能通：curl http://%s:8080/imu" % host)
        return 1
    print(f"   平放时 tilt_x={base['tilt_x']:+.1f}° tilt_y={base['tilt_y']:+.1f}°")

    try:
        fetch(host, "/imu/zero")
        print("   ✓ 已把当前姿态记为零点\n")
    except (urllib.error.URLError, TimeoutError):
        print("   ! /imu/zero 调用失败，继续用当前值做基准\n")

    # —— 第一步：只判方向，不看幅度 ——
    _prompt("① 朝前倾一下（车头方向压低，倾多少度都行，能看出方向就够）")
    s = sample(host, 1.2)
    if s is None:
        print("   ✗ 没采到数据")
        return 1
    f_axis, f_sign = _dominant(s)
    minor = min(abs(s["tilt_x"]), abs(s["tilt_y"]))
    major = max(abs(s["tilt_x"]), abs(s["tilt_y"]))
    print(f"   x={s['tilt_x']:+.1f}°  y={s['tilt_y']:+.1f}°  → 前后 = "
          f"{'+' if f_sign > 0 else '-'}{f_axis}")
    if major > 1.0 and minor / major > 0.45:
        print(f"   ⚠ 两个轴都动了（次要分量占 {minor / major:.0%}），像是斜着倾的。"
              "结果仍可用，想更准可以重来。")

    _prompt("② 朝右倾一下（右侧压低）")
    s = sample(host, 1.2)
    if s is None:
        print("   ✗ 没采到数据")
        return 1
    r_axis, r_sign = _dominant(s)
    print(f"   x={s['tilt_x']:+.1f}°  y={s['tilt_y']:+.1f}°  → 左右 = "
          f"{'+' if r_sign > 0 else '-'}{r_axis}")

    if f_axis == r_axis:
        print("\n✗ 前后和左右判到了同一个轴，说明有一次倾的方向不对。重跑一次。")
        return 1

    # —— 第二步：把你自己的最大幅度定为满舵 ——
    print("\n接着定满舵：倾到「你在游戏里真会用的最大幅度」并保持 1.5 秒。")
    _prompt("③ 朝前倾到最大，保持住")
    full_f = _peak(host, f_axis)
    if full_f is None or full_f < 3.0:
        print("   ✗ 没能采到有效的最大幅度，重跑一次")
        return 1
    print(f"   前后满舵 = {full_f:.1f}°")

    _prompt("④ 朝右倾到最大，保持住")
    full_r = _peak(host, r_axis)
    if full_r is None or full_r < 3.0:
        print("   ✗ 没能采到有效的最大幅度，重跑一次")
        return 1
    print(f"   左右满舵 = {full_r:.1f}°")

    # 两个方向满舵差太多，手感会不一致（一个方向很灵敏、另一个很钝）
    if max(full_f, full_r) / min(full_f, full_r) > 1.4:
        print(f"   ⚠ 两个方向差了 {max(full_f, full_r) / min(full_f, full_r):.1f} 倍，"
              "手感会不一致。")
        print(f"     想统一的话，建议两次都取 {min(full_f, full_r):.1f}° 左右。")

    cfg = {
        "axis_fwd": f_axis,
        "axis_rgt": r_axis,
        "sign_fwd": f_sign,
        "sign_rgt": r_sign,
        "full_fwd": round(full_f, 1),
        "full_rgt": round(full_r, 1),
        "deadzone": 1.5,
    }
    try:
        with open(MAP_PATH, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
        print(f"\n已保存到 {MAP_PATH}")
    except OSError as e:
        print(f"\n! 保存失败：{e}")

    print("\n" + "-" * 62)
    print("贴进游戏代码（tilt_x / tilt_y 取自 /imu，单位是度）：")
    print("```python")
    print(f"AXIS_FWD = \"{f_axis}\"    # 前后轴")
    print(f"AXIS_RGT = \"{r_axis}\"    # 左右轴")
    print(f"SIGN_FWD = {f_sign:+d}          # 倾向前方时该轴是正还是负")
    print(f"SIGN_RGT = {r_sign:+d}")
    print(f"FULL_FWD = {full_f:.1f}         # 满舵：倾到这么大 = 1.0")
    print(f"FULL_RGT = {full_r:.1f}")
    print("")
    print("DEADZONE = 1.5         # 手放着不动时的抖动余量，低于这个值算 0")
    print("")
    print("def axis(v, full, dz=DEADZONE):")
    print("    \"\"\"死区之后线性，到满舵饱和。返回 -1 ~ +1。\"\"\"")
    print("    m = abs(v)")
    print("    if m <= dz:")
    print("        return 0.0")
    print("    out = min((m - dz) / max(full - dz, 1e-3), 1.0)")
    print("    return out if v > 0 else -out")
    print("")
    print("def tilt_to_stick(d):")
    print("    ay = axis(SIGN_FWD * d[AXIS_FWD], FULL_FWD)   # 前 +")
    print("    ax = axis(SIGN_RGT * d[AXIS_RGT], FULL_RGT)   # 右 +")
    print("    return ax, ay")
    print("```")
    print()
    print("跑一次验证：朝前倾，ay 应该变正；朝右倾，ax 应该变正。")
    print("方向反了就把对应的 SIGN_* 取反，不用重跑标定。")
    print("-" * 62)
    return 0


def _axis_value(v: float, full: float, dz: float) -> float:
    """死区之后线性，到满舵饱和，输出 -1 ~ +1。和打印给游戏的那段完全一致。"""
    m = abs(v)
    if m <= dz:
        return 0.0
    out = min((m - dz) / max(full - dz, 1e-3), 1.0)
    return out if v > 0 else -out


def stick(host: str) -> int:
    try:
        with open(MAP_PATH, encoding="utf-8") as f:
            cfg = json.load(f)
    except (OSError, json.JSONDecodeError):
        print(f"没有标定结果（{MAP_PATH}），先跑 --calib")
        return 1

    print("实时摇杆量（Ctrl-C 退出）。")
    print("朝前倾 ay 应变正，朝右倾 ax 应变正；反了就改 SIGN_*，不用重标。\n")
    try:
        while True:
            d = fetch(host, "/imu")
            if not d.get("ready"):
                time.sleep(0.3)
                continue
            ax = _axis_value(cfg["sign_rgt"] * d[cfg["axis_rgt"]],
                             cfg["full_rgt"], cfg["deadzone"])
            ay = _axis_value(cfg["sign_fwd"] * d[cfg["axis_fwd"]],
                             cfg["full_fwd"], cfg["deadzone"])
            print(
                f"\r ax {ax:+.2f} [{bar(ax * 20)}]  ay {ay:+.2f} [{bar(ay * 20)}]   ",
                end="",
                flush=True,
            )
            time.sleep(0.06)
    except KeyboardInterrupt:
        print("\n")
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
    ap.add_argument("--calib", action="store_true", help="引导式标定（判轴 + 定满舵，不需要控角度）")
    ap.add_argument("--stick", action="store_true", help="按已标定的映射实时显示摇杆量")
    ap.add_argument("--drift", action="store_true", help="静置漂移测量（判断能否只用 IMU）")
    ap.add_argument("--seconds", type=float, default=60.0, help="--drift 的采样时长")
    ap.add_argument("--status", action="store_true", help="只打印一次原始 JSON")
    args = ap.parse_args()

    if not args.host:
        print("需要设备地址：-H 192.168.x.x 或设置 SPARKBOT_HOST", file=sys.stderr)
        return 2

    try:
        if args.status:
            print(json.dumps(fetch(args.host, "/imu"), ensure_ascii=False, indent=2))
            return 0
        if args.drift:
            return drift(args.host, args.seconds)
        if args.stick:
            return stick(args.host)
        return calibrate(args.host) if args.calib else watch(args.host)
    except urllib.error.URLError as exc:
        print(f"\n连不上 {args.host}:{PORT} — {exc}", file=sys.stderr)
        print("检查：设备在同一网段？/status 能通？固件烧的是带 IMU 的版本？",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
