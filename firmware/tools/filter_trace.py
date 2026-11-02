"""照抄固件那段滤波代码，逐帧跑，看它到底收敛到哪。

用途：实机发现「固件 roll/pitch」与「加速度算出的 roll/pitch」差 8.7°，
而且收敛极慢。这里把固件数学原样复刻，只改符号/增益做对照，
定位是符号错、坐标系错，还是增益太小。

不是运行期代码，是排查工具。
"""
import math

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


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q) if n > 1e-9 else (1.0, 0.0, 0.0, 0.0)


def rot_inv(q, v):
    """R(q)ᵀ·v，与固件 QuatRotateInv 同式。"""
    cw, cx, cy, cz = q[0], -q[1], -q[2], -q[3]
    tx = 2 * (cy * v[2] - cz * v[1])
    ty = 2 * (cz * v[0] - cx * v[2])
    tz = 2 * (cx * v[1] - cy * v[0])
    return (
        v[0] + cw * tx + (cy * tz - cz * ty),
        v[1] + cw * ty + (cz * tx - cx * tz),
        v[2] + cw * tz + (cx * ty - cy * tx),
    )


def from_rotvec(rv):
    ang = math.sqrt(sum(c * c for c in rv))
    if ang < 1e-9:
        return (1.0, 0.0, 0.0, 0.0)
    s = math.sin(ang / 2) / ang
    return (math.cos(ang / 2), rv[0] * s, rv[1] * s, rv[2] * s)


def align_up(up):
    n = math.sqrt(sum(c * c for c in up))
    ux, uy, uz = (c / n for c in up)
    ax, ay = uy, -ux
    al = math.sqrt(ax * ax + ay * ay)
    if al < 1e-6:
        return (1.0, 0.0, 0.0, 0.0) if uz > 0 else (0.0, 1.0, 0.0, 0.0)
    half = 0.5 * math.acos(max(-1.0, min(1.0, uz)))
    s = math.sin(half) / al
    return (math.cos(half), ax * s, ay * s, 0.0)


def euler(q):
    w, x, y, z = q
    ux = 2 * (x * z - w * y)
    uy = 2 * (y * z + w * x)
    uz = 1 - 2 * (x * x + y * y)
    return D(math.atan2(uy, uz)), D(math.atan2(-ux, math.hypot(uy, uz)))


def err_deg(q, acc):
    """估计的「上」与实测重力方向之间的夹角。"""
    n = math.sqrt(sum(c * c for c in acc))
    k = rot_inv(q, (0.0, 0.0, 1.0))
    a = [c / n for c in acc]
    d = sum(p * r for p, r in zip(k, a))
    return D(math.acos(max(-1.0, min(1.0, d))))


def run(acc, sign, w_acc=0.6, frames=300, dt=0.01, label=""):
    q = align_up(acc)
    n = math.sqrt(sum(c * c for c in acc))
    a = [c / n for c in acc]
    traj = []
    for i in range(frames):
        k = rot_inv(q, (0.0, 0.0, 1.0))
        e = (k[1] * a[2] - k[2] * a[1],
             k[2] * a[0] - k[0] * a[2],
             k[0] * a[1] - k[1] * a[0])
        corr = [sign * e[j] * w_acc for j in range(3)]
        q = qnorm(qmul(q, from_rotvec([c * dt for c in corr])))
        if i in (0, 10, 50, 100, 200, 299):
            traj.append((i, err_deg(q, acc), euler(q)))
    r, p = euler(q)
    print(f"{label:28s} 末态 roll {r:+7.2f} pitch {p:+7.2f}  残余误差 {err_deg(q, acc):6.2f}°")
    return traj


print("场景：设备静止，acc = (0, 0.02, 0.99)")
print("      （对照：由 acc 直接算 roll = +1.2°, pitch = +0.1°）\n")
acc = (0.0, 0.02, 0.99)
for sign, label in ((-1.0, "corr -= err*w（固件现状）"), (+1.0, "corr += err*w")):
    # 故意把初值拧偏 10°，看这一步是把误差拉回来还是推出去
    q = qnorm(qmul(align_up(acc), from_rotvec((0.17, 0.0, 0.0))))
    print(f"{label}：")
    print(f"{'帧':>6}{'残余误差':>10}{'roll':>9}{'pitch':>9}")
    n = math.sqrt(sum(c * c for c in acc))
    a = [c / n for c in acc]
    for i in range(400):
        k = rot_inv(q, (0.0, 0.0, 1.0))
        e = (k[1] * a[2] - k[2] * a[1],
             k[2] * a[0] - k[0] * a[2],
             k[0] * a[1] - k[1] * a[0])
        corr = [sign * e[j] * 0.6 for j in range(3)]
        q = qnorm(qmul(q, from_rotvec([c * 0.01 for c in corr])))
        if i in (0, 10, 30, 100, 200, 399):
            r, p = euler(q)
            print(f"{i:6d}{err_deg(q, acc):9.2f}°{r:8.2f}°{p:8.2f}°")
    print()
