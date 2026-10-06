#!/usr/bin/env sh
# Usage: scripts/tap.sh "<visible text>"  — taps the first on-screen element with that text (via uiautomator).
adb() { "${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb" "$@"; }
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
adb shell cat /sdcard/ui.xml | python3 -c '
import sys, re, xml.etree.ElementTree as ET
want = sys.argv[1].lower()
for n in ET.fromstring(sys.stdin.read()).iter("node"):
    if n.get("text", "").lower() == want or n.get("content-desc", "").lower() == want:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2); break
else:
    sys.exit("not found: " + sys.argv[1])
' "$1" | { read -r x y && adb shell input tap "$x" "$y" && echo "tapped $1 at $x,$y"; }
