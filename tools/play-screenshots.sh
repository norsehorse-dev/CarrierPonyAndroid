#!/usr/bin/env bash
# Store screenshots for Google Play and F-Droid, in every app language.
#
# Installs the foss debug build, sets the display to 1080x1920 (Play rejects
# anything taller than 2:1), puts SystemUI in demo mode (09:41, full battery
# and signal, no notifications), then for each language clears the app,
# seeds demo data (see app/src/debug/.../demo/Demo.kt) and captures each
# screen. Raw captures land in fastlane/metadata/android/<locale>/images/
# phoneScreenshots, which F-Droid shows as is. tools/frame_screenshots.py
# turns them into the framed Play set.
#
# Run with exactly one device connected; an emulator is easiest.

set -euo pipefail
cd "$(dirname "$0")/.."

PKG=com.carrierpony.app
ACTIVITY="$PKG/.MainActivity"
LANGS="en:en-US de:de-DE es:es-ES fr:fr-FR it:it-IT ja:ja-JP pt:pt-BR ru:ru-RU zh:zh-CN"
SCREENS="inbox chat group files pair"
ONLY="${1:-}"

# Use the SDK's adb, the same one Gradle's install uses. A second adb of a
# different version (Homebrew's, say) restarts the server and knocks the
# device offline mid-run.
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB_BIN="$SDK/platform-tools/adb"
[ -x "$ADB_BIN" ] || ADB_BIN=$(command -v adb)

serials=$("$ADB_BIN" devices | awk 'NR>1 && $2=="device" {print $1}')
count=$(printf '%s\n' "$serials" | grep -c . || true)
if [ "$count" != "1" ]; then
  echo "Connect exactly one device or emulator (found $count)." >&2
  exit 1
fi
SERIAL=$serials
adb() { "$ADB_BIN" -s "$SERIAL" "$@"; }

./gradlew :app:installFossDebug

# Wait until the device answers again after the install.
"$ADB_BIN" -s "$SERIAL" wait-for-device
for _ in $(seq 1 30); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then break; fi
  sleep 1
done

demo() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }

restore() {
  demo exit || true
  adb shell settings put global sysui_demo_allowed 0 || true
  adb shell wm size reset || true
  adb shell wm density reset || true
  adb shell svc power stayon false || true
}
trap restore EXIT

adb shell wm size 1080x1920
adb shell wm density 420
adb shell input keyevent KEYCODE_WAKEUP
adb shell svc power stayon usb
adb shell wm dismiss-keyguard
adb shell settings put global sysui_demo_allowed 1
demo enter
demo clock -e hhmm 0941
demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true
demo network -e mobile hide
demo notifications -e visible false

for pair in $LANGS; do
  lang=${pair%%:*}
  locale=${pair##*:}
  if [ -n "$ONLY" ] && [ "$ONLY" != "$lang" ]; then continue; fi
  out="fastlane/metadata/android/$locale/images/phoneScreenshots"
  mkdir -p "$out"
  rm -f "$out"/*.png
  adb shell pm clear "$PKG" >/dev/null
  adb shell am start -W -n "$ACTIVITY" --es demo_lang "$lang" >/dev/null
  sleep 5
  i=1
  for screen in $SCREENS; do
    adb shell am start -S -W -n "$ACTIVITY" --es demo_screen "$screen" >/dev/null
    sleep 3
    file="$out/${i}_${screen}.png"
    adb exec-out screencap -p > "$file"
    size=$(wc -c < "$file" | tr -d ' ')
    if [ "$size" -lt 40000 ]; then
      echo "Blank capture: $file ($size bytes). Is the screen on and unlocked?" >&2
      exit 1
    fi
    i=$((i + 1))
  done
  echo "$locale: $((i - 1)) screenshots"
done
