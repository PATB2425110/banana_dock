"""
把截图里的图标抠成透明底 PNG，直接作为 Android drawable 使用。

原理：截图是不透明的单色底（#202429）+ 亮色图标。
  1. 用"与背景色的最大通道差"估计每个像素的 alpha（抗锯齿边缘 → 半透明）
  2. 反预乘还原真实颜色：color = bg + (obs - bg) / alpha
  3. 裁掉四周空白，等比缩放进 64x64（保持比例，不拉伸）
  4. 输出 res/drawable-nodpi/ic_xxx.png

不依赖任何第三方库（纯 zlib + struct）。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngtool import read_png, write_png

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "preview", "icons_src")
DST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")

BG = (32, 36, 41)          # 截图底色
DEAD_ZONE = 7               # 小于这个差值直接视为背景
NORM = 205.0                # 归一化系数：差值达到它就认为完全不透明
TARGET = 64                 # 输出尺寸上限（保持比例）


def key_to_alpha(rgba, w, h):
    """→ (alpha_map, color_map)，color_map 是反预乘后的 RGB"""
    alpha = [0.0] * (w * h)
    color = bytearray(w * h * 3)
    for i in range(w * h):
        r, g, b, _ = rgba[i * 4:i * 4 + 4]
        dr, dg, db = r - BG[0], g - BG[1], b - BG[2]
        d = max(abs(dr), abs(dg), abs(db))
        if d <= DEAD_ZONE:
            alpha[i] = 0.0
            continue
        a = min(1.0, d / NORM)
        alpha[i] = a
        # 反预乘
        cr = BG[0] + dr / a
        cg = BG[1] + dg / a
        cb = BG[2] + db / a
        color[i * 3] = max(0, min(255, int(round(cr))))
        color[i * 3 + 1] = max(0, min(255, int(round(cg))))
        color[i * 3 + 2] = max(0, min(255, int(round(cb))))
    return alpha, color


def bbox(alpha, w, h, thresh=0.06):
    x0, y0, x1, y1 = w, h, -1, -1
    for y in range(h):
        row = alpha[y * w:(y + 1) * w]
        for x in range(w):
            if row[x] > thresh:
                if x < x0: x0 = x
                if x > x1: x1 = x
                if y < y0: y0 = y
                if y > y1: y1 = y
    if x1 < 0:
        return None
    return x0, y0, x1 + 1, y1 + 1


def sample(color, alpha, sw, sh, sx, sy):
    """盒式采样（按 alpha 加权），用于缩小时的平滑"""
    if 0 <= sx < sw and 0 <= sy < sh:
        i = sy * sw + sx
        return color[i * 3], color[i * 3 + 1], color[i * 3 + 2], alpha[i]
    return 0, 0, 0, 0.0


def resize(color, alpha, sw, sh, box, tw, th):
    """把 box 区域缩放到 tw x th —— 预乘 alpha 的双线性插值（放大/缩小都平滑）"""
    x0, y0, x1, y1 = box
    bw, bh = x1 - x0, y1 - y0
    fx = bw / tw
    fy = bh / th
    out_a = [0.0] * (tw * th)
    out_c = bytearray(tw * th * 3)

    def premul(sx, sy):
        """返回 (r*a, g*a, b*a, a)"""
        cx = min(max(int(sx), 0), sw - 1)
        cy = min(max(int(sy), 0), sh - 1)
        i = cy * sw + cx
        a = alpha[i]
        if a <= 0:
            return 0.0, 0.0, 0.0, 0.0
        return color[i * 3] * a, color[i * 3 + 1] * a, color[i * 3 + 2] * a, a

    for ty in range(th):
        sy = y0 + (ty + 0.5) * fy - 0.5
        sy = max(sy, 0.0)
        iy = int(sy)
        wy = sy - iy
        for tx in range(tw):
            sx = x0 + (tx + 0.5) * fx - 0.5
            sx = max(sx, 0.0)
            ix = int(sx)
            wx = sx - ix

            p00 = premul(ix, iy)
            p10 = premul(ix + 1, iy)
            p01 = premul(ix, iy + 1)
            p11 = premul(ix + 1, iy + 1)

            top_r = p00[0] * (1 - wx) + p10[0] * wx
            top_g = p00[1] * (1 - wx) + p10[1] * wx
            top_b = p00[2] * (1 - wx) + p10[2] * wx
            top_a = p00[3] * (1 - wx) + p10[3] * wx

            bot_r = p01[0] * (1 - wx) + p11[0] * wx
            bot_g = p01[1] * (1 - wx) + p11[1] * wx
            bot_b = p01[2] * (1 - wx) + p11[2] * wx
            bot_a = p01[3] * (1 - wx) + p11[3] * wx

            r = top_r * (1 - wy) + bot_r * wy
            g = top_g * (1 - wy) + bot_g * wy
            b = top_b * (1 - wy) + bot_b * wy
            a = top_a * (1 - wy) + bot_a * wy

            i = ty * tw + tx
            if a > 0.001:
                out_a[i] = min(1.0, a)
                out_c[i * 3] = max(0, min(255, int(round(r / a))))
                out_c[i * 3 + 1] = max(0, min(255, int(round(g / a))))
                out_c[i * 3 + 2] = max(0, min(255, int(round(b / a))))
    return out_c, out_a


def main():
    os.makedirs(DST, exist_ok=True)
    for name in sorted(os.listdir(SRC)):
        if not name.endswith(".png"):
            continue
        w, h, rgba = read_png(os.path.join(SRC, name))
        alpha, color = key_to_alpha(rgba, w, h)
        box = bbox(alpha, w, h)
        if box is None:
            print(f"!! {name}: 没找到图标像素，跳过")
            continue
        x0, y0, x1, y1 = box
        bw, bh = x1 - x0, y1 - y0
        # 统一缩放到 max(bw,bh) = TARGET，保证各图标"视觉尺寸"一致
        scale = TARGET / max(bw, bh)
        tw = max(1, int(round(bw * scale)))
        th = max(1, int(round(bh * scale)))
        c2, a2 = resize(color, alpha, w, h, box, tw, th)

        out = bytearray(tw * th * 4)
        for i in range(tw * th):
            out[i * 4] = c2[i * 3]
            out[i * 4 + 1] = c2[i * 3 + 1]
            out[i * 4 + 2] = c2[i * 3 + 2]
            out[i * 4 + 3] = int(round(a2[i] * 255))
        dst = os.path.join(DST, name)
        write_png(dst, tw, th, out)
        opaque = sum(1 for i in range(tw * th) if out[i * 4 + 3] > 200)
        print(f"✓ {name}: 裁剪 {bw}x{bh} → {tw}x{th}  "
              f"不透明像素 {opaque}/{tw * th}")


if __name__ == "__main__":
    main()
