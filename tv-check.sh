#!/bin/bash
# Read-only check of the VU TV for the Glass launcher project. Changes nothing on the TV.
TV="192.168.1.4:5555"
OUT="$(cd "$(dirname "$0")" && pwd)/tv-report.txt"

if ! command -v adb >/dev/null 2>&1; then
  echo "ADB not found. Installing Google's platform tools with Homebrew..."
  if command -v brew >/dev/null 2>&1; then
    brew install --cask android-platform-tools || { echo "Install failed. Tell Claude."; exit 1; }
  else
    echo "Homebrew not found. Tell Claude and we'll install ADB another way."; exit 1
  fi
fi

echo "Connecting to the TV at $TV ..."
adb connect "$TV"
echo ""
echo ">>> Look at the TV now: if it asks 'Allow USB debugging?', tick 'Always allow' and press Allow."
echo ">>> Waiting up to 60 seconds..."
for i in $(seq 1 30); do
  STATE=$(adb -s "$TV" get-state 2>/dev/null)
  [ "$STATE" = "device" ] && break
  sleep 2
done
if [ "$(adb -s "$TV" get-state 2>/dev/null)" != "device" ]; then
  echo "Could not connect (state: $(adb devices | grep "$TV")). Tell Claude what you see on the TV."; exit 1
fi

S() { echo ""; echo "===== $1 ====="; shift; adb -s "$TV" shell "$@" 2>&1; }
{
  echo "TV check report  $(date)"
  S "Model / build" 'for p in ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release ro.build.version.sdk ro.build.version.security_patch ro.board.platform ro.hardware ro.build.fingerprint; do echo "$p = $(getprop $p)"; done'
  S "CPU" 'grep -m1 -i hardware /proc/cpuinfo; grep -c ^processor /proc/cpuinfo'
  S "UI resolution + density" 'wm size; wm density'
  S "Display modes" 'dumpsys display | grep -m3 -E "mBaseDisplayInfo|supportedModes"'
  S "Memory" 'head -3 /proc/meminfo'
  S "Storage" 'df -h /data'
  S "GPU" 'dumpsys SurfaceFlinger | grep -m2 -i GLES'
  S "Blur support" 'echo "sf blur prop = $(getprop ro.surface_flinger.supports_background_blur)"'
  S "Current HOME handler" 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
  S "All HOME apps" 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
  S "Screensaver settings" 'for k in screensaver_enabled screensaver_components screensaver_activate_on_sleep screensaver_activate_on_dock sleep_timeout; do echo "$k = $(settings get secure $k)"; done; echo "screen_off_timeout = $(settings get system screen_off_timeout)"'
  S "TV inputs" 'dumpsys tv_input | grep -E "TvInputInfo|id=|type=" | head -40'
  S "Launcher-related packages" 'pm list packages | grep -i -E "launcher|tungsten|setupwraith|home|vu|katniss|tvrecommend|leanback" | sort'
  S "All packages" 'pm list packages | sort'
} > "$OUT"

echo ""
echo "Done. Report saved to: $OUT"
echo "Go back to Claude and say 'done'."
