#!/usr/bin/env python3
"""把 BMI260 的 init data 二进制转成固件里的 C 头文件。

BMI260（chip id 0x27）上电后必须把 8KB 配置写进芯片内部 RAM 才会输出数据，
否则加速度恒为 0。这段数据和 BMI270 的 config file 不同，不能互相替代。

数据来源：linux-firmware 仓库
  https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/bmi260-init-data.fw

用法：
  curl -sSLo /tmp/bmi260-init-data.fw \\
    https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git/plain/bmi260-init-data.fw
  python3 gen_bmi260_init.py /tmp/bmi260-init-data.fw \\
    ~/esp/xiaozhi-esp32/main/boards/espressif/esp-sparkbot/bmi260_init_data.h

校验：输入 8192 字节，sha256 应稳定。若上游换了内容，重新生成并复核。
"""

import hashlib
import sys

EXPECTED_SIZE = 8192

TEMPLATE_HEAD = """// ===========================================================================
// Bosch BMI260 init data（{size} 字节）
//
// 来源：linux-firmware 仓库的 bmi260-init-data.fw
//   https://git.kernel.org/pub/scm/linux/kernel/git/firmware/linux-firmware.git
//   sha256: {sha}
//
// BMI260 上电后处于 suspend，且加速度/陀螺默认不输出数据，必须把这 8KB
// 配置写进芯片内部 RAM 才会工作。这段数据与 BMI270 的 config file 不同
// （实测 8192 字节里 5662 字节不一样），不能互相替代。
// 由 firmware/tools/gen_bmi260_init.py 生成，请勿手工编辑。
// ===========================================================================
#pragma once

#include <cstdint>

namespace Bmi260InitData {{

constexpr uint32_t kSize = {size};

constexpr uint8_t kData[kSize] = {{
"""

TEMPLATE_TAIL = """};

}  // namespace Bmi260InitData
"""


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    src, dst = sys.argv[1], sys.argv[2]
    data = open(src, "rb").read()
    if len(data) != EXPECTED_SIZE:
        print(f"错误：{src} 是 {len(data)} 字节，BMI260 init data 应为 {EXPECTED_SIZE}")
        return 1

    sha = hashlib.sha256(data).hexdigest()
    out = [TEMPLATE_HEAD.format(size=len(data), sha=sha)]
    for i in range(0, len(data), 16):
        chunk = data[i : i + 16]
        out.append("    " + " ".join(f"0x{b:02X}," for b in chunk) + "\n")
    out.append(TEMPLATE_TAIL)

    with open(dst, "w") as f:
        f.writelines(out)
    print(f"已生成 {dst}（{len(data)} 字节，sha256 {sha[:16]}…）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
