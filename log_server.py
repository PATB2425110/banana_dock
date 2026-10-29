#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CarDockBar 日志接收服务（最小自建版，仅用 Python 标准库，无需 pip install）

做什么：
  接收 App 的 LogUploader POST 上来的 JSON 日志，按「一天一个文件」追加落盘，
  顺便在控制台打印一行摘要，方便肉眼确认「通了」。

怎么用：
  1) 在本机运行：  python log_server.py            （默认 0.0.0.0:8080）
  2) 看它打印的局域网地址，例如 http://192.168.124.9:8080/upload
  3) 平板/车机上打开 CarDockBar → 设置 → 日志 → 上传地址，填这个地址 → 保存
  4) 之后每次 App 启动 / 崩溃 / 点「测试上传」，这里就会收到一条

日志落在： log_server_logs/YYYY-MM-DD.jsonl （一行一条 JSON，可直接用文本编辑器看）

自检：
  curl -X POST -H "Content-Type: application/json" -d '{"kind":"probe"}' http://127.0.0.1:8080/upload

停止： Ctrl+C 。它只监听局域网，不对外网暴露；用完建议直接关掉。
"""

import json
import os
import re
import socket
import subprocess
import sys
import threading
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ---------------------------------------------------------------- 配置
HOST = os.environ.get("LOG_SERVER_HOST", "0.0.0.0")   # 0.0.0.0 = 允许局域网设备访问
PORT = int(os.environ.get("LOG_SERVER_PORT", "8080"))
LOG_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "log_server_logs")

os.makedirs(LOG_DIR, exist_ok=True)
_write_lock = threading.Lock()          # 多线程同时写，加锁防串行


def lan_ip() -> str:
    """取本机在局域网里的 IPv4（用于打印给用户填到 App 里）"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))      # 不会真的发包，只是为了选路由
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def ipv6_addr() -> list:
    """列出本机的公网/全局 IPv6 地址（车机走 IPv6 直连时用）"""
    out = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET6):
            ip = info[4][0]
            if ip.startswith("fe80") or ip.startswith("::1"):   # 链路本地/回环，跳过
                continue
            if ip not in out:
                out.append(ip)
    except Exception:
        pass
    return out


def public_ipv6() -> str:
    """向国内服务查一次出口 IPv6（比本机枚举更准，反映车机到底能连到哪个地址）"""
    for url in ("https://speed.neu6.edu.cn/", "https://test.ipw.cn/", "https://v6.ident.me/"):
        try:
            r = subprocess.run(
                ["curl", "-s", "--max-time", "6", "-6", url],
                capture_output=True, text=True, timeout=10,
            )
            txt = (r.stdout or "").strip()
            # 从返回里抠出第一个 IPv6 形态的串
            m = re.search(r"([0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7})", txt)
            if m and ":" in m.group(1):
                return m.group(1)
        except Exception:
            continue
    return ""


def save_record(obj: dict) -> str:
    """一条记录 → 当天的 .jsonl 文件。返回写入的文件名"""
    day = datetime.now().strftime("%Y-%m-%d")
    path = os.path.join(LOG_DIR, f"{day}.jsonl")
    with _write_lock, open(path, "a", encoding="utf-8") as f:
        f.write(json.dumps(obj, ensure_ascii=False) + "\n")
    return path


def brief(obj: dict) -> str:
    """控制台摘要：挑最有辨识度的几个字段"""
    kind = obj.get("kind", "?")
    note = (obj.get("note") or "").strip()
    tail = ""
    log = obj.get("log")
    if isinstance(log, str) and log:
        lines = [l for l in log.splitlines() if l.strip()]
        tail = lines[-1][:90] if lines else ""
    parts = [f"kind={kind}"]
    if note:
        parts.append(f"note={note}")
    if tail:
        parts.append(f"日志尾行: {tail}")
    return "  ".join(parts)


class ThreadingHTTPServerV6(ThreadingHTTPServer):
    """
    IPv4 / IPv6 双栈监听。

    默认的 ThreadingHTTPServer 只绑 AF_INET（IPv4）。运营商给的是公网 IPv6，
    车机要走 IPv6 直连就必须监听 IPv6；同时关掉 IPV6_V6ONLY，
    这样一个 socket 同时接受 IPv4 和 IPv6 连接（Windows 默认 V6ONLY=1，必须显式关）。
    """

    address_family = socket.AF_INET6

    def server_bind(self):
        try:
            self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        except (AttributeError, OSError):
            # 个别平台不支持，忽略：大不了只走 IPv4
            pass
        super().server_bind()


class Handler(BaseHTTPRequestHandler):
    # 静默默认访问日志，只打我们自己的一行
    def log_message(self, fmt, *args):
        pass

    def _send(self, code: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        # 允许任意来源（App 没有 Origin 头，这里只是图个安心）
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        # ---- 读 body ----
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length > 0 else b""

        obj = None
        text = None
        try:
            text = raw.decode("utf-8")
            obj = json.loads(text)
        except Exception:
            obj = None

        # App 发的是 JSON；万一哪天台了别的格式，也原样存下来别丢
        if isinstance(obj, dict):
            obj.setdefault("received_at", datetime.now().isoformat(timespec="seconds"))
            obj.setdefault("src_ip", self.client_address[0])
            path = save_record(obj)
            print(f"[{datetime.now():%H:%M:%S}] 收到 → {os.path.basename(path)}  {brief(obj)}", flush=True)
            self._send(200, {"ok": True, "saved": os.path.basename(path)})
        else:
            fallback = {
                "kind": "raw",
                "received_at": datetime.now().isoformat(timespec="seconds"),
                "src_ip": self.client_address[0],
                "path": self.path,
                "body": text if text is not None else raw.decode("utf-8", "replace"),
            }
            path = save_record(fallback)
            print(f"[{datetime.now():%H:%M:%S}] 收到(非JSON) → {os.path.basename(path)}  path={self.path}", flush=True)
            self._send(200, {"ok": True, "saved": os.path.basename(path), "note": "body 不是 JSON，已按 raw 存储"})

    def do_GET(self):
        """迷你查看页：就近看几条，不用翻文件"""
        if self.path in ("/", "/index.html"):
            files = sorted(
                (f for f in os.listdir(LOG_DIR) if f.endswith(".jsonl")),
                reverse=True,
            )
            rows = []
            for name in files[:2]:                     # 只看最近两天
                with open(os.path.join(LOG_DIR, name), encoding="utf-8") as f:
                    for line in f:
                        try:
                            o = json.loads(line)
                        except Exception:
                            continue
                        rows.append(
                            f"<tr><td>{o.get('received_at','')}</td>"
                            f"<td>{o.get('time','')}</td>"
                            f"<td><b>{o.get('kind','')}</b></td>"
                            f"<td>{o.get('model','')} / {o.get('app','')}</td>"
                            f"<td>{o.get('note','')}</td></tr>"
                        )
            rows = rows[-60:][::-1]                    # 最新在上
            html = (
                "<!doctype html><meta charset=utf-8><title>CarDockBar 日志</title>"
                "<body style=\"font:13px/1.5 system-ui;margin:24px;background:#111;color:#eee\">"
                "<h3>CarDockBar 日志接收服务</h3>"
                f"<p>已落盘 {len(files)} 天 · 目录 <code>{LOG_DIR}</code></p>"
                "<table border=0 cellpadding=6 cellspacing=0>"
                "<tr><th>收到时间</th><th>设备时间</th><th>类型</th><th>机型/版本</th><th>备注</th></tr>"
                + "".join(rows) +
                "</table></body>"
            )
            body = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self._send(404, {"ok": False, "error": "not found"})


def main():
    ip = lan_ip()
    print("=" * 62, flush=True)
    print("  CarDockBar 日志接收服务已启动", flush=True)
    print(f"  局域网地址（填到 App 的「上传地址」）：", flush=True)
    print(f"      http://{ip}:{PORT}/upload", flush=True)
    print(f"  本机自测：  curl -X POST -d '{{\"kind\":\"probe\"}}' http://127.0.0.1:{PORT}/upload", flush=True)
    print(f"  查看页面：  http://{ip}:{PORT}/", flush=True)
    print(f"  日志目录：  {LOG_DIR}", flush=True)
    print("  停止：Ctrl+C", flush=True)
    print("=" * 62, flush=True)
    try:
        ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。", flush=True)
    except OSError as e:
        print(f"\n启动失败：{e}", flush=True)
        if e.errno == 10048:
            print("端口被占用。换个端口再试：  set LOG_SERVER_PORT=8081 && python log_server.py", flush=True)
        sys.exit(1)


if __name__ == "__main__":
    main()
