#!/usr/bin/env python3
"""
Automate the weight -> calorie comparison test, against the app's own
calorie estimate (see kcalPerMin() in MainActivity.kt).

The board's own calorie counter does not respond to weight at all — confirmed
earlier by writing to the board's WEIGHT field directly via debugWeight() and
holding identical pace/incline/duration at 40kg and 100kg: kcal/min came back
within noise of each other. This script now tests the *app's* estimate
instead, which is computed from the walker's weight set in Settings
(Stride.setPersonWeight) and only takes effect when Session.isMoving and a
nonzero weight is on file for the current walker — see accumulate() in
MainActivity.kt.

Requires: a debug build running on the console with setPersonWeight() and
liveStats() in the Bridge, and a person already added in Settings (or this
script will add one via addPerson() on first run).

    python3 tools/weight_calorie_test.py 40 100 --walker Tester
    python3 tools/weight_calorie_test.py 40 100 --walker Tester --kph 5.5 --incline 4 --minutes 3
"""
import argparse
import json
import subprocess
import sys
import time
import urllib.request

import websocket

PKG = "dev.stride.hud"


def connect():
    pid = subprocess.check_output(["adb", "shell", "pidof", PKG]).decode().strip()
    if not pid:
        sys.exit(f"{PKG} is not running")
    subprocess.run(["adb", "forward", "tcp:9222",
                    f"localabstract:webview_devtools_remote_{pid}"], check=True)
    pages = json.loads(urllib.request.urlopen("http://localhost:9222/json/list", timeout=5).read())
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
                    print("  THREW:", json.dumps(res["exceptionDetails"])[:400])
                    return None
                return res.get("result", {}).get("value")

    def stats(self):
        raw = self.ev("Stride.liveStats()")
        try:
            return json.loads(raw)
        except (TypeError, ValueError):
            print(f"  WARN: liveStats() returned unparseable value: {raw!r}")
            return {"calories": 0.0, "distance": 0.0, "elapsed": 0.0}


def ensure_walker(b: Bridge, name: str):
    """Add the walker if Settings doesn't already have them, then select them."""
    people_json = b.ev("Stride.settingsJson()")
    try:
        people = json.loads(people_json).get("people", [])
    except (TypeError, ValueError):
        people = []
    if not any(p.get("name") == name for p in people):
        print(f"  adding walker '{name}' to Settings")
        b.ev(f"Stride.addPerson({json.dumps(name)})")
        time.sleep(0.3)
    b.ev(f"Stride.setWalker({json.dumps(name)})")


def run_one(b: Bridge, walker: str, kg: float, kph: float, incline: float,
            minutes: float, poll_s: float):
    print(f"\n=== {kg} kg, {incline}% incline ===")

    b.ev("Stride.home()")
    time.sleep(1.5)

    print(f"  setting {walker}'s weight to {kg} kg")
    b.ev(f"Stride.setPersonWeight({json.dumps(walker)}, {kg})")
    time.sleep(0.5)

    print("  starting walk")
    b.ev("Stride.choose()")
    time.sleep(3.0)          # let the warm-up ramp begin
    b.ev("Stride.skipWarmup()")
    time.sleep(0.5)
    b.ev(f"Stride.setSpeed({kph})")

    if incline != 0.0:
        print(f"  setting incline to {incline}%")
        # No direct "set" — incline(delta) nudges from wherever it is now.
        # A fresh casual walk starts level, so one delta call reaches target.
        b.ev(f"Stride.incline({incline})")
        time.sleep(1.0)       # let the deck actually move before polling starts

    start = b.stats()
    t0 = time.time()
    samples = [(0.0, start)]
    print(f"  {'t(s)':>6} {'kcal':>8} {'m':>8}")
    while time.time() - t0 < minutes * 60:
        time.sleep(poll_s)
        s = b.stats()
        t = time.time() - t0
        samples.append((t, s))
        print(f"  {t:6.0f} {s.get('calories', 0):8.2f} {s.get('distance', 0):8.1f}")

    end = samples[-1][1]

    print("  stopping")
    b.ev("Stride.pause()")
    time.sleep(1.0)
    b.ev("Stride.end()")
    time.sleep(1.0)
    b.ev("Stride.home()")
    time.sleep(1.0)

    elapsed_s = end.get("elapsed", 0) - start.get("elapsed", 0)
    kcal = end.get("calories", 0) - start.get("calories", 0)
    kcal_per_min = kcal / (elapsed_s / 60) if elapsed_s > 0 else 0.0
    return {"kg": kg, "incline": incline, "kcal": kcal,
            "elapsed_s": elapsed_s, "kcal_per_min": kcal_per_min}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("weights", nargs="+", type=float, help="weights in kg to test")
    ap.add_argument("--walker", required=True,
                    help="walker name to attach the weight to (added if not already in Settings)")
    ap.add_argument("--kph", type=float, default=5.0, help="fixed speed, default 5.0")
    ap.add_argument("--incline", type=float, default=0.0,
                    help="incline %% to hold during each run, default 0.0")
    ap.add_argument("--minutes", type=float, default=3.0, help="hold time per weight")
    ap.add_argument("--poll", type=float, default=15.0, help="poll interval, seconds")
    args = ap.parse_args()

    b = Bridge()
    ensure_walker(b, args.walker)

    results = []
    for kg in args.weights:
        results.append(run_one(b, args.walker, kg, args.kph, args.incline,
                               args.minutes, args.poll))

    print("\n=== summary ===")
    print(f"{'kg':>6} {'incline':>8} {'kcal':>8} {'kcal/min':>10}")
    for r in results:
        print(f"{r['kg']:6.0f} {r['incline']:8.1f} {r['kcal']:8.2f} {r['kcal_per_min']:10.3f}")

    base = results[0]["kcal_per_min"]
    if base > 0:
        print("\nratio vs first weight:")
        for r in results:
            print(f"  {r['kg']:.0f} kg: {r['kcal_per_min'] / base:.3f}x "
                  f"(expected ~{r['kg'] / results[0]['kg']:.3f}x if weight-linear)")


if __name__ == "__main__":
    main()