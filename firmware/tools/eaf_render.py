#!/usr/bin/env python3
"""把固件 assets 里的 .eaf 表情解码成图片，用于文档与排查。

.eaf 是乐鑫 emote 组件私有的动画容器：整片按 32px 高切块，块内再走
RLE / Huffman 压缩，每块输出 width×block_height 个 8bit 调色板索引。
本脚本对照 esp_emote_gfx/src/lib/eaf/gfx_eaf_dec.c 实现解码，只依赖 Pillow。

用法：
    python3 firmware/tools/eaf_render.py                 # 出总览图 + 十宫格动图
    python3 firmware/tools/eaf_render.py --list          # 只打印表情清单
    python3 firmware/tools/eaf_render.py --only Happy    # 只导出单个表情的 GIF

素材不在本仓库里（属于 espressif/esp_emote_assets 组件），默认去
xiaozhi-esp32 的 managed_components 下找，可用 --assets 指定。
"""

from __future__ import annotations

import argparse
import struct
import sys
import textwrap
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("需要 Pillow：python3 -m pip install pillow")

# ---------------------------------------------------------------- 表情清单

ASSETS_DEFAULT = ("~/esp/xiaozhi-esp32/managed_components/"
                  "espressif2022__esp_emote_assets/emoji_large")

# 与 assets/240_240/emote.json 一致：21 个表情名只对应 10 个动画文件。
# (文件, 中文名, 服务端下发的表情名)
EMOTES = [
    ("Happy", "开心", "happy / laughing / funny / loving / embarrassed / confident / delicious / silly / relaxed"),
    ("Sad", "难过", "sad"),
    ("cry", "哭泣", "crying"),
    ("sleep", "睡觉", "sleepy"),
    ("angry", "生气", "angry"),
    ("shocked", "惊讶", "surprised / shocked"),
    ("confused", "困惑", "thinking / confused"),
    ("winking", "眨眼", "winking"),
    ("neutral", "平静", "neutral / idle / robot_2"),
    ("listen", "聆听", "不走表情接口，语音唤醒时自动出现"),
]

# 屏幕上唯一的表情控件是 layout.json 里的 eye_anim：
# LEFT_MID + x=-5, y=8，mirror=auto —— 所以 .eaf 里其实是一只眼，镜像出另一只。
SCREEN = 240
EYE_X, EYE_Y = -5, 8
BG = (12, 13, 16)

FONT_CANDIDATES = [
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/Library/Fonts/Arial Unicode.ttf",
]

# ---------------------------------------------------------------- eaf 解码


def _huffman(blob: bytes, table_bytes: bytes, out_len: int) -> bytes:
    """按字典逐位走码表。table_bytes: padding | (symbol, code_len, code_bytes)*"""
    padding = table_bytes[0]
    pos, codes, max_len = 1, {}, 0
    while pos + 1 < len(table_bytes):
        symbol, code_len = table_bytes[pos], table_bytes[pos + 1]
        pos += 2
        nbytes = (code_len + 7) // 8
        if pos + nbytes > len(table_bytes) or code_len == 0:
            break
        code = 0
        for i in range(nbytes):
            code = (code << 8) | table_bytes[pos + i]
        pos += nbytes
        codes[(code_len, code)] = symbol
        max_len = max(max_len, code_len)

    out, code, bits = bytearray(), 0, 0
    for bit_index in range(len(blob) * 8 - padding):
        code = (code << 1) | ((blob[bit_index >> 3] >> (7 - (bit_index & 7))) & 1)
        bits += 1
        symbol = codes.get((bits, code))
        if symbol is not None:
            out.append(symbol)
            code = bits = 0
            if len(out) >= out_len:
                break
        elif bits > max_len:
            break
    return bytes(out)


def _rle(data: bytes, out_len: int) -> bytes:
    out, i = bytearray(), 0
    while i + 1 < len(data) and len(out) < out_len:
        out.extend(bytes([data[i + 1]]) * data[i])
        i += 2
    return bytes(out[:out_len])


def _decode_block(enc: int, payload: bytes, out_len: int) -> bytes:
    if enc == 0:                                    # RLE
        return _rle(payload, out_len)
    if enc in (1, 3):                               # Huffman(+RLE) / Huffman
        dict_size = struct.unpack_from("<H", payload, 0)[0]
        table_bytes = payload[2:2 + dict_size]
        bits = payload[2 + dict_size:]
        if not bits:                                # 单色块：字典只有一条
            return bytes([table_bytes[1]]) * out_len
        raw = _huffman(bits, table_bytes, out_len * 2 if enc == 1 else out_len)
        return _rle(raw, out_len) if enc == 1 else raw[:out_len]
    if enc == 5:                                    # 未压缩
        return payload[:out_len]
    raise ValueError(f"未知的编码类型 {enc}")


def decode_eaf(path: Path):
    """返回 ((w, h), [(rgba_bytes, ...)])；RGBA 已展开调色板。"""
    data = path.read_bytes()
    if data[:1] != b"\x89" or data[1:4] not in (b"EAF", b"AAF"):
        raise ValueError(f"{path.name} 不是 eaf 文件")
    nframes = struct.unpack_from("<i", data, 4)[0]
    table = [struct.unpack_from("<II", data, 16 + i * 8) for i in range(nframes)]
    base = 16 + nframes * 8

    frames, size = [], None
    for _size, offset in table:
        f = data[base + offset + 2:]                 # 跳过 0x5A5A 帧魔数
        if f[:2] == b"_C":                           # 控制帧（loop/online 之类），无像素
            continue
        if f[:2] != b"_S":
            continue
        depth = f[9]
        w, h, blocks, block_h = struct.unpack_from("<HHHH", f, 10)
        size = (w, h)
        block_len = struct.unpack_from(f"<{blocks}I", f, 18)

        ncolors = 1 << depth if depth != 24 else 0
        pal_off = 18 + blocks * 4
        palette = [(f[pal_off + i * 4 + 2], f[pal_off + i * 4 + 1], f[pal_off + i * 4],
                    f[pal_off + i * 4 + 3]) for i in range(ncolors)]   # B,G,R,A -> R,G,B,A
        data_off = pal_off + ncolors * 4

        idx = bytearray(w * h)
        off = data_off
        for bi in range(blocks):
            blen = block_len[bi]
            block = f[off:off + blen]
            off += blen
            y0 = bi * block_h
            rows = min(block_h, h - y0)
            if rows <= 0:
                break
            pix = _decode_block(block[0], block[1:], w * block_h)
            idx[y0 * w:(y0 + rows) * w] = pix[:rows * w]

        rgba = bytearray(w * h * 4)
        for i, ci in enumerate(idx):
            rgba[i * 4:i * 4 + 4] = bytes(palette[ci])
        frames.append(bytes(rgba))
    return size, frames

# ---------------------------------------------------------------- 渲染


def _font(size: int):
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def screen_frame(rgba: bytes, w: int, h: int, size: int = SCREEN, top_mid: bool = False):
    """把一只眼贴成一对，还原成 240×240 屏幕的效果图。"""
    canvas = Image.new("RGBA", (SCREEN, SCREEN), (0, 0, 0, 255))
    eye = Image.frombytes("RGBA", (w, h), rgba)
    if top_mid:                                       # listen：顶部居中波形，不镜像
        canvas.alpha_composite(eye, ((SCREEN - w) // 2, 4))
    else:
        canvas.alpha_composite(eye, (EYE_X, EYE_Y))
        canvas.alpha_composite(eye.transpose(Image.FLIP_LEFT_RIGHT),
                               (SCREEN - (w + EYE_X), EYE_Y))
    if size != SCREEN:
        canvas = canvas.resize((size, size), Image.LANCZOS)
    return canvas.convert("RGB")


def build_sheet(assets: Path, out: Path, frame_px: int = 150, ncols: int = 4):
    label_w, gap, row_h, top = 306, 10, frame_px + 18, 96
    width = label_w + ncols * (frame_px + gap) + 28
    height = top + len(EMOTES) * row_h + 26
    sheet = Image.new("RGB", (width, height), BG)
    d = ImageDraw.Draw(sheet)
    f_cn, f_meta = _font(26), _font(15)
    f_sub, f_title, f_small = _font(13), _font(34), _font(16)

    d.text((34, 30), "ESP-SparkBot 表情一览", font=f_title, fill=(240, 242, 245))
    d.text((34, 72), "10 个动画 · 20 fps · 240×240 屏上左右镜像成一双眼睛",
           font=f_small, fill=(130, 136, 146))

    for row, (name, cn, mapping) in enumerate(EMOTES):
        y = top + row * row_h
        (w, h), frames = decode_eaf(assets / f"{name}.eaf")
        n = len(frames)
        picks = [round(i * (n - 1) / (ncols - 1)) for i in range(ncols)]

        d.text((34, y + 22), cn, font=f_cn, fill=(235, 238, 242))
        d.text((34, y + 60), f"{name}.eaf · {n} 帧 · {w}×{h}", font=f_meta, fill=(122, 130, 142))
        for i, line in enumerate(textwrap.wrap(mapping, 40)[:4]):
            d.text((34, y + 88 + i * 19), line, font=f_sub, fill=(98, 106, 118))

        for col, fi in enumerate(picks):
            x = label_w + col * (frame_px + gap)
            sheet.paste(screen_frame(frames[fi], w, h, frame_px, top_mid=(name == "listen")), (x, y))
            d.rectangle([x, y, x + frame_px - 1, y + frame_px - 1], outline=(38, 41, 46))
            d.text((x + 6, y + 4), f"#{fi}", font=f_sub, fill=(72, 78, 88))

    sheet.save(out)
    return sheet.size


def build_gif(assets: Path, out: Path, cell: int = 120, fps: int = 20, max_frames: int = 72):
    cols, rows, pad, label_h = 5, 2, 8, 26
    W = cols * cell + (cols + 1) * pad
    H = rows * (cell + label_h) + (rows + 1) * pad
    f_cn, f_small = _font(16), _font(13)

    data = []
    for name, cn, _ in EMOTES:
        (w, h), frames = decode_eaf(assets / f"{name}.eaf")
        data.append((name, cn, w, h, frames))

    total = min(max(len(d[4]) for d in data), max_frames)
    out_frames = []
    for t in range(total):
        canvas = Image.new("RGB", (W, H), BG)
        d = ImageDraw.Draw(canvas)
        for idx, (name, cn, w, h, frames) in enumerate(data):
            x = pad + (idx % cols) * (cell + pad)
            y = pad + (idx // cols) * (cell + label_h + pad)
            canvas.paste(screen_frame(frames[min(t, len(frames) - 1)], w, h, cell,
                                      top_mid=(name == "listen")), (x, y + label_h))
            d.text((x + 2, y + 4), cn, font=f_cn, fill=(235, 238, 242))
            d.text((x + 36, y + 7), f"{name}.eaf", font=f_small, fill=(110, 118, 130))
        out_frames.append(canvas)

    out_frames[0].save(out, save_all=True, append_images=out_frames[1:],
                       duration=int(1000 / fps), loop=0, optimize=True)
    return (W, H), total


def build_single(assets: Path, out: Path, name: str, cell: int = 240, fps: int = 20):
    (w, h), frames = decode_eaf(assets / f"{name}.eaf")
    imgs = [screen_frame(f, w, h, cell, top_mid=(name == "listen")) for f in frames]
    imgs[0].save(out, save_all=True, append_images=imgs[1:],
                 duration=int(1000 / fps), loop=0, optimize=True)
    return len(imgs)


def main():
    ap = argparse.ArgumentParser(description="把 .eaf 表情渲染成 PNG/GIF")
    ap.add_argument("--assets", default=ASSETS_DEFAULT, help="emoji_large 目录")
    ap.add_argument("--out", default="docs/images", help="输出目录")
    ap.add_argument("--list", action="store_true", help="只打印清单，不渲染")
    ap.add_argument("--only", help="只导出某个表情的 GIF，比如 Happy")
    args = ap.parse_args()

    assets = Path(args.assets).expanduser()
    if not assets.is_dir():
        sys.exit(f"找不到素材目录：{assets}\n用 --assets 指向 esp_emote_assets 的 emoji_large")

    if args.list:
        for name, cn, mapping in EMOTES:
            (w, h), frames = decode_eaf(assets / f"{name}.eaf")
            print(f"{cn:　<3} {name:<9} {len(frames):>3} 帧  {w}×{h}  ← {mapping}")
        return

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.only:
        path = out_dir / f"emote-{args.only}.gif"
        n = build_single(assets, path, args.only)
        print(f"{path}  {n} 帧  {path.stat().st_size / 1024:.0f} KB")
        return

    p1 = out_dir / "emotions-grid.png"
    size = build_sheet(assets, p1)
    print(f"{p1}  {size[0]}×{size[1]}  {p1.stat().st_size / 1024:.0f} KB")

    p2 = out_dir / "emotions.gif"
    size, n = build_gif(assets, p2)
    print(f"{p2}  {size[0]}×{size[1]}  {n} 帧  {p2.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    main()
