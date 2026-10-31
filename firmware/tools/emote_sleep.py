#!/usr/bin/env python3
"""画一套"睡觉"表情：闭眼 + 飘起来的 zzz，输出成 .eaf。

原厂 sleep.eaf 是个变形动画：从一个大白圆（睁眼）慢慢压扁成一条横杠，
所以刚切到睡觉时会有一瞬间像没闭上眼。这里重画成从头到尾都闭着的眼，
再把 zzz 单独做成一个小动画，用 emote_display 里另建的 anim 对象叠在左上角
（eye_anim 是 mirror=auto，zzz 画进去会被镜像成两份，只能分开放）。

产物：
    firmware/emote_style/240_240/sleep.eaf   125×160，单只眼，屏幕上镜像成一双
    firmware/emote_style/240_240/zzz.eaf      64×56，透明底，叠在左上角
    docs/images/sleep-preview.png            240×240 屏幕效果图（挑 4 帧）

用法：
    python3 firmware/tools/emote_sleep.py            # 只生成，不安装
    python3 firmware/tools/emote_sleep.py --install  # 顺带拷进 emote 素材目录
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).parent))
from eaf_write import write_eaf  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
OUT_DIR = REPO / "firmware" / "emote_style" / "240_240"
PREVIEW = REPO / "docs" / "images" / "sleep-preview.png"
ASSETS_DEFAULT = (Path.home() / "esp" / "xiaozhi-esp32" / "managed_components" /
                  "espressif2022__esp_emote_assets" / "emoji_large")

EYE_W, EYE_H = 125, 160          # 与其它表情一致，eye_anim 按这个尺寸布局
WHITE = (230, 246, 255, 255)     # 和原厂素材同一个白
BLACK = (0, 0, 0, 255)
CLEAR = (0, 0, 0, 0)

SS = 4                           # 超采样倍数，画完再阈值化成像素图
EYE_FRAMES = 40                  # 20fps → 2 秒一次呼吸
ZZZ_W, ZZZ_H = 64, 56
ZZZ_FRAMES = 24                  # 10fps → 2.4 秒一轮
ZZZ_FPS = 10


def _threshold(img: Image.Image, w: int, h: int, fg, bg) -> Image.Image:
    """缩小到目标尺寸，按覆盖率决定是否上色（等价于硬边抗锯齿）。"""
    mask = img.split()[3].resize((w, h), Image.LANCZOS)
    out = Image.new("RGBA", (w, h), bg)
    solid = Image.new("RGBA", (w, h), fg)
    out.paste(solid, (0, 0), mask.point(lambda v: 255 if v >= 128 else 0))
    return out


def _bezier(p0, p1, p2, steps=64):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        pts.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return pts


def eye_frame(t: float) -> Image.Image:
    """闭着的眼：一条向下弯的白弧（中间厚、两头收细），随呼吸轻微起伏。

    t ∈ [0,1) 是一个呼吸周期；只看形状不看时间，避免出现任何"睁开"的帧。
    形状用上下两条曲线夹出来的月牙填充，而不是描一条等宽的线——描线在
    收细的两端会断成一格一格，填出来的边缘干净得多。
    """
    phase = 2 * math.pi * t
    lift = 1.6 * math.sin(phase)              # 整条眼皮上下浮动
    dip = 9.0 + 1.5 * math.sin(phase)         # 弧度：呼气时垂得更低
    thickness = 13.5 + 1.0 * math.sin(phase + 0.8)

    scale = SS
    img = Image.new("RGBA", (EYE_W * scale, EYE_H * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    base_y = 70 + lift                        # 眼睛圆的中心（canvas y≈80）
    left, right = 34, 112                     # 与原厂那条横杠同宽（86px）
    steps = 96
    upper, lower = [], []
    for i in range(steps + 1):
        u = i / steps
        x = left + (right - left) * u
        yc = base_y + dip * math.sin(math.pi * u)          # 整条的走向
        th = thickness * (1 - 0.60 * abs(2 * u - 1) ** 2.2)  # 两端收细
        upper.append((x * scale, (yc - th / 2) * scale))
        lower.append((x * scale, (yc + th / 2) * scale))

    d.polygon(upper + lower[::-1], fill=WHITE)
    return _threshold(img, EYE_W, EYE_H, WHITE, BLACK)


def _z_glyph(d: ImageDraw.ImageDraw, x: float, y: float, s: float, scale: int):
    """一个方块风格的 Z：上下两条横杠 + 一条斜杠。"""
    bar = max(3.0, s * 0.2)
    X, Y, S, B = x * scale, y * scale, s * scale, bar * scale
    d.rectangle([X, Y, X + S, Y + B], fill=WHITE)                    # 上横
    d.rectangle([X, Y + S - B, X + S, Y + S], fill=WHITE)            # 下横
    d.polygon([(X + S - B * 1.1, Y + B), (X + S, Y + B),
               (X + B * 1.1, Y + S - B), (X, Y + S - B)], fill=WHITE)  # 斜杠


def zzz_frame(i: int) -> Image.Image:
    """三个 z 依次冒出、往上飘一点，最后一起消失再重来。"""
    scale = SS
    img = Image.new("RGBA", (ZZZ_W * scale, ZZZ_H * scale), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    # (出现帧, 存在帧数, 起点 x, y, 字号)；字号递增、整体往右上排。
    # 三个 z 依次冒出来，但一起在第 20 帧消失，这样 0.6~2.0s 这段能看到完整的 "zzz"。
    plan = [(0, 20, 6, 32, 13), (3, 17, 24, 20, 16), (6, 14, 43, 8, 19)]
    for start, life, x0, y0, size in plan:
        age = i - start
        if 0 <= age < life:
            grow = min(1.0, 0.62 + 0.19 * age)        # 冒出来的头两帧放大一下
            drift = -min(4.0, age * 0.36)             # 慢慢往上飘，别飘出画布
            _z_glyph(d, x0, y0 + drift, size * grow, scale)

    return _threshold(img, ZZZ_W, ZZZ_H, WHITE, CLEAR)


def screen_preview(eye: Image.Image, zzz: Image.Image) -> Image.Image:
    """按 layout.json 的位置还原 240×240 屏：eye_anim x=-5,y=48 + 镜像，zzz 在 (8,6)。"""
    canvas = Image.new("RGBA", (240, 240), (0, 0, 0, 255))
    canvas.alpha_composite(eye, (-5, 48))
    canvas.alpha_composite(eye.transpose(Image.FLIP_LEFT_RIGHT), (240 - (EYE_W - 5), 48))
    canvas.alpha_composite(zzz, (8, 6))
    return canvas


def build(out_dir: Path, preview: Path) -> None:
    eyes = [eye_frame(i / EYE_FRAMES) for i in range(EYE_FRAMES)]
    zzzs = [zzz_frame(i) for i in range(ZZZ_FRAMES)]

    eye_size = write_eaf(out_dir / "sleep.eaf", eyes)
    zzz_size = write_eaf(out_dir / "zzz.eaf", zzzs)
    print(f"sleep.eaf  {EYE_FRAMES} 帧 {EYE_W}×{EYE_H}  {eye_size / 1024:.1f} KB")
    print(f"zzz.eaf    {ZZZ_FRAMES} 帧 {ZZZ_W}×{ZZZ_H}  {zzz_size / 1024:.1f} KB")

    picks = (0, 10, 20, 30)
    cell = 240
    sheet = Image.new("RGB", (cell * 4 + 30, cell + 46), (18, 19, 22))
    for k, fi in enumerate(picks):
        sheet.paste(screen_preview(eyes[fi], zzzs[fi % ZZZ_FRAMES]).convert("RGB"),
                    (k * (cell + 10), 38))
    preview.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(preview)
    print(f"预览 {preview}")


def install(assets: Path) -> None:
    for name in ("sleep.eaf", "zzz.eaf"):
        src = OUT_DIR / name
        dst = assets / name
        if name == "sleep.eaf" and dst.exists() and not (assets / "sleep.eaf.orig").exists():
            dst.rename(assets / "sleep.eaf.orig")      # 原厂那份留个底
        dst.write_bytes(src.read_bytes())
        print(f"安装 {dst}")


def main() -> None:
    ap = argparse.ArgumentParser(description="生成睡觉表情的 .eaf")
    ap.add_argument("--install", action="store_true", help="同时拷进 xiaozhi 的 emote 素材目录")
    ap.add_argument("--assets", default=str(ASSETS_DEFAULT), help="emote 素材目录")
    args = ap.parse_args()

    build(OUT_DIR, PREVIEW)
    if args.install:
        install(Path(args.assets).expanduser())


if __name__ == "__main__":
    main()
