#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ CarDockBar 字符串加密工具

用途
----
把源码里要保护的明文字符串（车机 SDK 类名 / 反射方法名 / 接口地址 / 车机包名）
转成 XOR+Base64 密文，粘回源码用。

用法
----
1) 生成密文（贴进源码）：
     python tools/enc_str.py "com.geely.hvac"

2) 批量：把一个文件里的所有 "..." 逐个加密并输出对照表：
     python tools/enc_str.py --file path/to/File.kt

密钥
----
与 Sec.kt 中的 KEY 完全一致（16 字节 XOR + Base64）。
**两端必须一致**，改一边就要改另一边。

为什么不用 const val
------------------
const val 是编译期常量，会被**内联进每一个调用点** —— 等于没加密。
所以敏感串必须写成 `val x = Sec.dec("密文", "key")`（运行时解密）。
"""

import base64
import re
import sys

# ★ 必须与 Sec.kt 的 KEY 逐字节一致
KEY = [
    0x5A, 0x17, 0xC3, 0x2E, 0x9B, 0x64, 0x38, 0xF1,
    0x4D, 0xA7, 0x12, 0xE5, 0x76, 0x8B, 0x03, 0xD9,
]


def enc(plain: str) -> str:
    """明文 → 密文（Base64(XOR)）"""
    b = plain.encode("utf-8")
    out = bytes(b[i] ^ KEY[i % len(KEY)] for i in range(len(b)))
    return base64.b64encode(out).decode("ascii")


def dec(cipher: str) -> str:
    """密文 → 明文（自检用）"""
    b = base64.b64decode(cipher)
    out = bytes(b[i] ^ KEY[i % len(KEY)] for i in range(len(b)))
    return out.decode("utf-8")


def main():
    args = sys.argv[1:]
    if not args:
        print(__doc__)
        return

    if args[0] == "--file":
        path = args[1]
        src = open(path, encoding="utf-8").read()
        # 只取长度 >= 4 的双引号字符串（跳过太短的）
        cands = [s for s in re.findall(r'"([^"\n]{4,})"', src)]
        seen, uniq = set(), []
        for c in cands:
            if c not in seen:
                seen.add(c)
                uniq.append(c)
        print(f"# 文件 {path} 里的候选字符串 {len(uniq)} 个\n")
        for c in uniq:
            e = enc(c)
            assert dec(e) == c, f"自检失败: {c}"
            print(f'"{e}"')
            print(f"   // {c}")
        print("\n# ✓ 全部自检通过（enc→dec 往返一致）")
        return

    for plain in args:
        e = enc(plain)
        assert dec(e) == plain
        print(f'"{e}"')
        print(f"   // {plain}")


if __name__ == "__main__":
    main()
