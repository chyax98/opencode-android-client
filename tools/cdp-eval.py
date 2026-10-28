#!/usr/bin/env python3
"""在壳的 WebView 里跑一段 JS，把结果打到 stdout（设备验证用）。

    python3 tools/cdp-eval.py "document.body.innerText"

壳的 WebView 上截图/uiautomator 都是空白的（SurfaceView 那条路），所以要看"页面到底渲染了什么"
只能走 CDP：自己从 /proc/net/unix 找 webview_devtools_remote、adb forward、再走一次 WebSocket 握手。
只用标准库。
"""
import base64
import json
import os
import re
import socket
import struct
import subprocess
import sys
import urllib.request


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True).stdout


def page_target():
    out = adb("shell", "cat", "/proc/net/unix")
    match = re.search(r"webview_devtools_remote[^\s]*", out)
    if not match:
        sys.exit("没找到 webview_devtools_remote：壳起来了吗、页面开始加载了吗？")
    adb("forward", "tcp:9222", "localabstract:" + match.group(0))
    targets = json.load(urllib.request.urlopen("http://127.0.0.1:9222/json"))
    for target in targets:
        if target.get("type") == "page":
            return target
    sys.exit("没有 page 目标：" + repr(targets))


def connect(url):
    match = re.match(r"ws://([^:/]+):(\d+)(/.*)", url)
    host, port, path = match.group(1), int(match.group(2)), match.group(3)
    sock = socket.create_connection((host, port))
    key = base64.b64encode(os.urandom(16)).decode()
    sock.sendall(
        f"GET {path} HTTP/1.1\r\nHost: {host}:{port}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n".encode()
    )
    header = b""
    while b"\r\n\r\n" not in header:
        header += sock.recv(4096)
    return sock


def send_text(sock, text):
    payload = text.encode()
    mask = os.urandom(4)
    n = len(payload)
    frame = bytes([0x81])
    if n < 126:
        frame += bytes([0x80 | n])
    elif n < 65536:
        frame += bytes([0x80 | 126]) + struct.pack(">H", n)
    else:
        frame += bytes([0x80 | 127]) + struct.pack(">Q", n)
    sock.sendall(frame + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


def read_text(sock):
    def readn(n):
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise EOFError("WebSocket 关了")
            data += chunk
        return data

    while True:
        first, second = readn(2)
        length = second & 0x7F
        if length == 126:
            length = struct.unpack(">H", readn(2))[0]
        elif length == 127:
            length = struct.unpack(">Q", readn(8))[0]
        payload = readn(length)
        if first & 0x0F == 0x1:  # 文本帧
            return payload.decode("utf-8", "replace")


def main():
    expression = sys.argv[1] if len(sys.argv) > 1 else "document.body.innerText"
    target = page_target()
    sock = connect(target["webSocketDebuggerUrl"])
    send_text(sock, json.dumps({
        "id": 1,
        "method": "Runtime.evaluate",
        "params": {"expression": expression, "returnByValue": True, "awaitPromise": True},
    }))
    while True:
        message = json.loads(read_text(sock))
        if message.get("id") != 1:
            continue
        result = message.get("result", {})
        value = result.get("result", {}).get("value")
        if value is None:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        elif isinstance(value, str):
            print(value)
        else:
            print(json.dumps(value, ensure_ascii=False))
        return


if __name__ == "__main__":
    main()
