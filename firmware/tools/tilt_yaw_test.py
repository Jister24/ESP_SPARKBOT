"""验证「倾角」两种算法在手腕带 yaw 时的表现。

背景：实机测到倾了 38.8°、但读数 58.7°，多出来的 20° 来自边倾边拧手腕。
几何真值取「重力方向在机体系里转过的角度」——这个量对 yaw 免疫，
所以它也是判断某种倾角算法对不对的唯一标准。

结论用来决定固件里 tilt_x/tilt_y 怎么算，不是运行期代码。
"""
import math
import random

D = math.degrees


def qmul(a, b):
    w1, x1, y1, z1 = a
    w2, x2, y2, z2 = b
    return (
        w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2,
        w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2,
        w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2,
        w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2,
    )


def qx(t):
    return (math.cos(t / 2), math.sin(t / 2), 0.0, 0.0)


def qy(t):
    return (math.cos(t / 2), 0.0, math.sin(t / 2), 0.0)


def qz(t):
    return (math.cos(t / 2), 0.0, 0.0, math.sin(t / 2))


def up_in_body(q):
    """重力在机体系的方向 = R(q)ᵀ·(0,0,1)。"""
    w, x, y, z = q
    return (2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))


def euler(q):
    """与固件 QuatToEuler 完全同式。"""
    w, x, y, z = q
    ux = 2 * (x * z - w * y)
    uy = 2 * (y * z + w * x)
    uz = 1 - 2 * (x * x + y * y)
    return D(math.atan2(uy, uz)), D(math.atan2(-ux, math.hypot(uy, uz)))


def rotvec_xy(q):
    """旧的错误做法：q_rel 的旋转向量 x/y。"""
    w, x, y, z = q
    if w < 0:
        w, x, y, z = -w, -x, -y, -z
    ang = 2 * math.acos(max(-1.0, min(1.0, w)))
    s = math.sqrt(max(0.0, 1 - w * w))
    if s < 1e-6:
        return 2 * D(x), 2 * D(y)
    return D(x / s * ang), D(y / s * ang)


def angle(a, b):
    d = sum(p * q for p, q in zip(a, b))
    na = math.sqrt(sum(p * p for p in a))
    nb = math.sqrt(sum(p * p for p in b))
    return D(math.acos(max(-1.0, min(1.0, d / (na * nb)))))


def main():
    rnd = random.Random(11)
    print("每组：先倾 tilt，再绕竖直轴拧 yaw（手持最自然的动作）")
    print(f"{'tilt':>6}{'yaw':>6}{'真值':>9}{'roll/pitch 法':>15}{'旋转向量法':>14}")
    rows = []
    for _ in range(6):
        tilt = rnd.uniform(15, 50)
        yaw = rnd.uniform(0, 70)
        axis = rnd.choice(["x", "y", "diag"])
        rad = math.radians(tilt)
        if axis == "x":
            qt = qx(rad)
        elif axis == "y":
            qt = qy(-rad)
        else:
            qt = qmul(qx(rad * 0.7), qy(-rad * 0.7))
        q = qmul(qz(math.radians(yaw)), qt)

        truth = angle((0.0, 0.0, 1.0), up_in_body(q))
        r, p = euler(q)
        got_rp = math.hypot(r, p)
        rx, ry = rotvec_xy(q)
        got_rv = math.hypot(rx, ry)
        rows.append((abs(got_rp - truth), abs(got_rv - truth)))
        print(f"{tilt:6.1f}{yaw:6.1f}{truth:8.1f}°{got_rp:11.1f}°{got_rv:11.1f}°")

    worst_rp = max(e for e, _ in rows)
    worst_rv = max(e for _, e in rows)
    print(f"\n最大偏差：roll/pitch 法 {worst_rp:.2f}°，旋转向量法 {worst_rv:.2f}°")

    print("\n小角度扫描（yaw 固定 30°，看 roll/pitch 法在大倾角下的过读）")
    for tilt in (10, 20, 30, 40, 50, 60):
        for axis_name, qt in (("单轴", qx(math.radians(tilt))),
                              ("斜 45°", qmul(qx(math.radians(tilt * 0.707)), qy(-math.radians(tilt * 0.707))))):
            q = qmul(qz(math.radians(30)), qt)
            truth = angle((0.0, 0.0, 1.0), up_in_body(q))
            r, p = euler(q)
            got = math.hypot(r, p)
            print(f"  tilt={tilt:2d}° {axis_name:6s} 真值 {truth:5.1f}°  读数 {got:5.1f}°  "
                  f"偏差 {got - truth:+5.2f}°")


if __name__ == "__main__":
    main()
