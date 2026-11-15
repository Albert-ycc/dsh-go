#!/usr/bin/env python3
# 电脑侧调试服务：把备好的 webprobe 资产按原样发给手机上的 WebView，页面 POST 回来的结果落到结果目录。
# 用法：serve.py <资产目录> <结果目录> <端口> [绑定地址，默认 127.0.0.1]
# 默认只在本机听，用来在电脑浏览器里先过一遍测试页；绑到局域网地址后手机上点「Mac页」可免重打 APK 调页面
import http.server, os, sys, time

ROOT, OUT, PORT = sys.argv[1], sys.argv[2], int(sys.argv[3])
HOST = sys.argv[4] if len(sys.argv) > 4 else "127.0.0.1"


class H(http.server.SimpleHTTPRequestHandler):
    extensions_map = dict(http.server.SimpleHTTPRequestHandler.extensions_map, **{".mjs": "text/javascript", ".wasm": "application/wasm"})

    def __init__(self, *a, **k):
        super().__init__(*a, directory=ROOT, **k)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        k = "".join(ch for ch in self.path.partition("k=")[2][:20] if ch.isalnum()) or "main"
        with open(os.path.join(OUT, "webprobe-%s.json" % k), "wb") as f:
            f.write(body)
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, fmt, *a):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % a))


os.makedirs(OUT, exist_ok=True)
http.server.ThreadingHTTPServer((HOST, PORT), H).serve_forever()
