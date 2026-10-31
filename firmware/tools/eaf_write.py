#!/usr/bin/env python3
"""把 PIL 画面编码成 .eaf（emote 组件的私有动画容器）。

只实现解码器支持的一个子集：8bit 调色板 + 块内 RLE（编码类型 0），
够画图形化的表情，也不依赖 Huffman 编码器。

格式（对照 esp_emote_gfx/src/lib/eaf/gfx_eaf_dec.c）：
    文件头  0x89 'E' 'A' 'F' | 帧数 u32 | 校验和 u32 | 表长 u32
    帧表    每帧 8 字节：size u32（含 2 字节魔数）、offset u32（相对帧数据区起点）
    帧      0x5A5A | "_S" | 0x00 | version[6] | depth u8 |
            w u16 | h u16 | blocks u16 | block_h u16 |
            块长 blocks×u32 | 调色板 (1<<depth)×4（B,G,R,A）| 块数据
    块      首字节是编码类型（0=RLE），其后是 (count u8, value u8) 对

调色板索引 0 固定留给全 0（解码器把全 0 当透明）。校验和是
[16, 16+表长) 这段的字节和，解码时逐字节比对，算错直接 ESP_ERR_INVALID_CRC。
"""

from __future__ import annotations

import struct
from pathlib import Path

from PIL import Image

TRANSPARENT = (0, 0, 0, 0)


def _palette_and_index(images: list[Image.Image]):
    """所有帧共用一套调色板；索引 0 永远是透明。"""
    colors = {TRANSPARENT}
    for im in images:
        colors.update(im.convert("RGBA").getdata())
    ordered = [TRANSPARENT] + sorted(c for c in colors if c != TRANSPARENT)
    if len(ordered) > 256:
        raise ValueError(f"颜色太多：{len(ordered)}，8bit 调色板最多 256 色")
    lookup = {c: i for i, c in enumerate(ordered)}
    return ordered, lookup


def _rle(samples: bytes) -> bytes:
    """(count, value) 对，count 是 uint8，最长 255。"""
    out = bytearray()
    i, n = 0, len(samples)
    while i < n:
        value = samples[i]
        j = i + 1
        while j < n and samples[j] == value and j - i < 255:
            j += 1
        out += bytes((j - i, value))
        i = j
    return bytes(out)


def encode_frame(indices: bytes, w: int, h: int, palette: list, block_h: int = 32) -> bytes:
    blocks = (h + block_h - 1) // block_h
    block_data = []
    for bi in range(blocks):
        rows = indices[bi * block_h * w:min((bi + 1) * block_h, h) * w]
        # 最后一块不满就用透明补齐，解码端按整块尺寸算偏移
        rows = bytes(rows) + bytes(block_h * w - len(rows))
        block_data.append(b"\x00" + _rle(rows))   # 0 = RLE

    palette_bytes = b"".join(bytes((b, g, r, a)) for (r, g, b, a) in palette)
    palette_bytes += bytes(256 * 4 - len(palette_bytes))   # 补齐到 256 条

    payload = b"_S\x00" + bytes((0, 0, 0, 0, 0, 1)) + bytes((8,))
    payload += struct.pack("<HHHH", w, h, blocks, block_h)
    payload += struct.pack(f"<{blocks}I", *(len(b) for b in block_data))
    payload += palette_bytes
    payload += b"".join(block_data)
    return b"\x5a\x5a" + payload


def write_eaf(path: Path, images: list[Image.Image], block_h: int = 32) -> int:
    """把若干张同样大小的 RGBA 图编码成 .eaf，返回文件字节数。"""
    if not images:
        raise ValueError("至少要给一帧")
    w, h = images[0].size
    for im in images:
        if im.size != (w, h):
            raise ValueError("所有帧尺寸必须一致")

    palette, lookup = _palette_and_index(images)
    frames = []
    for im in images:
        idx = bytes(lookup[c] for c in im.convert("RGBA").getdata())
        frames.append(encode_frame(idx, w, h, palette, block_h))

    table_len = len(frames) * 8 + sum(len(f) for f in frames)
    body = bytearray()
    offset = 0
    for frame in frames:
        body += struct.pack("<II", len(frame), offset)
        offset += len(frame)
    body += b"".join(frames)

    header = bytearray(b"\x89EAF")
    header += struct.pack("<I", len(frames))
    header += struct.pack("<I", sum(body) & 0xFFFFFFFF)
    header += struct.pack("<I", table_len)

    data = bytes(header) + bytes(body)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return len(data)
