#!/usr/bin/env python3
"""假的 OpenCode 服务，用来在模拟器上端到端验证壳（真机/模拟器跑单测之外的最后一公里）。

  python3 tools/fake-server.py 8091 A &
  python3 tools/fake-server.py 8092 B &

它照着上游的两条分支实现（packages/server/src/handlers/server.ts）：
  GET /auth/connect/<code>
    Accept 里没有 text/html（= API 那条） → 200 application/json {"token":"<expires>.<签名>"}
                                          码以 bad 开头时 → 401 {"_tag":"UnauthorizedError"}
    Accept 里有 text/html（= 浏览器那条） → 302 到 / + Set-Cookie
                                          码以 bad 开头时 → 200 错误页
  GET /api/info
    Authorization: Basic opencode:<token> 或 session cookie 正确 → 200，否则 401（壳用来自校验 token）
  GET /*
    回一个能看出"是哪台机器 + 会话成不成立"的页面（cookie 名照上游规则 opcode_session_<port>）

每个请求都往 stdout 打一行，宿主机上直接看壳到底发了什么。

模拟器里用 http://10.0.2.2:8091 访问（10.0.2.2 是宿主机；127.0.0.1 会被壳当成"手机自己"拒掉）。
"""
import base64
import re
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOKEN_SHAPE = re.compile(r"^[0-9]{1,12}\.[A-Za-z0-9_-]{20,256}$")


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 + Content-Length：302 也要给长度，不然 Chromium 可能会把这次跳转丢掉。
    protocol_version = "HTTP/1.1"

    def token(self, code):
        # 形状要跟上游一致（expires.base64url 签名），不然壳的形状校验会（正确地）拒掉
        return "%d.%s%s" % (int(time.time()) + 2592000, code, "0" * 24)

    def session_name(self):
        host = self.headers.get("Host") or ""
        port = host.rsplit(":", 1)[-1] if ":" in host else ""
        return "opencode_session_%s" % port if port not in ("", "80", "443") else "opencode_session"

    def session_ok(self):
        cookie = self.headers.get("Cookie") or ""
        for part in cookie.split(";"):
            name, _, value = part.strip().partition("=")
            if name == self.session_name() and value in self.server.issued:
                return True
        auth = self.headers.get("Authorization") or ""
        if auth.startswith("Basic "):
            try:
                user, _, password = base64.b64decode(auth[6:]).decode().partition(":")
            except Exception:
                return False
            return user == "opencode" and password in self.server.issued
        return False

    def send(self, status, content_type, body):
        payload = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        accept = self.headers.get("Accept") or ""
        self.server.log("[%s %s] %s accept=%r cookie=%r auth=%r" % (
            self.server.name, time.strftime("%H:%M:%S"), self.path, accept,
            self.headers.get("Cookie") or "", self.headers.get("Authorization") or ""))

        if self.path.startswith("/redirect-out"):
            # 可信服务器把主框架 302 到外站：壳必须拦下来（302 不走 shouldOverrideUrlLoading）
            self.send_response(302)
            self.send_header("Location", "https://example.com/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        if self.path.startswith("/api/info"):
            if not self.session_ok():
                self.send(401, "application/json", '{"_tag":"UnauthorizedError"}')
                return
            self.send(200, "application/json", '{"version":"0.0.0-fake"}')
            return

        if self.path.startswith("/auth/connect/"):
            code = self.path.rsplit("/", 1)[-1]
            api = "text/html" not in accept
            if code.startswith("bad"):
                if api:
                    self.send(401, "application/json", '{"_tag":"UnauthorizedError","message":"expired"}')
                else:
                    # 浏览器那条：真服务端此时渲染错误页（不跳转），用来跟 302 的路径对比
                    self.send(200, "text/html",
                              "<html><body><h1>%s</h1><p>配对链接已失效</p></body></html>" % self.server.name)
                return
            token = self.token(code)
            self.server.issued.add(token)
            if api:
                self.send(200, "application/json", '{"token":"%s"}' % token)
                return
            self.send_response(302)
            self.send_header("Set-Cookie", "%s=%s; Path=/; HttpOnly; Max-Age=2592000" % (self.session_name(), token))
            self.send_header("Location", "/")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return

        # 根页面：把"会话成不成立"直接写在页面上，宿主机用 CDP 读一眼就知道
        session = "ok" if self.session_ok() else "none"
        body = ("<html><body><h1>%s</h1><p id=session>%s</p><p id=cookie>%s</p></body></html>"
                % (self.server.name, session, self.headers.get("Cookie") or ""))
        self.send(200, "text/html", body)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    port, name = int(sys.argv[1]), sys.argv[2]
    # 必须多线程：WebView 会占着 keep-alive 连接，单线程时 302 之后的跳转请求会被卡死。
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.name = name
    server.issued = set()
    def log(line):
        print(line, flush=True)
    server.log = log
    server.serve_forever()
