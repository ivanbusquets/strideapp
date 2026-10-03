#!/usr/bin/env python3
import json, subprocess, sys, time, urllib.request
import websocket

PKG = "dev.stride.hud"

def connect():
    pid = subprocess.check_output(["adb", "shell", "pidof", PKG]).decode().strip()
    if not pid:
        sys.exit(f"{PKG} is not running")
    subprocess.run(["adb", "forward", "tcp:9222",
                    f"localabstract:webview_devtools_remote_{pid}"], check=True)
    pages = json.loads(urllib.request.urlopen("http://localhost:9222/json/list").read())
    page = next(p for p in pages if p.get("webSocketDebuggerUrl"))
    return websocket.create_connection(page["webSocketDebuggerUrl"], timeout=30)

class Bridge:
    def __init__(self):
        self.ws = connect()
        self.n = 0

    def ev(self, expr):
        self.n += 1
        self.ws.send(json.dumps({"id": self.n, "method": "Runtime.evaluate",
                                 "params": {"expression": expr, "returnByValue": True}}))
        while True:
            r = json.loads(self.ws.recv())
            if r.get("id") == self.n:
                res = r.get("result", {})
                if "exceptionDetails" in res:
                    return "THREW: " + json.dumps(res["exceptionDetails"])[:400]
                return res.get("result", {}).get("value")

if __name__ == "__main__":
    b = Bridge()
    kg = float(sys.argv[1]) if len(sys.argv) > 1 else 60
    print(b.ev(f"Stride.debugWeight({kg}); 'sent'"))