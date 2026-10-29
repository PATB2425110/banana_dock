"""把 preview/dock_preview.html 里的 5 个手绘 SVG 图标换成真正抠出来的 PNG（base64 内嵌）。"""
import base64
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NODPI = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
HTML = os.path.join(ROOT, "preview", "dock_preview.html")

# HTML 里的 key → 资源名
MAP = {
    "car": "ic_car.png",
    "airflow": "ic_airflow.png",
    "vent": "ic_seat_vent.png",
    "recirc": "ic_recirc.png",
    "seat": "ic_seat.png",
}


def data_uri(path):
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode("ascii")
    return "data:image/png;base64," + b64


def main():
    with open(HTML, encoding="utf-8") as f:
        html = f.read()

    m = re.search(r"const SVG = \{.*?\n\};", html, re.S)
    if not m:
        raise SystemExit("没找到 const SVG 块")

    block = m.group(0)

    # 先取出 left/right/pause/note/home 的现有定义（这几个人工 SVG 保留）
    keep = {}
    for key in ["home", "left", "right", "pause", "note"]:
        km = re.search(r"\n\s*%s\s*:\s*`(.*?)`,?" % key, block, re.S)
        if km:
            keep[key] = km.group(1)

    lines = ["const SVG = {"]
    lines.append("  // ① 圆圈（人工矢量，截图里没给）")
    lines.append("  home: `%s`," % keep["home"])
    lines.append("  // 以下 5 个：直接从用户截图抠出来的位图（res/drawable-nodpi）")
    for key, fname in MAP.items():
        uri = data_uri(os.path.join(NODPI, fname))
        lines.append("  %s: `<img src=\"%s\" alt=\"%s\"/>`," % (key, uri, key))
    lines.append("  left: `%s`," % keep["left"])
    lines.append("  right:`%s`," % keep["right"])
    lines.append("  pause:`%s`," % keep["pause"])
    lines.append("  note:`%s`" % keep["note"])
    lines.append("};")

    html = html[:m.start()] + "\n".join(lines) + html[m.end():]

    # img 需要显式尺寸
    if ".ibtn img" not in html:
        html = html.replace(
            ".ibtn svg{display:block}",
            ".ibtn svg{display:block}\n  .ibtn img{display:block;width:26px;height:26px;object-fit:contain}"
        )

    with open(HTML, "w", encoding="utf-8") as f:
        f.write(html)
    print("已更新", HTML)


if __name__ == "__main__":
    main()
