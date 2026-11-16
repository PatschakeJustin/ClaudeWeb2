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

SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
DENSITY=$(adb shell wm density | grep -o '[0-9]*' | tail -1)
# Phone layout: row of 5 navigation blocks just above the system navigation bar.
NAVBAR=$(( 48 * DENSITY / 160 ))
ROW=$(( H - NAVBAR - 23 * DENSITY / 160 ))
i=0
for name in chat terminal dateien server system; do
  X=$(( (2 * i + 1) * W / 10 ))
  adb shell input tap "$X" "$ROW"
  sleep 4
  shot "$((i + 2))-$name"
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
