#!/usr/bin/env python3
"""PX4 attitude_estimator_q 数学的 Python 原型 —— 上机前先验证。

对应 PX4 src/modules/attitude_estimator_q/attitude_estimator_q_main.cpp:

    _rates = _gyro + _gyro_bias;
    corr = _rates + 加速度修正
    _q += _q.derivative1(corr) * dt;  _q.normalize();

    k   = R(q)^T * (0,0,1)                      # 估计的重力方向（机体系）
    corr += (k % acc.normalized()) * ATT_W_ACC
    仅当 |a| 落在 0.9g~1.1g 才融合加速度

    if spinRate < 0.175 rad/s:
        _gyro_bias += corr * (ATT_W_GYRO_BIAS * dt)
        clamp(_gyro_bias, ±ATT_BIAS_MAX)

本原型要回答三个问题：
1. 这套增益在 100Hz / BMI260 噪声水平下收敛快不快、稳不稳；
2. 陀螺零偏能不能被它自己估出来（我们实测 z 零偏导致 4.56°/min 漂移）；
3. tilt 改成「零点机体系下的旋转轴分解」，能不能消掉欧拉角方案 9° 的过读。
"""

import math
import random

DEG = math.degrees
RAD = math.radians
G = 9.80665

# ---------------------------------------------------------------- 四元数 (w,x,y,z)


def qmul(a, b):
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (
        aw * bw - ax * bx - ay * by - az * bz,
        aw * bx + ax * bw + ay * bz - az * by,
        aw * by - ax * bz + ay * bw + az * bx,
        aw * bz + ax * by - ay * bx + az * bw,
    )


def qnorm(q):
    n = math.sqrt(sum(c * c for c in q))
    return tuple(c / n for c in q)


def qconj(q):
    return (q[0], -q[1], -q[2], -q[3])


def qrot(q, v):
    """R(q)·v，机体系 → 世界系。"""
    w, x, y, z = q
    vx, vy, vz = v
    tx = 2.0 * (y * vz - z * vy)
    ty = 2.0 * (z * vx - x * vz)
    tz = 2.0 * (x * vy - y * vx)
    return (vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx))


def qrot_inv(q, v):
    """R(q)^T·v，世界系 → 机体系。"""
    return qrot(qconj(q), v)


def q_from_rvec(rv):
    """旋转向量(rad) → 四元数。"""
    ang = math.sqrt(sum(c * c for c in rv))
    if ang < 1e-12:
        return (1.0, 0.0, 0.0, 0.0)
    s = math.sin(ang * 0.5) / ang
    return (math.cos(ang * 0.5), rv[0] * s, rv[1] * s, rv[2] * s)


def q_to_rvec(q):
    """四元数 → 旋转向量(rad)，含 w<0 的短弧处理。"""
    if q[0] < 0.0:
        q = tuple(-c for c in q)
    w = max(-1.0, min(1.0, q[0]))
    ang = 2.0 * math.acos(w)
    s = math.sqrt(max(0.0, 1.0 - w * w))
    if s < 1e-9:
        return (2.0 * q[1], 2.0 * q[2], 2.0 * q[3])
    return (q[1] / s * ang, q[2] / s * ang, q[3] / s * ang)


def q_to_euler(q):
    """ZYX：roll 绕 x、pitch 绕 y、yaw 绕 z（度）。"""
    w, x, y, z = q
    roll = math.atan2(2 * (w * x + y * z), 1 - 2 * (x * x + y * y))
    pitch = math.asin(max(-1.0, min(1.0, 2 * (w * y - z * x))))
    yaw = math.atan2(2 * (w * z + x * y), 1 - 2 * (y * y + z * z))
    return DEG(roll), DEG(pitch), DEG(yaw)


# ---------------------------------------------------------------- 估计器


class AttEstimator:
    """PX4 attitude_estimator_q 的移植（去掉磁力计/外航向）。

    corr 的符号约定沿用 PX4：k 为估计重力方向，â 为实测，误差取 k×â 后
    叠加到角速度上；若符号取反会正反馈发散，这里显式记录 sign 便于验证。
    """

    def __init__(self, w_acc=1.0, w_bias=0.15, bias_max=RAD(3.0),
                 acc_low=0.9, acc_high=1.1, bias_rate_max=RAD(10.0), err_sign=-1.0):
        self.q = (1.0, 0.0, 0.0, 0.0)
        self.bias = [0.0, 0.0, 0.0]
        self.w_acc = w_acc
        self.w_bias = w_bias
        self.bias_max = bias_max
        self.acc_low = acc_low
        self.acc_high = acc_high
        self.bias_rate_max = bias_rate_max
        self.err_sign = err_sign

    def update(self, gyro_dps, acc_g, dt):
        g = [RAD(v) for v in gyro_dps]
        rates = [g[i] + self.bias[i] for i in range(3)]
        corr = list(rates)

        norm_a = math.sqrt(sum(c * c for c in acc_g))
        spin = math.sqrt(sum(c * c for c in rates))
        e = (0.0, 0.0, 0.0)
        if self.acc_low <= norm_a <= self.acc_high:
            k = qrot_inv(self.q, (0.0, 0.0, 1.0))     # 估计的"上"在机体系
            a = [c / norm_a for c in acc_g]
            e = (k[1] * a[2] - k[2] * a[1],
                 k[2] * a[0] - k[0] * a[2],
                 k[0] * a[1] - k[1] * a[0])
            for i in range(3):
                corr[i] += self.err_sign * e[i] * self.w_acc

        if spin < self.bias_rate_max:
            # 只积分「校正项」，不含 rates 本身，符号与校正同号。
            # 实测（见 __main__ 的符号扫描）：同号能让零偏收敛到 -真值，
            # 静稳误差从 0.13° 降到 0.02°；反号会把零偏学成正的、越学越偏。
            # PX4 写的是 _gyro_bias += corr*(w*dt)，corr 里还含 rates 本身，
            # 相当于多了一个 bias += bias*w*dt 的自激项；它默认
            # ATT_W_GYRO_BIAS=0 所以从没暴露，我们照抄必须去掉那一项。
            for i in range(3):
                self.bias[i] += self.err_sign * e[i] * self.w_acc * self.w_bias * dt
                self.bias[i] = max(-self.bias_max, min(self.bias_max, self.bias[i]))

        self.q = qnorm(qmul(self.q, q_from_rvec([c * dt for c in corr])))
        return self.q


# ---------------------------------------------------------------- 仿真


def _ss(a, b, t):
    """a~b 之间的 smoothstep，0→1，首尾导数为 0，保证角速度连续。"""
    if t <= a:
        return 0.0
    if t >= b:
        return 1.0
    x = (t - a) / (b - a)
    return x * x * (3 - 2 * x)


def traj(t):
    """返回世界系旋转向量（度）。模拟手持：静止→前后→左右→斜角→静止→晃→静止。

    分段之间必须连续，否则差分会造出不存在的角速度尖峰，
    测出来的误差全是轨迹的锅。"""
    fwd = 35.0 * _ss(5, 10, t)                     # 前后倾 0→35°
    rgt = 30.0 * _ss(13, 18, t)                    # 叠加左右倾 0→30°
    back = 1.0 - _ss(22, 25, t)                    # 回中
    env = _ss(30, 31.5, t) * (1.0 - _ss(34, 35.5, t))   # 晃动段的包络
    wob_f = 15.0 * math.sin((t - 30) * 6.0) * env
    wob_r = 15.0 * math.cos((t - 30) * 7.0) * env
    return (fwd * back + wob_f, rgt * back + wob_r, 0.0)


def simulate(seed=1, seconds=40.0, dt=0.01, gyro_noise=0.12, acc_noise=0.004,
             bias_true=(0.05, -0.12, 0.08), est=None, shake_g=0.0):
    rnd = random.Random(seed)
    est = est or AttEstimator()

    q_prev = q_from_rvec([RAD(v) for v in traj(0.0)])
    log = []
    for step in range(int(seconds / dt)):
        t = step * dt
        q_true = q_from_rvec([RAD(v) for v in traj(t)])
        # 机体系角速度：ω = 2·vec( q_prev* ⊗ q_true ) / dt
        dq = qmul(qconj(q_prev), q_true)
        if dq[0] < 0:
            dq = tuple(-c for c in dq)
        vec = q_to_rvec(dq)
        omega = [DEG(v) / dt for v in vec]
        q_prev = q_true

        gyro = [omega[i] + bias_true[i] + rnd.gauss(0, gyro_noise) for i in range(3)]
        # 加速度计在静止时读的是"上"方向在机体系里的分量 = R(q)^T·z。
        # 写成 R(q)·z 是"机体系 z 轴在世界系的方向"，大角度下会完全对不上，
        # 表现为滤波器越倾越偏——这个坑值得单独记一笔。
        up = qrot_inv(q_true, (0.0, 0.0, 1.0))
        acc = [up[i] + rnd.gauss(0, acc_noise) for i in range(3)]
        # 手持晃动会叠上真实的线加速度，模长偏离 1g —— 这正是 PX4
        # 那个 0.9~1.1g 门控要挡掉的东西
        if shake_g > 0.0 and 30.0 <= t <= 35.0:
            acc[0] += shake_g * math.sin((t - 30.0) * 18.0)
            acc[1] += shake_g * math.sin((t - 30.0) * 23.0 + 1.0)

        est.update(gyro, acc, dt)

        up_est = qrot_inv(est.q, (0.0, 0.0, 1.0))
        dot = max(-1.0, min(1.0, sum(a * b for a, b in zip(up, up_est))))
        log.append((t, DEG(math.acos(dot)), list(est.bias)))
    return log, est


def report(log, est, bias_true=(0.05, -0.12, 0.08)):
    print(f"{'时间':>6}  {'姿态误差(度)':>12}  {'零偏估计(dps)':>26}")
    for m in (3, 5, 10, 15, 20, 26, 31, 35, 39.9):
        idx = min(len(log) - 1, int(m / 0.01))
        t, err, bias = log[idx]
        print(f"{t:6.1f}  {err:12.3f}  {bias[0]:+7.3f} {bias[1]:+7.3f} {bias[2]:+7.3f}")
    tail = [e for t, e, _ in log if t > 30]
    print(f"\n后 10s 平均姿态误差 {sum(tail) / len(tail):.3f}°  最大 {max(tail):.3f}°")
    print(f"最终零偏 {est.bias[0]:+.3f} {est.bias[1]:+.3f} {est.bias[2]:+.3f} dps "
          f"（真值 {bias_true[0]:+.3f} {bias_true[1]:+.3f} {bias_true[2]:+.3f}）")


# ---------------------------------------------------------------- 倾角分解对比


def tilt_from_quat(q_zero, q):
    """零点机体系下把相对旋转拆成绕机身 x / y 的角度（度）。

    这是精确解：q_rel = conj(q_zero) ⊗ q 的旋转向量分量就是绕三根机身轴的
    转角，|(x, y)| 在轴向旋转时严格等于总转角，不受欧拉角耦合影响。"""
    q_rel = qmul(qconj(q_zero), q)
    rv = q_to_rvec(q_rel)
    return DEG(rv[0]), DEG(rv[1])


def compare_tilt():
    """随机"手腕倾斜"下：四元数旋转向量分解 vs 现有欧拉角相减。

    只取绕机身水平轴的旋转——绕机身 z 轴转是偏航，本来就不该算成倾角。"""
    rnd = random.Random(3)
    q_zero = q_from_rvec((RAD(4), RAD(-6), RAD(20)))   # 零点本身不平，更接近真机
    e0 = q_to_euler(q_zero)
    rows = []
    for _ in range(4000):
        phi = rnd.uniform(0, 2 * math.pi)
        theta = RAD(rnd.uniform(5, 55))
        delta = q_from_rvec((theta * math.cos(phi), theta * math.sin(phi), 0.0))
        q = qmul(q_zero, delta)          # 绕机身轴转，符合"拿着转手腕"

        tx, ty = tilt_from_quat(q_zero, q)
        err_q = abs(math.hypot(tx, ty) - DEG(theta))

        e = q_to_euler(q)
        got = math.hypot(e[0] - e0[0], e[1] - e0[1])
        err_e = got - DEG(theta)

        rows.append((DEG(theta), err_q, err_e))

    for lo, hi in ((0, 20), (20, 30), (30, 40), (40, 55)):
        sel = [(a, b) for t, a, b in rows if lo <= t < hi]
        if sel:
            print(f"真值 {lo:2d}-{hi:2d}°：四元数最大偏差 {max(a for a, _ in sel):6.3f}°  "
                  f"| 欧拉角相减最大偏差 {max(abs(b) for _, b in sel):5.2f}°")
    print(f"\n总体最大偏差：四元数分解 {max(r[1] for r in rows):.3f}°   "
          f"欧拉角相减 {max(abs(r[2]) for r in rows):.2f}°")


if __name__ == "__main__":
    print("=" * 66)
    print("① 倾角分解：四元数旋转向量 vs 现有欧拉角相减（零点取在倾斜姿势）")
    print("=" * 66)
    compare_tilt()

    print()
    print("=" * 66)
    print("② 滤波器收敛与零偏估计（真零偏 +0.050/-0.120/+0.080 dps）")
    print("=" * 66)
    log, est = simulate()
    report(log, est)

    print()
    print("=" * 66)
    print("③ 校正符号取错会怎样（验证 err_sign）")
    print("=" * 66)
    for s in (+1.0, -1.0):
        est = AttEstimator(err_sign=s)
        log, est2 = simulate(est=est)
        tail = [e for t, e, _ in log if t > 30]
        print(f"  err_sign={s:+.0f}: 后 10s 平均误差 {sum(tail) / len(tail):8.2f}°")

    print()
    print("=" * 66)
    print("④ 零偏学习率扫描")
    print("=" * 66)
    for wb in (0.0, 0.05, 0.15, 0.5, 1.5, 5.0):
        est = AttEstimator(w_bias=wb)
        log, est2 = simulate(est=est)
        tail = [e for t, e, _ in log if t > 30]
        print(f"  ATT_W_GYRO_BIAS={wb:<5} 稳态误差 {sum(tail) / len(tail):6.3f}°  "
              f"z 零偏估成 {est2.bias[2]:+.3f}（真值 +0.080）")
