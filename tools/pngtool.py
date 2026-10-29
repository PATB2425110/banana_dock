"""纯标准库 PNG 读/写（不依赖 Pillow）。用于把截图里的图标抠成透明底 PNG。"""
import struct
import zlib


def read_png(path):
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "不是 PNG"
    pos = 8
    idat = bytearray()
    w = h = bitdepth = coltype = None
    plte = None
    trns = None
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bitdepth, coltype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert interlace == 0, "不支持隔行扫描"
        elif typ == b"PLTE":
            plte = body
        elif typ == b"tRNS":
            trns = body
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        pos += 12 + ln

    assert bitdepth == 8, f"只支持 8bit，实际 {bitdepth}"
    channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[coltype]
    raw = zlib.decompress(bytes(idat))

    stride = w * channels
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ft == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif ft == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ft == 3:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ft == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = prev[i]
                c = prev[i - channels] if i >= channels else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line

    # 统一转成 RGBA
    rgba = bytearray(w * h * 4)
    for i in range(w * h):
        if coltype == 6:
            r, g, b, a = out[i * 4:i * 4 + 4]
        elif coltype == 2:
            r, g, b = out[i * 3:i * 3 + 3]
            a = 255
        elif coltype == 0:
            r = g = b = out[i]
            a = trns[out[i]] if trns and out[i] < len(trns) else 255
        elif coltype == 3:
            idx = out[i]
            r, g, b = plte[idx * 3:idx * 3 + 3]
            a = trns[idx] if trns and idx < len(trns) else 255
        else:  # coltype 4 = 灰度+alpha
            r = g = b = out[i * 2]
            a = out[i * 2 + 1]
        rgba[i * 4:i * 4 + 4] = bytes((r, g, b, a))
    return w, h, rgba


def write_png(path, w, h, rgba):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw += rgba[y * w * 4:(y + 1) * w * 4]

    def chunk(typ, body):
        return (struct.pack(">I", len(body)) + typ + body +
                struct.pack(">I", zlib.crc32(typ + body) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)
