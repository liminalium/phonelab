#!/usr/bin/env bash
# Installs the APK on a running emulator, walks through every screen of the
# system check, and saves screenshots, the saved report and logcat.
# Fails if the app crashes or the first check never finishes.
#
# Usage: scripts/screenshots.sh path/to/phonelab.apk out-dir
set -uo pipefail
APK=$1
OUT=$2
PKG=io.github.liminalium.phonelab
HERE=$(cd "$(dirname "$0")" && pwd)
FAILED=0
mkdir -p "$OUT"

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb exec-out cat /sdcard/ui.xml; }
find_view() { dump | python3 "$HERE/ui.py" "$@"; }
tap() {
  local xy
  xy=$(find_view "$@") || { echo "not on screen: $*"; return 1; }
  adb shell input tap $xy
  sleep 1
}
wait_for() {
  local timeout=$1; shift
  local end=$((SECONDS + timeout))
  while ((SECONDS < end)); do
    find_view "$@" >/dev/null && return 0
    sleep 2
  done
  echo "timed out after ${timeout}s waiting for: $*"
  return 1
}
shot() { sleep 1; adb exec-out screencap -p >"$OUT/$1.png"; echo "saved $1.png"; }
swipe_up() { adb shell input swipe 540 1700 540 700 400; sleep 1; }
to_top() { for _ in $(seq 12); do adb shell input swipe 540 500 540 2000 80; done; sleep 1; }
# Scrolls until the view is on screen, then drags it up near the top.
bring_to_top() {
  local xy=""
  for _ in $(seq 25); do
    xy=$(find_view "$@") && break
    swipe_up
  done
  [ -n "$xy" ] || { echo "never found: $*"; return 1; }
  local y=${xy#* }
  if ((y > 450)); then adb shell input swipe 540 "$y" 540 300 1500; sleep 1; fi
}

# Location on, and a fix in Berlin, so the GNSS and Wi-Fi tests have something to report.
adb shell settings put secure location_providers_allowed +gps
adb shell settings put secure location_providers_allowed +network
adb emu geo fix 13.405 52.52 >/dev/null 2>&1 || true

adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"

# 1. First launch: phone info runs by itself.
wait_for 90 contains "Checked " || FAILED=1
shot 01-first-launch
swipe_up
shot 02-phone-info

# 2. A card opened up, with one camera expanded.
bring_to_top text "Cameras" && tap text "Cameras"
tap text "▸ cameras" && tap text "▸ 0"
shot 03-card-details
tap text "Cameras"

# 3. What the recorder still needs, with shortcuts to settings.
bring_to_top text "Recorder readiness"
shot 04-recorder-readiness

# 4. Hardware tests: the permission dialogs, then the results.
to_top
tap text "Run hardware tests"
sleep 2
shot 05-permission-request
for _ in $(seq 6); do
  tap id permission_allow_foreground_only_button 2>/dev/null ||
    tap id permission_allow_button 2>/dev/null || break
done
wait_for 20 contains "Running hardware tests" || true
wait_for 180 contains "Checked " || FAILED=1
bring_to_top text "Hardware tests"
shot 06-hardware-tests
swipe_up
shot 07-hardware-tests-more

# 5. The module catalog checked against this phone.
bring_to_top text "What this phone can do"
shot 08-capabilities
swipe_up
shot 09-capabilities-more

# 6. Dark theme follows the system setting. Restart the app so the new
#    configuration is read; it reopens on the saved report.
adb shell settings put secure ui_night_mode 2
adb shell cmd uimode night yes
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -W -n "$PKG/.MainActivity"
wait_for 30 contains "Checked " || true
shot 10-dark-theme
adb shell cmd uimode night no

# The report the app saved to Download/PhoneLab.
adb pull /sdcard/Download/PhoneLab "$OUT/report" || FAILED=1
adb logcat -d >"$OUT/logcat.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat.txt"; then
  echo "::error::PhoneLab crashed on the emulator; see logcat.txt"
  grep -A 30 "FATAL EXCEPTION" "$OUT/logcat.txt" | head -60
  FAILED=1
fi
exit $FAILED
