#!/usr/bin/env python3
"""Finds and taps on-screen text on an emulator, for release/rc-check.sh (Ferb's rc-check uses the same).

  rc-ui.py SERIAL has TEXT     exit 0 if TEXT is on screen (exact, else the start of a longer text)
  rc-ui.py SERIAL tap TEXT     tap it, scrolling down up to 6 times to find it; exit 1 if never found
  rc-ui.py SERIAL wait TEXT    wait up to 20 s for TEXT to appear
  rc-ui.py SERIAL gone TEXT    wait up to 30 s for TEXT to go
  rc-ui.py SERIAL dump         print every text on screen

TEXT matches a node's text or its content-desc (an icon's label). Reads the screen with uiautomator (a dump of the view tree, which Compose fills from its semantics).
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=60).stdout


def nodes(serial):
    xml = adb(serial, "exec-out", "uiautomator", "dump", "/dev/tty")
    xml = xml[xml.find("<?xml"):xml.rfind(">") + 1] if "<?xml" in xml else ""
    if not xml:
        return []
    out = []
    for n in ET.fromstring(xml).iter("node"):
        for label in (n.get("text") or "", n.get("content-desc") or ""):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if label and m:
                x1, y1, x2, y2 = map(int, m.groups())
                out.append((label, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def find(serial, text):
    """An exact match first ("Connect", not the title "Connect to this computer?"), else the first that starts with TEXT."""
    found = nodes(serial)
    for label, x, y in found:
        if label == text:
            return x, y
    for label, x, y in found:
        if label.startswith(text):
            return x, y
    return None


def scroll(serial):
    size = re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size"))
    w, h = (int(size.group(1)), int(size.group(2))) if size else (1080, 2400)
    adb(serial, "shell", "input", "swipe", str(w // 2), str(h * 3 // 4), str(w // 2), str(h // 4), "300")
    time.sleep(1)


def main():
    serial, command = sys.argv[1], sys.argv[2]
    text = sys.argv[3] if len(sys.argv) > 3 else ""
    if command == "dump":
        for label, _, _ in nodes(serial):
            print(label)
        return 0
    if command == "has":
        return 0 if find(serial, text) else 1
    if command == "wait":
        for _ in range(20):
            if find(serial, text):
                return 0
            time.sleep(1)
        return 1
    if command == "gone":
        for _ in range(30):
            if not find(serial, text):
                return 0
            time.sleep(1)
        return 1
    if command == "tap":
        for attempt in range(7):
            at = find(serial, text)
            if at:
                adb(serial, "shell", "input", "tap", str(at[0]), str(at[1]))
                time.sleep(1.5)
                return 0
            if attempt < 6:
                scroll(serial)
        return 1
    print(__doc__, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
