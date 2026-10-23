#!/usr/bin/env python3
"""
Drive the STRIDE HUD from a laptop and photograph it, with the belt cold.

Every screen and overlay can be checked against fabricated state without
arming the treadmill, which is how the design screenshots in `claudeDesign/`
were made and how any new UI should be reviewed before it is trusted.

    python3 tools/hud_drive.py screens            # the full set, whichever UI is up
    python3 tools/hud_drive.py screens ember      # switch to Ember first, then capture
    python3 tools/hud_drive.py guided             # hold one guided-walk frame on screen
    python3 tools/hud_drive.py casual             # hold one casual-walk frame on screen
    python3 tools/hud_drive.py ui ember           # just switch, and leave it there
    python3 tools/hud_drive.py release            # hand the screen back to Kotlin

Shots land in `claudeDesign/<ui>/` for every UI except the original, which
keeps the flat `claudeDesign/` it has always used.

Three things that will otherwise waste your afternoon:

* **Kotlin repaints at 5 Hz and will stomp anything you inject**, so the real
  renderer is parked first (`window.render` swapped for a no-op). `release`
  puts it back; so does relaunching the app.
* **`Page.captureScreenshot` does not composite the canvas on Chromium 83.**
  It returns a frame with the path blank and everything else correct, which
  looks exactly like a rendering bug. Screenshots here come from
  `adb exec-out screencap`, which reads the real framebuffer.
* **The pre-workout flow is driven with real taps**, which means per-UI
  coordinates — see TAPS. Everything after it is injected and layout-agnostic.
  Switching UI reloads the WebView, so the DevTools socket has to be reopened
  after one.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

import websocket

# Override with STRIDE_DEVICE; otherwise whatever adb has attached.
DEV = os.environ.get("STRIDE_DEVICE") or subprocess.run(
    ["adb", "devices"], capture_output=True, text=True
).stdout.splitlines()[1].split()[0]
PKG = "dev.stride.hud"
OUT = "claudeDesign"

# Where to tap to walk the pre-workout flow: profile, walk, guided. Each UI
# lays those cards out differently, so each needs its own three coordinates.
# Measured from the CSS, not from a screenshot.
TAPS = {
    "original": [(286, 492), (476, 492), (804, 492)],
    "ember":    [(226, 400), (412, 410), (868, 410)],
    "daylight": [(185, 455), (335, 450), (944, 450)],
    "pacer":    [(197, 430), (397, 445), (883, 450)],
    "cluster":  [(205, 390), (403, 400), (877, 400)],
    # Super: the first name card, "Guided" in the middle of the three control
    # cards, then Rolling hills in the plan step's four-up row. The name card
    # assumes a full row, as the other four do.
    "super":    [(282, 194), (640, 257), (495, 351)],
}

# Rolling hills at 30 minutes, resolved the way Plan.kt would.
SHAPE = [(0.11, 0.0, "Settle in"), (0.09, 4.0, "First rise"),
         (0.07, -1.0, "Down the far side"), (0.12, 6.5, "The long one"),
         (0.06, 1.0, "Short recovery"), (0.05, 9.5, "The wall"),
         (0.09, 2.0, "Over the top"), (0.08, 5.5, "One more"),
         (0.07, -1.5, "Long descent"), (0.12, 3.0, "The last drag"),
         (0.14, 0.0, "Cool down")]


def adb(*args):
    return subprocess.run(["adb", "-s", DEV] + list(args),
                          capture_output=True, check=False).stdout


def connect():
    pid = subprocess.check_output(["adb", "-s", DEV, "shell", "pidof", PKG]).decode().strip()
    if not pid:
        sys.exit(f"{PKG} is not running")
    subprocess.run(["adb", "-s", DEV, "forward", "tcp:9222",
                    f"localabstract:webview_devtools_remote_{pid}"],
                   check=True, capture_output=True)
    pages = json.loads(urllib.request.urlopen("http://localhost:9222/json/list").read())
    page = next(p for p in pages if p.get("webSocketDebuggerUrl"))
    return websocket.create_connection(page["webSocketDebuggerUrl"], timeout=30)


class Hud:
    def __init__(self, out=OUT):
        self.ws = connect()
        self.n = 0
        self.out = out
        os.makedirs(out, exist_ok=True)

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

    def park(self):
        """Stop the 5 Hz repaint stomping on injected frames."""
        self.ev("if(!window._real){window._real=window.render;window.render=function(){}}")

    def release(self):
        self.ev("if(window._real){window.render=window._real;delete window._real}")

    def plan(self, minutes=30):
        total, at, steps = minutes * 60.0, 0.0, []
        norm = sum(s[0] for s in SHAPE)
        for i, (share, incline, label) in enumerate(SHAPE):
            end = total if i == len(SHAPE) - 1 else at + total * share / norm
            steps.append({"start": round(at), "end": round(end),
                          "incline": incline, "label": label})
            at = end
        self.ev(f"window.plan({json.dumps({'name': 'Rolling hills', 'steps': steps})})")
        return steps

    def push(self, **over):
        s = {"speed": 6.2, "incline": 4.0, "targetSpeed": 6.2, "targetIncline": 4.0,
             "distance": 1180, "elapsed": 640, "calories": 78, "pulse": 0, "fan": 0,
             "workout": "walk", "dmk": False, "ramping": "", "phaseLeft": 0,
             "boardMode": 2, "avgSpeed": 5.9, "maxSpeed": 6.8, "avgIncline": 2.4,
             "maxIncline": 6.0, "who": "Sam", "plan": "Rolling hills", "segment": 2,
             "segments": 9, "segmentLabel": "Hill one", "segmentLeft": 96,
             "suggestPace": 6.4, "inclineAuto": True, "mode": "running"}
        s.update(over)
        return self.ev(f"window._real({json.dumps(s)}); 'ok'")

    def shot(self, name):
        time.sleep(1.3)
        data = subprocess.run(["adb", "-s", DEV, "exec-out", "screencap", "-p"],
                              capture_output=True, check=True).stdout
        open(f"{self.out}/{name}.png", "wb").write(data)
        print(f"  {name:26} {len(data)//1024:4} KB")

    def which_ui(self):
        return self.ev("Stride.currentUi()")


CASUAL = dict(plan="", segment=0, segments=0, segmentLabel="", segmentLeft=0,
              suggestPace=0, inclineAuto=False, incline=1.5, targetIncline=1.5)


def switch_ui(name):
    """Ask the console to change interface, and wait for the reload to land."""
    Hud().ev(f"Stride.setUi({json.dumps(name)})")
    time.sleep(4)


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "screens"
    ui = sys.argv[2] if len(sys.argv) > 2 else None

    if what == "release":
        Hud().release()
        print("renderer released — Kotlin has the screen back")
        return

    if what == "ui":
        if not ui:
            sys.exit("which one? " + ", ".join(TAPS))
        switch_ui(ui)
        print(f"now showing {Hud().which_ui()}")
        return

    if ui:
        switch_ui(ui)

    hud = Hud()
    ui = hud.which_ui()
    # The original keeps the flat directory it has always written to; anything
    # else gets its own, so four sets of eleven do not collide.
    out = OUT if ui == "original" else f"{OUT}/{ui}"
    hud = Hud(out)
    taps = TAPS.get(ui)
    if not taps:
        sys.exit(f"no tap coordinates for {ui} — add them to TAPS")
    print(f"{ui} -> {out}/")

    if what in ("guided", "casual"):
        hud.park()
        hud.plan()
        hud.push(**({} if what == "guided" else CASUAL))
        print(f"holding a {what} frame. `release` when done.")
        return

    # Full set. The welcome flow is driven with real taps; the rest injected.
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(7)
    hud = Hud(out)
    print("welcome flow:")
    hud.shot("01-welcome-who")
    adb("shell", "input", "tap", *map(str, taps[0]))    # the leftmost profile
    hud.shot("02-welcome-what")
    adb("shell", "input", "tap", *map(str, taps[1]))    # Walk
    hud.shot("03-welcome-how")
    adb("shell", "input", "tap", *map(str, taps[2]))    # Guided
    hud.shot("04-guided-picker")

    print("workout states:")
    hud.park()
    hud.plan()
    hud.push(**CASUAL)
    hud.shot("05-workout-casual-oval")
    hud.push()
    hud.shot("06-workout-guided-path")
    hud.ev("""window.coach({line:"Ease off a touch — this one lasts.",kind:"segment"})""")
    hud.shot("07-workout-coach")
    # Warm-up runs *first*, so its telemetry is warm-up scale. Injecting a
    # mid-session elapsed under a WARMING UP badge produced a screenshot that
    # looked like the HUD's bug rather than the driver's, and the numbers were
    # duly written up as a fault. Every field here is one the console could
    # actually be showing 19 seconds into a walk.
    hud.push(mode="warmup", phaseLeft=74, speed=2.0, incline=0.0,
             targetSpeed=2.0, targetIncline=0.0, elapsed=19, distance=60,
             calories=6, segments=0, plan="", segment=0, segmentLabel="",
             segmentLeft=0, suggestPace=0)
    hud.shot("08-phase-warmup")
    hud.push(mode="paused", speed=0.0)
    hud.ev("document.getElementById('confirm').classList.add('show')")
    hud.shot("09-paused-confirm")
    hud.ev("document.getElementById('confirm').classList.remove('show')")
    hud.push(dmk=True)
    hud.shot("10-safety-key")
    hud.push(mode="summary", elapsed=1800, distance=3120, calories=214)
    hud.shot("11-summary")

    # Settings > UI. Opened by script rather than by tapping at coordinates,
    # which would have to be re-measured every time the welcome screen moves.
    hud.push(mode="welcome")
    hud.ev("buildUis(); var s=document.getElementById('settings');s.classList.add('show'); s.classList.add('on')")
    hud.shot("12-settings-ui")

    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    print("\nconsole reset to welcome")


if __name__ == "__main__":
    main()
