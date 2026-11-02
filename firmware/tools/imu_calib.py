#!/usr/bin/env python3
"""ESP-SparkBot 六轴 IMU 工具：验证姿态精度 + 映射到游戏摇杆。

设备在 8080 端口暴露两个端点：
    GET /imu       当前读数（JSON）
    GET /imu/zero  把当前姿态记为零点

=== 两件事分开，互不影响 ===

1. **传感器本身**：只关心「读数准不准」和「哪根轴对应哪个方向」。
   这部分由 --check（精度）和 --map（判轴）负责，结果写进 tilt_map.json
   的 sensor 段。它跟游戏无关，换游戏不用重标。

2. **游戏手感**：多少度算满舵、死区多大。这是纯主观参数，
   由 --set-full 写进 game 段，也可以随时手改 json。
   标定不会去测它，也不应该测——用人力去标「我能倾多少度」既不准也不稳定。

常用：
    python3 firmware/tools/imu_calib.py -H 192.168.1.23 --check
    python3 firmware/tools/imu_calib.py -H 192.168.1.23            # 实时摇杆量
    python3 firmware/tools/imu_calib.py --set-full 25              # 改满舵，不用连设备
    python3 firmware/tools/imu_calib.py --show                     # 看配置 + 贴游戏代码

只依赖标准库。设备地址也可以用环境变量 SPARKBOT_HOST 给。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request

# 局域网直连必须绕开系统代理，否则请求会被 http_proxy 拦走
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
PORT = 8080

MAP_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tilt_map.json")

# 游戏参数默认值。满舵 30° 是个中庸起点：比「手拿着最舒服的极限」略小，
# 不用倾到手腕尽头就能打满，也不至于轻轻一晃就满舵。
DEFAULT_GAME = {
    "full_fwd": 30.0,
    "full_rgt": 30.0,
    "deadzone": 1.5,
}
DEFAULT_SENSOR = {
    "axis_fwd": "tilt_y",
    "axis_rgt": "tilt_x",
    "sign_fwd": 1,
    "sign_rgt": 1,
}


def fetch(host: str, path: str, timeout: float = 2.0) -> dict:
    url = f"http://{host}:{PORT}{path}"
    with _OPENER.open(url, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", "replace"))


# ---------------------------------------------------------------------------
# 配置读写
# ---------------------------------------------------------------------------


def load_cfg() -> dict:
    """读配置。兼容早期「满舵和映射混在一个平铺字典里」的老格式，
    这样升级工具不会让之前的标定结果作废。"""
    cfg = {"sensor": dict(DEFAULT_SENSOR), "game": dict(DEFAULT_GAME)}
    try:
        with open(MAP_PATH, encoding="utf-8") as f:
            raw = json.load(f)
    except (OSError, json.JSONDecodeError):
        return cfg

    if "sensor" in raw or "game" in raw:
        cfg["sensor"].update(raw.get("sensor") or {})
        cfg["game"].update(raw.get("game") or {})
        return cfg

    # 老格式
    for k in ("axis_fwd", "axis_rgt", "sign_fwd", "sign_rgt"):
        if k in raw:
            cfg["sensor"][k] = raw[k]
    for k in ("full_fwd", "full_rgt", "deadzone"):
        if k in raw:
            cfg["game"][k] = raw[k]
    return cfg


def save_cfg(cfg: dict) -> None:
    with open(MAP_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


# ---------------------------------------------------------------------------
# 小工具
# ---------------------------------------------------------------------------


def bar(value: float, full: float, width: int = 21) -> str:
    """把数值画成条状，中心是零点，左负右正。终端里比纯数字直观。"""
    mid = width // 2
    norm = max(-1.0, min(1.0, value / max(full, 1e-6)))
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


def sample(host: str, seconds: float = 1.5) -> dict | None:
    """采一段取平均，压掉单帧噪声。返回 tilt 与单位重力向量。"""
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
    ax = sum(r["acc_g"][0] for r in rows) / n
    ay = sum(r["acc_g"][1] for r in rows) / n
    az = sum(r["acc_g"][2] for r in rows) / n
    mag = math.sqrt(ax * ax + ay * ay + az * az)
    return {
        "tilt_x": sum(r["tilt_x"] for r in rows) / n,
        "tilt_y": sum(r["tilt_y"] for r in rows) / n,
        "acc": (ax, ay, az),
        "gvec": (ax / mag, ay / mag, az / mag) if mag > 1e-6 else (0.0, 0.0, 1.0),
        "acc_mag": mag,
        "gyr": (
            sum(r["gyr_dps"][0] for r in rows) / n,
            sum(r["gyr_dps"][1] for r in rows) / n,
            sum(r["gyr_dps"][2] for r in rows) / n,
        ),
        "n": n,
    }


def _dot(a, b) -> float:
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]


def _angle_between(a, b) -> float:
    """两个单位向量的夹角（度）。这就是「姿态变化了多少度」的几何真值。"""
    return math.degrees(math.acos(max(-1.0, min(1.0, _dot(a, b)))))


# ---------------------------------------------------------------------------
# 1. 传感器：精度验证（不涉及任何「满舵」）
# ---------------------------------------------------------------------------


def _prompt(msg: str) -> None:
    print(msg)
    try:
        input("   摆好不动后按回车… ")
    except EOFError:
        pass


def check(host: str) -> int:
    """验证姿态估计准不准，用三个姿势互相比对。

    参考值从哪来？——重力方向。加速度计在准静态下唯一能绝对测准的就是
    「重力朝哪个方向」，而且这个方向不受零偏、量程、刻度误差影响（只受
    安装方向影响）。所以拿「两个姿势之间重力向量转过的角度」当几何真值，
    再对比固件给出的 tilt 向量变化量，就能判断姿态解算是否可信。

    这个测试不需要量角器，也不要求倾到某个特定角度。"""
    cfg = load_cfg()
    print("=" * 62)
    print("姿态精度验证（不需要控制角度、不需要量角器）")
    print("=" * 62)
    print("原理：两个姿势之间重力方向转过的角度是几何真值，")
    print("     固件给出的 tilt 变化量应该等于它。对不上就是解算有误差。\n")

    _prompt("① 把设备平放（或任意舒服的姿势），别动")
    a = sample(host, 1.8)
    if a is None:
        print("✗ 采不到数据。先确认 /imu 能通：curl http://%s:8080/imu" % host)
        return 1
    print(f"   tilt=({a['tilt_x']:+.1f}, {a['tilt_y']:+.1f})°  |a|={a['acc_mag']:.3f}g")

    _prompt("② 朝一个方向倾到 30~40° 左右，保持住（准确角度不重要）")
    b = sample(host, 1.8)
    if b is None:
        print("✗ 没采到数据")
        return 1
    print(f"   tilt=({b['tilt_x']:+.1f}, {b['tilt_y']:+.1f})°  |a|={b['acc_mag']:.3f}g")

    _prompt("③ 换个方向再倾一次（往斜角倾最好），保持住")
    c = sample(host, 1.8)
    if c is None:
        print("✗ 没采到数据")
        return 1
    print(f"   tilt=({c['tilt_x']:+.1f}, {c['tilt_y']:+.1f})°  |a|={c['acc_mag']:.3f}g\n")

    pairs = [("①②", a, b), ("①③", a, c), ("②③", b, c)]
    print(f"{'姿态对':<6}{'几何真值':>10}{'固件读数':>10}{'偏差':>9}{'相对误差':>10}")
    worst_abs = 0.0
    worst_rel = 0.0
    usable = 0
    for name, p, q in pairs:
        truth = _angle_between(p["gvec"], q["gvec"])
        dx = q["tilt_x"] - p["tilt_x"]
        dy = q["tilt_y"] - p["tilt_y"]
        got = math.hypot(dx, dy)
        err = got - truth
        rel = abs(err) / truth * 100 if truth > 3.0 else 0.0
        if truth > 5.0:
            usable += 1
            worst_abs = max(worst_abs, abs(err))
            worst_rel = max(worst_rel, rel)
        print(f"{name:<6}{truth:9.1f}°{got:9.1f}°{err:+8.1f}°{rel:9.0f}%")

    if usable == 0:
        print("\n✗ 三个姿势之间几乎没有变化（都 <5°），没测到有效倾斜。")
        print("  摆姿势时要真的把设备倾起来，再跑一次。")
        return 1

    mags = [p["acc_mag"] for p in (a, b, c)]
    mag_spread = (max(mags) - min(mags)) * 100
    print()
    print(f"重力模长 {min(mags):.3f}~{max(mags):.3f}g，跨姿势漂移 {mag_spread:.1f}%"
          "（<1.5% 说明加速度计刻度正常）")

    # 静止抖动决定死区该留多大——这是唯一需要从传感器侧给游戏侧的建议值
    nx, ny, still = _residual_noise(host)
    noise = max(nx, ny)
    cur_dz = cfg["game"]["deadzone"]
    if not still:
        # 采样时手还在动，这个标准差是「人的动作」不是「传感器噪声」，
        # 拿它推死区会得出「1.5° 太小」这种完全错误的结论，宁可不给。
        print(f"静止抖动：这两秒设备在动（sd {nx:.1f}°/ {ny:.1f}°），跳过死区评估。"
              f"当前死区 {cur_dz}°")
    else:
        verdict = '够用' if cur_dz >= noise * 3 else '偏小，抖动会漏过去'
        print(f"静止抖动 sd x{nx:.2f}° y{ny:.2f}°；当前死区 {cur_dz}°（{verdict}）")

    # 固件在线估出来的陀螺零偏，能直接看出这颗芯片的偏置水平
    try:
        d = fetch(host, "/imu")
        gb = d.get("gbias")
        if gb:
            print(f"在线估计的陀螺零偏 {gb[0]:+.2f} {gb[1]:+.2f} {gb[2]:+.2f} dps"
                  "（z 轴不可观测，别当真）")
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, KeyError):
        pass

    print()
    ok = worst_abs <= 2.0 and worst_rel <= 5.0
    if ok:
        print(f"✓ 通过：最大偏差 {worst_abs:.2f}°（{worst_rel:.0f}%），"
              "姿态解算可信，可以拿来映射成摇杆")
    else:
        print(f"✗ 偏差偏大：最大 {worst_abs:.1f}°（{worst_rel:.0f}%）")
        print("  排查顺序（按被踩过的坑排）：")
        print("  ① 固件里重力校正项有没有乘 kRadToDeg？err 是 rad/s，corr 是 dps，")
        print("     少乘这一步校正强度只剩 1/57.3，滤波器变成纯陀螺积分，")
        print("     表现为「静止时固件 roll 与加速度算出的 roll 差好几度」。")
        print("  ② 零偏是不是按校正量积分学的？那样会在手持慢速倾斜时学飞，")
        print("     几秒顶到限幅，然后往积分里灌假角速度。改成静止时直接抓。")
        print("  ③ 姿态还没稳就按了回车（刚放下 / 刚倾斜完），摆定再测。")
        print("  ④ 倾角用四元数旋转向量？那个量含 yaw，边倾边拧手腕会虚高。")
    if mag_spread > 1.5:
        print("⚠ 各姿势重力模长不一致，加速度计刻度或零偏有问题，角度不可全信")
    print()
    print("说明：阈值为 2° / 5%。实测倾角（roll/pitch 差）与重力方向真值偏差")
    print("      应在 1° 量级；明显超标时先量一下「同一帧里固件 roll 和由")
    print("      acc_g 算出的 roll」差多少——这个差值若不为 0，就不是分解公式")
    print("      的问题，而是滤波器没跟上加速度计。")
    return 0 if ok else 1


def _residual_noise(host: str) -> tuple[float, float, bool]:
    """静止 2 秒的 tilt 抖动（标准差，度）。死区设多大该看这个数。

    第三个返回值表示「这两秒里设备到底有没有静止」。check 的最后一步紧跟
    在用户摆姿势之后，手上还没松劲，采到的标准差能到 10° 以上——那是人在
    动，不是传感器噪声，拿它推死区会得出完全错误的结论。
    """
    rows = []
    t0 = time.time()
    while time.time() - t0 < 2.0:
        try:
            d = fetch(host, "/imu")
            if d.get("ready"):
                rows.append(d)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError):
            pass
        time.sleep(0.05)
    if len(rows) < 5:
        return (0.0, 0.0, False)

    def sd(vals):
        m = sum(vals) / len(vals)
        return (sum((v - m) ** 2 for v in vals) / len(vals)) ** 0.5

    nx = sd([r["tilt_x"] for r in rows])
    ny = sd([r["tilt_y"] for r in rows])
    # 真静止时抖动量级是 0.1~0.5°；超过 2° 基本可以断定人在动
    still = max(nx, ny) < 2.0
    return (nx, ny, still)


# ---------------------------------------------------------------------------
# 2. 传感器：判轴（只看方向，不看幅度）
# ---------------------------------------------------------------------------


def _dominant(s: dict) -> tuple[str, int]:
    """看出一次倾斜主要落在哪个轴、正负如何。

    只关心方向，所以倾 5° 和倾 30° 判出来完全一样——不需要控角。"""
    tx, ty = s["tilt_x"], s["tilt_y"]
    if abs(tx) >= abs(ty):
        return "tilt_x", (1 if tx > 0 else -1)
    return "tilt_y", (1 if ty > 0 else -1)


def map_axes(host: str) -> int:
    """只确定「哪个轴管前后、哪个轴管左右、符号是正还是负」。

    这一步跟幅度完全无关，所以随便倾多少都行。改完之后 game 段原样保留，
    你设的满舵不会被标定冲掉。"""
    cfg = load_cfg()
    print("=" * 62)
    print("判轴（只定方向，不涉及幅度；你的满舵设置不会被动到）")
    print("=" * 62)

    try:
        fetch(host, "/imu/zero")
        print("   ✓ 已把当前姿态记为零点（零点只影响零位，不影响判轴）\n")
    except (urllib.error.URLError, TimeoutError):
        print("   ! /imu/zero 调用失败，用当前值继续\n")

    _prompt("① 朝前倾一下（车头方向压低，倾多少度都行）")
    s = sample(host, 1.2)
    if s is None:
        print("   ✗ 没采到数据")
        return 1
    f_axis, f_sign = _dominant(s)
    minor = min(abs(s["tilt_x"]), abs(s["tilt_y"]))
    major = max(abs(s["tilt_x"]), abs(s["tilt_y"]))
    print(f"   x={s['tilt_x']:+.1f}°  y={s['tilt_y']:+.1f}°  → 前后 = "
          f"{'+' if f_sign > 0 else '-'}{f_axis}")

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

    cfg["sensor"] = {
        "axis_fwd": f_axis,
        "axis_rgt": r_axis,
        "sign_fwd": f_sign,
        "sign_rgt": r_sign,
    }
    save_cfg(cfg)
    print(f"\n已写入 {MAP_PATH} 的 sensor 段")
    print(f"   {f_axis}({'正向' if f_sign > 0 else '负向'}) = 前   "
          f"{r_axis}({'正向' if r_sign > 0 else '负向'}) = 右")
    return 0


# ---------------------------------------------------------------------------
# 3. 游戏参数：满舵 / 死区，用户自己定
# ---------------------------------------------------------------------------


def set_full(host: str, spec: str, deadzone: float | None) -> int:
    """设置「多少度算满舵」。这是纯手感参数，跟标定无关，随时可改。"""
    cfg = load_cfg()
    parts = [p.strip() for p in spec.replace("，", ",").split(",") if p.strip()]
    try:
        vals = [float(p) for p in parts]
    except ValueError:
        print("满舵要写成数字：--set-full 25 或 --set-full 25,30")
        return 2
    if any(v < 5.0 or v > 60.0 for v in vals):
        print("满舵建议落在 5~60° 之间，否则不是难操作就是容易打满")
        return 2
    if len(vals) == 1:
        vals = [vals[0], vals[0]]
    if len(vals) != 2:
        print("最多给两个数（前后, 左右）")
        return 2

    # 死区是手感参数，不自动推：只把静止抖动测出来当参考，
    # 免得「手不动时漂一点点」被误当成操作。没给设备地址就跳过。
    nd = 0.0
    if host:
        nx, ny, still = _residual_noise(host)
        nd = max(nx, ny) if still else 0.0

    cfg["game"]["full_fwd"] = vals[0]
    cfg["game"]["full_rgt"] = vals[1]
    if deadzone is not None:
        cfg["game"]["deadzone"] = deadzone
    save_cfg(cfg)

    print(f"满舵 = 前后 {vals[0]:.0f}° / 左右 {vals[1]:.0f}°   "
          f"死区 = {cfg['game']['deadzone']}°")
    print(f"已写入 {MAP_PATH} 的 game 段")
    if nd > 0:
        print(f"   静止抖动实测 {nd:.2f}°，死区只要比它大 2~3 倍就不会自己抖")
    print(f"当前手感：倾 {vals[0]:.0f}° 打满前进，倾 {vals[0] * 0.5:.0f}° 走一半")
    return 0


# ---------------------------------------------------------------------------
# 4. 实时输出
# ---------------------------------------------------------------------------


def _axis_value(v: float, full: float, dz: float) -> float:
    """死区之后线性，到满舵饱和，输出 -1 ~ +1。和打印给游戏的那段一致。"""
    m = abs(v)
    if m <= dz:
        return 0.0
    out = min((m - dz) / max(full - dz, 1e-3), 1.0)
    return out if v > 0 else -out


def snippet(cfg: dict) -> None:
    """打印能直接贴进游戏的那段映射代码。"""
    s, g = cfg["sensor"], cfg["game"]
    print("\n" + "-" * 62)
    print("贴进游戏代码（tilt_x / tilt_y 取自 /imu，单位是度）：")
    print("```python")
    print(f'AXIS_FWD = "{s["axis_fwd"]}"    # 前后轴')
    print(f'AXIS_RGT = "{s["axis_rgt"]}"    # 左右轴')
    print(f'SIGN_FWD = {s["sign_fwd"]:+d}    # 倾向前方时该轴是正还是负')
    print(f'SIGN_RGT = {s["sign_rgt"]:+d}')
    print(f'FULL_FWD = {g["full_fwd"]:.0f}    # 手感参数，随时可改')
    print(f'FULL_RGT = {g["full_rgt"]:.0f}')
    print(f'DEADZONE = {g["deadzone"]}')
    print("")
    print("def axis(v, full, dz=DEADZONE):")
    print('    """死区之后线性，到满舵饱和。返回 -1 ~ +1。"""')
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
    print("-" * 62)


def watch(host: str) -> int:
    """实时倾斜 + 按当前满舵换算的摇杆量。满舵一改，这里立刻跟着变。"""
    cfg = load_cfg()
    s, g = cfg["sensor"], cfg["game"]
    print(f"实时（Ctrl-C 退出）。满舵 前后 {g['full_fwd']:.0f}° / 左右 "
          f"{g['full_rgt']:.0f}°，死区 {g['deadzone']}°。\n")
    try:
        while True:
            d = fetch(host, "/imu")
            if not d.get("ready"):
                print(f"\r  未就绪: {d}", end="", flush=True)
                time.sleep(0.3)
                continue
            tx = s["sign_rgt"] * d[s["axis_rgt"]]
            ty = s["sign_fwd"] * d[s["axis_fwd"]]
            ax = _axis_value(tx, g["full_rgt"], g["deadzone"])
            ay = _axis_value(ty, g["full_fwd"], g["deadzone"])
            print(
                f"\r({tx:+6.1f},{ty:+6.1f})°  ax {ax:+.2f}[{bar(ax * 20, 20)}]"
                f"  ay {ay:+.2f}[{bar(ay * 20, 20)}]  ",
                end="",
                flush=True,
            )
            time.sleep(0.06)
    except KeyboardInterrupt:
        print("\n")
        return 0


# ---------------------------------------------------------------------------
# 5. 陀螺漂移（判断纯 IMU 指向是否可行的依据）
# ---------------------------------------------------------------------------


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

      yaw 只能靠陀螺积分，加速度计帮不上忙（没有磁力计），
      所以零偏会 1:1 变成每秒的指向误差。
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

    bias = (mean(gx), mean(gy), mean(gz))
    raw_rate = 60.0 * bias[2]

    print(f"帧数 {n}  实际 {hz:.1f} Hz  时长 {span:.1f}s")
    print(f"加速度  均值 {mean(mag):.3f}g  标准差 {std(mag) * 1000:.1f} mg")
    print(f"        x {mean(ax):+.3f}  y {mean(ay):+.3f}  z {mean(az):+.3f} g")
    print(f"陀螺零偏 x {bias[0]:+.3f}  y {bias[1]:+.3f}  z {bias[2]:+.3f} dps")
    print(f"        噪声 x {std(gx):.2f}  y {std(gy):.2f}  z {std(gz):.2f} dps")
    print(f"真实漂移率 {raw_rate:+.2f} °/min ({raw_rate / 60:+.3f} °/s)，按 z 零偏换算")
    print(f"         等价于每 {abs(60.0 / raw_rate) if abs(raw_rate) > 0.01 else 0:.0f} 秒偏 1°")

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


# ---------------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(
        description="ESP-SparkBot 六轴 IMU 工具（姿态验证 / 判轴 / 摇杆参数）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("-H", "--host", default=os.environ.get("SPARKBOT_HOST", ""),
                    help="设备 IP（默认读环境变量 SPARKBOT_HOST）")
    ap.add_argument("--check", action="store_true", help="姿态精度验证（不需要控制角度）")
    ap.add_argument("--map", action="store_true", help="判轴：只定前后/左右对应哪个轴")
    ap.add_argument("--set-full", metavar="DEG[,DEG]", help="设满舵角度，如 25 或 25,30")
    ap.add_argument("--deadzone", type=float, default=None, help="配 --set-full，指定死区（度）")
    ap.add_argument("--show", action="store_true", help="打印当前配置和可直接贴进游戏的代码")
    ap.add_argument("--drift", action="store_true", help="静置漂移测量")
    ap.add_argument("--seconds", type=float, default=60.0, help="--drift 的采样时长")
    ap.add_argument("--status", action="store_true", help="只打印一次原始 JSON")
    args = ap.parse_args()

    if args.show:
        cfg = load_cfg()
        print(json.dumps(cfg, ensure_ascii=False, indent=2))
        snippet(cfg)
        return 0

    if not args.host and (args.check or args.map or args.drift or args.status):
        print("需要设备地址：-H 192.168.x.x 或设置 SPARKBOT_HOST", file=sys.stderr)
        return 2

    try:
        if args.status:
            print(json.dumps(fetch(args.host, "/imu"), ensure_ascii=False, indent=2))
            return 0
        if args.set_full:
            return set_full(args.host, args.set_full, args.deadzone)
        if args.drift:
            return drift(args.host, args.seconds)
        if args.check:
            return check(args.host)
        if args.map:
            return map_axes(args.host)
        return watch(args.host)
    except urllib.error.URLError as exc:
        print(f"\n连不上 {args.host}:{PORT} — {exc}", file=sys.stderr)
        print("检查：设备在同一网段？/status 能通？固件烧的是带 IMU 的版本？",
              file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
