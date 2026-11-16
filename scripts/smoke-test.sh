#!/usr/bin/env bash
# Installs the APK on a running emulator, opens every tab, takes screenshots and fails on any crash.
# Usage: scripts/smoke-test.sh <api-level>
API="$1"
OUT=smoke
mkdir -p "$OUT"
PKG=app.devdeck

adb wait-for-device
adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done'
adb install -r Humantize-DevApp.apk || { echo "INSTALL FAILED"; exit 1; }
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/api$API-$1.png"; }

adb shell am start -W -n $PKG/.ui.MainActivity
sleep 12
shot 1-start

# Tap the navigation blocks by their text (found via the accessibility tree), not by guessed coordinates.
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  XY=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2].lower()
for n in ET.parse(sys.argv[1]).iter("node"):
    if (n.get("text") or "").strip().lower() == want:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$XY" ]; then adb shell input tap $XY; else echo "not found: $1"; fi
}

i=2
for name in term dateien server system chat; do
  tap_text "$name"
  sleep 4
  shot "$i-$name"
  i=$((i + 1))
done

adb logcat -d > "$OUT/logcat-api$API.txt"
echo "=== Process / crash check (API $API) ==="
if grep -q "FATAL EXCEPTION" "$OUT/logcat-api$API.txt"; then
  echo "CRASH on API $API:"
  grep -A 60 "FATAL EXCEPTION" "$OUT/logcat-api$API.txt" | head -120
  exit 1
fi
if ! adb shell pidof $PKG >/dev/null 2>&1 && ! adb shell ps | grep -q $PKG; then
  echo "App process not running on API $API"
  grep -iE "$PKG|AndroidRuntime" "$OUT/logcat-api$API.txt" | tail -60
  exit 1
fi
echo "OK: no crash on API $API"
