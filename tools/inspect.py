"""分析 5 张图标截图：尺寸、alpha 分布、四角颜色，判断背景是什么。"""
import os
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngtool import read_png

SRC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   "preview", "icons_src")


def px(rgba, w, x, y):
    i = (y * w + x) * 4
    return tuple(rgba[i:i + 4])


for name in sorted(os.listdir(SRC)):
    if not name.endswith(".png"):
        continue
    w, h, rgba = read_png(os.path.join(SRC, name))
    alphas = Counter(rgba[i] for i in range(3, len(rgba), 4))
    corners = [px(rgba, w, 0, 0), px(rgba, w, w - 1, 0),
               px(rgba, w, 0, h - 1), px(rgba, w, w - 1, h - 1)]
    top_alpha = alphas.most_common(4)
    print(f"\n=== {name}  {w}x{h} ===")
    print("  alpha 分布(前4):", top_alpha)
    print("  四角像素:", corners)
    # 最亮的像素（图标本体）
    best = max(range(w * h),
               key=lambda i: rgba[i * 4] + rgba[i * 4 + 1] + rgba[i * 4 + 2])
    print("  最亮像素:", px(rgba, w, best % w, best // w), "at", (best % w, best // w))
