#!/usr/bin/env bash
# Installs the APK on a running emulator, opens every tab, takes screenshots and fails on any crash.
# Usage: scripts/smoke-test.sh <api-level>
API="$1"
OUT=smoke
mkdir -p "$OUT"
PKG=app.devdeck

T() { timeout "$@"; }   # every adb call gets a time limit so a hang cannot block the job

T 300 adb wait-for-device
T 600 adb shell 'while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done' || echo "boot not confirmed"
echo "Android $(adb shell getprop ro.build.version.release) / API $(adb shell getprop ro.build.version.sdk)"
T 300 adb install -r Humantize-DevApp.apk || { echo "INSTALL FAILED"; exit 1; }
# Allow notifications up front (Android 13+), otherwise the permission dialog covers the app in screenshots.
T 30 adb shell pm grant app.devdeck android.permission.POST_NOTIFICATIONS 2>/dev/null || true
T 30 adb logcat -c

shot() { T 60 adb exec-out screencap -p > "$OUT/api$API-$1.png"; }

T 120 adb shell am start -W -n $PKG/.ui.MainActivity
sleep 12
shot 1-start

# Tap the navigation blocks by their text (found via the accessibility tree), not by guessed coordinates.
tap_text() {
  T 60 adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  T 30 adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1
  XY=$(python3 - "$OUT/ui.xml" "$1" <<'PY'
import re, sys, xml.etree.ElementTree as ET
want = sys.argv[2].lower()
for n in ET.parse(sys.argv[1]).iter("node"):
    if want in ((n.get("text") or "").strip().lower(), (n.get("content-desc") or "").strip().lower()):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [ -n "$XY" ]; then T 30 adb shell input tap $XY; else echo "not found: $1"; fi
}

i=2
for name in planer alltag ki-chat terminal dateien server einstellungen start; do
  tap_text "$name"
  sleep 4
  shot "$i-$name"
  i=$((i + 1))
done

T 60 adb logcat -d > "$OUT/logcat-api$API.txt"
echo "=== Crash check (API $API) ==="
# Only real crashes of this app count; the emulator's own services (GMS, WebView sandbox) sometimes die.
if grep -A1 "FATAL EXCEPTION" "$OUT/logcat-api$API.txt" | grep -q "Process: $PKG,"; then
  echo "CRASH on API $API:"
  grep -A 60 "FATAL EXCEPTION" "$OUT/logcat-api$API.txt" | head -120
  exit 1
fi
echo "OK: no crash of $PKG on API $API"
