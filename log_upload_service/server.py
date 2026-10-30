#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CarDockBar 日志接收服务（线上部署版，仅 Python 标准库，零依赖）

用途：接收车机 App（CarDockBar LogUploader）POST 上来的 JSON 日志，
按「一天一个文件」落盘成 JSONL，并提供健康检查 / 查看 / 下载接口，
用来回答一个问题：**车机那边的日志到底传没传出来。**

与根目录 log_server.py（局域网自用版）的区别：
  - 线上部署平台通过环境变量 PORT 下发监听端口，本服务必须读它；
  - 只绑 0.0.0.0（容器里没有"局域网地址"可打印，也不需要 IPv6 探测）；
  - 增加 /health /logs /download 接口，便于远程确认链路、取回日志。

运行：
  PORT=8080 python server.py        # 不传 PORT 时默认 8080（本地调试用）

接口：
  POST  任意路径            接收日志（App 直接 POST JSON，路径随意，/upload 只是习惯）
  GET   /                   迷你查看页（最近记录表格，30 秒自动刷新）
  GET   /health             健康检查 -> {"ok": true}
  GET   /logs               最近 200 条记录（JSON，?date=YYYY-MM-DD 可指定某天）
  GET   /download           全部日志（纯文本，按天分隔；?date=YYYY-MM-DD 只下一天）
  GET   /favicon.ico        204

落盘位置：./logs/YYYY-MM-DD.jsonl（一行一条 JSON）。这就是按天归档：
跨天自动换新文件，同一天超过 16MB 续写 YYYY-MM-DD.1.jsonl，
超过 LOG_KEEP_DAYS（默认 30 天）的日期文件自动删除。

容量控制（车机日志特别多也不怕）：
  - App 端单条 payload 的日志正文已截断到 16000 字符，单条约 20-40KB；
  - 单文件超过 LOG_SPLIT_BYTES（默认 16MB）自动续写到 <日期>.1.jsonl / <日期>.2.jsonl ……
  - 超过 LOG_KEEP_DAYS（默认 30 天）的日志每天自动清理一次（LOG_KEEP_DAYS=0 则全部保留）；
  - 无上传频率限制；总量只受部署沙箱磁盘配额约束，按正常排查用量远够。
"""

import json
import os
import threading
from datetime import datetime, timedelta
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

# ---------------------------------------------------------------- 配置
# 部署平台用 PORT 环境变量告诉服务监听哪个端口，必须读它；本地不传时默认 8080。
PORT = int(os.environ.get("PORT") or "8080")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
LOG_DIR = os.path.join(BASE_DIR, "logs")
MAX_BODY = 8 * 1024 * 1024          # 单条 payload 上限 8MB，防御性限制
KEEP_DAYS = int(os.environ.get("LOG_KEEP_DAYS") or "30")   # 日志保留天数，超期自动清理
SPLIT_BYTES = int(os.environ.get("LOG_SPLIT_BYTES") or str(16 * 1024 * 1024))  # 单文件上限，超出续写 .1/.2

os.makedirs(LOG_DIR, exist_ok=True)
_lock = threading.Lock()            # 多线程并发写，加锁防串行


# ---------------------------------------------------------------- 存储
def day_files():
    """已落盘的日期文件（新的在前）"""
    try:
        return sorted(
            (f for f in os.listdir(LOG_DIR) if f.endswith(".jsonl")),
            reverse=True,
        )
    except FileNotFoundError:
        return []


def save_record(obj: dict) -> str:
    """一条记录 -> 当天的 .jsonl 文件。返回文件名。

    单日文件超过 SPLIT_BYTES 时自动续写到 <日期>.1.jsonl / <日期>.2.jsonl ……
    这样任何单个文件都有上限，下载时不会出现一个巨型文件。
    （正常情况下永远用不到：App 单条 payload 只有 ~40KB。）
    """
    day = datetime.now().strftime("%Y-%m-%d")
    path = os.path.join(LOG_DIR, day + ".jsonl")
    n = 1
    while os.path.exists(path) and os.path.getsize(path) >= SPLIT_BYTES:
        path = os.path.join(LOG_DIR, "%s.%d.jsonl" % (day, n))
        n += 1
    with _lock, open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")
    return os.path.basename(path)


_last_cleanup_day = [""]


def cleanup_old(force: bool = False) -> int:
    """删除超过 KEEP_DAYS 天的日志文件（按文件名里的日期判断）。返回删除个数。

    默认每天只跑一次（跨天时触发），避免高频写入时反复 listdir。
    KEEP_DAYS <= 0 表示不自动清理（全部保留）。
    """
    today = datetime.now().strftime("%Y-%m-%d")
    if not force and _last_cleanup_day[0] == today:
        return 0
    _last_cleanup_day[0] = today
    if KEEP_DAYS <= 0:
        return 0
    cutoff = datetime.now() - timedelta(days=KEEP_DAYS)
    removed = 0
    for name in day_files():
        # 文件名形如 2026-10-19.jsonl 或 2026-10-19.1.jsonl，取前 10 位当日期
        try:
            d = datetime.strptime(name[:10], "%Y-%m-%d")
        except ValueError:
            continue
        if d < cutoff:
            try:
                os.remove(os.path.join(LOG_DIR, name))
                removed += 1
                print(
                    "[%s] 清理过期日志 -> %s" % (datetime.now().strftime("%H:%M:%S"), name),
                    flush=True,
                )
            except OSError:
                pass
    return removed


def part_index(name: str) -> int:
    """2026-10-29.jsonl -> 0，2026-10-29.1.jsonl -> 1。"""
    stem = name[:-len(".jsonl")] if name.endswith(".jsonl") else name
    dot = stem.find(".", 10)
    if dot < 0:
        return 0
    try:
        return int(stem[dot + 1:])
    except ValueError:
        return 0


def files_for(date: str = ""):
    """某一天的全部分片，按写入顺序（.jsonl、.1、.2）。

    不带 date 时保持 day_files() 的「新日期在前」。
    同一天的分片必须从小到大读，后面的 reverse 才能让最新记录排在前面。
    """
    names = [n for n in day_files() if n.endswith(".jsonl")]
    if not date:
        return names
    matched = []
    for name in names:
        stem = name[:-len(".jsonl")]
        if stem == date or stem.startswith(date + "."):
            matched.append(name)
    return sorted(matched, key=part_index)


def load_records(date: str = "", limit: int = 0):
    """读记录，新的在前。date 为空读全部天数。"""
    names = files_for(date)
    rows = []
    for name in names:
        try:
            with open(os.path.join(LOG_DIR, name), encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        o = json.loads(line)
                    except Exception:
                        continue
                    o["_file"] = name
                    rows.append(o)
        except OSError:
            continue
    rows.reverse()
    return rows[:limit] if limit else rows


def one_line(text: str, n: int = 140) -> str:
    return " ".join((text or "").split())[:n]


# ---------------------------------------------------------------- HTTP
class Handler(BaseHTTPRequestHandler):
    server_version = "CarDockBarLog/1.0"

    # 静默默认访问日志，只打我们自己的一行摘要
    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, body, ctype: str = "application/json; charset=utf-8"):
        if isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, code: int, obj):
        self._send(code, json.dumps(obj, ensure_ascii=False))

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---------------- POST：收日志 ----------------
    def do_POST(self):
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = 0
        raw = self.rfile.read(min(length, MAX_BODY)) if length > 0 else b""
        text = raw.decode("utf-8", "replace")
        now = datetime.now()

        try:
            obj = json.loads(text)
        except Exception:
            obj = None

        if isinstance(obj, dict):
            obj.setdefault("received_at", now.isoformat(timespec="seconds"))
            obj.setdefault("src_ip", self.client_address[0])
            try:
                name = save_record(obj)
            except OSError as e:
                self._json(500, {"ok": False, "error": "save failed: %s" % e})
                return
            print(
                "[%s] 收到 -> %s  kind=%s  device=%s  ip=%s"
                % (
                    now.strftime("%H:%M:%S"),
                    name,
                    obj.get("kind", "?"),
                    obj.get("device", "?"),
                    obj.get("src_ip"),
                ),
                flush=True,
            )
            # App 端判定：2xx 即成功
            cleanup_old()
            self._json(200, {"ok": True, "saved": name})
        else:
            # 非 JSON 也原样存下来，别丢
            rec = {
                "kind": "raw",
                "received_at": now.isoformat(timespec="seconds"),
                "src_ip": self.client_address[0],
                "path": self.path,
                "ua": self.headers.get("User-Agent", ""),
                "body": text[:MAX_BODY],
            }
            name = save_record(rec)
            print(
                "[%s] 收到(非JSON) -> %s  path=%s  ip=%s"
                % (now.strftime("%H:%M:%S"), name, self.path, rec["src_ip"]),
                flush=True,
            )
            self._json(200, {"ok": True, "saved": name, "note": "body 不是 JSON，已按 raw 存储"})
            cleanup_old()

    # ---------------- GET：查看 / 健康检查 / 下载 ----------------
    def do_GET(self):
        u = urlparse(self.path)
        path = u.path.rstrip("/") or "/"
        q = parse_qs(u.query)
        date = (q.get("date") or [""])[0]

        if path == "/favicon.ico":
            return self._send(204, b"")
        if path == "/health":
            return self._json(
                200,
                {
                    "ok": True,
                    "service": "cardockbar-log",
                    "time": datetime.now().isoformat(timespec="seconds"),
                    "days": len(day_files()),
                },
            )
        if path in ("/", "/index.html"):
            return self._page()
        if path == "/logs":
            rows = load_records(date, limit=200)
            return self._json(200, {"ok": True, "count": len(rows), "records": rows})
        if path == "/download":
            names = files_for(date)
            chunks = []
            for name in names:
                chunks.append("########## %s ##########" % name)
                try:
                    with open(os.path.join(LOG_DIR, name), encoding="utf-8") as f:
                        chunks.append(f.read())
                except OSError:
                    chunks.append("(读取失败)")
            return self._send(200, "\n".join(chunks), "text/plain; charset=utf-8")
        return self._json(404, {"ok": False, "error": "not found", "path": self.path})

    def do_HEAD(self):
        self.do_GET()

    def _page(self):
        rows = load_records(limit=150)
        files = day_files()
        total = 0
        for name in files:
            try:
                with open(os.path.join(LOG_DIR, name), encoding="utf-8") as f:
                    total += sum(1 for line in f if line.strip())
            except OSError:
                pass

        trs = []
        for o in rows:
            log = o.get("log") or o.get("body") or ""
            tail = one_line(log.splitlines()[-1] if log else "", 160)
            note = o.get("note") or tail
            trs.append(
                "<tr><td>%s</td><td>%s</td><td><b>%s</b></td><td>%s</td>"
                "<td>%s</td><td>%s</td><td>%s</td></tr>"
                % (
                    escape(str(o.get("received_at", ""))),
                    escape(str(o.get("time", ""))),
                    escape(str(o.get("kind", ""))),
                    escape(str(o.get("device", ""))),
                    escape(str(o.get("model", ""))),
                    escape(str(o.get("app", ""))),
                    escape(str(note)),
                )
            )

        html = """<!doctype html>
<meta charset=utf-8>
<meta name=viewport content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="30">
<title>CarDockBar 日志</title>
<body style="font:13px/1.6 system-ui,-apple-system,'Segoe UI',sans-serif;margin:24px;background:#f6f7f9;color:#1a1a1a">
<div style="max-width:1180px;margin:0 auto">
<h2 style="margin:0 0 4px">CarDockBar 日志接收服务</h2>
<p style="margin:0 0 16px;color:#555">
  已落盘 <b>%(total)d</b> 条 · <b>%(days)d</b> 天 · 目录 <code>%(dir)s</code>
  · <a href="/download">下载全部日志</a> · <a href="/logs">JSON 接口</a>
  · 页面 30 秒自动刷新
</p>
<table border=0 cellpadding=6 cellspacing=0 style="background:#fff;border:1px solid #e3e5e8;border-radius:8px;width:100%%;border-collapse:collapse">
<tr style="background:#eef1f4;text-align:left">
  <th>收到时间</th><th>设备时间</th><th>类型</th><th>设备</th><th>机型</th><th>版本</th><th>备注 / 日志尾行</th>
</tr>
%(rows)s
</table>
<p style="color:#888;margin-top:14px">提示：拿到本链接的人都能查看这些日志，仅用于车机故障排查，排查结束后可下线本服务。</p>
</div>
</body>
""" % {
            "total": total,
            "days": len(files),
            "dir": escape(LOG_DIR),
            "rows": "".join(trs) if trs else "<tr><td colspan=7 style='color:#888'>（还没有收到任何记录）</td></tr>",
        }
        self._send(200, html, "text/html; charset=utf-8")


def main():
    print("=" * 62, flush=True)
    print("  CarDockBar 日志接收服务（部署版）", flush=True)
    print("  监听 0.0.0.0:%d（PORT 环境变量）" % PORT, flush=True)
    print("  POST 任意路径 -> 收日志；GET / -> 查看页；GET /download -> 下载", flush=True)
    print("  日志目录：%s" % LOG_DIR, flush=True)
    print(
        "  保留策略：超过 %d 天的日志自动清理（LOG_KEEP_DAYS=0 则全留）；"
        "单文件超过 %d 字节自动分片" % (KEEP_DAYS, SPLIT_BYTES),
        flush=True,
    )
    removed = cleanup_old(force=True)
    if removed:
        print("  启动时清理了 %d 个过期文件" % removed, flush=True)
    print("=" * 62, flush=True)
    try:
        ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。", flush=True)
    except OSError as e:
        print("\n启动失败：%s" % e, flush=True)
        raise


if __name__ == "__main__":
    main()
