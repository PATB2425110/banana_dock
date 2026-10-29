"""把抠好的 5 个图标拼成一张对比图（深色底，模拟 Dock 实际效果），用于肉眼校验。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pngtool import read_png, write_png

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DST = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
OUT = os.path.join(ROOT, "preview", "icons_check.png")

CANVAS_H = 90
PAD = 12
BG0 = (24, 27, 32)     # Dock 底色附近
BG1 = (48, 52, 60)     # 另一半，用来验证透明


def main():
    imgs = []
    for name in ["ic_car.png", "ic_airflow.png", "ic_seat_vent.png",
                 "ic_recirc.png", "ic_seat.png"]:
        p = os.path.join(DST, name)
        if os.path.exists(p):
            imgs.append((name, read_png(p)))
    if not imgs:
        print("没有图标")
        return

    total_w = PAD + sum(w + PAD for _, (w, h, _) in imgs)
    canvas = bytearray(total_w * CANVAS_H * 4)
    for i in range(total_w * CANVAS_H):
        bg = BG0 if (i % total_w) < total_w // 2 else BG1
        canvas[i * 4:i * 4 + 4] = bytes((bg[0], bg[1], bg[2], 255))

    x = PAD
    for name, (w, h, rgba) in imgs:
        oy = (CANVAS_H - h) // 2
        for yy in range(h):
            for xx in range(w):
                si = (yy * w + xx) * 4
                a = rgba[si + 3] / 255.0
                if a <= 0:
                    continue
                tx, ty = x + xx, oy + yy
                if not (0 <= tx < total_w and 0 <= ty < CANVAS_H):
                    continue
                di = (ty * total_w + tx) * 4
                for c in range(3):
                    canvas[di + c] = int(round(rgba[si + c] * a + canvas[di + c] * (1 - a)))
        print(f"{name}: {w}x{h} @ x={x}")
        x += w + PAD

    write_png(OUT, total_w, CANVAS_H, canvas)
    print("→", OUT, f"{total_w}x{CANVAS_H}")


if __name__ == "__main__":
    main()
