#!/usr/bin/env bash
# Drives Weather Buddy on a headless Android emulator. Run from the repo root:
#
#   .claude/skills/run-weather-buddy/driver.sh <command> [args]
#
# Every command is one-shot (no REPL): the emulator keeps running between
# calls, so chain them freely. See SKILL.md for the full list.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
SKILL=$(cd "$(dirname "$0")" && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
if [ -z "${JAVA_HOME:-}" ]; then
  for j in /home/linuxbrew/.linuxbrew/opt/openjdk@21 /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/temurin-21-jdk-amd64; do
    [ -x "$j/bin/java" ] && { export JAVA_HOME=$j; break; }
  done
fi
ADB=$ANDROID_HOME/platform-tools/adb
PKG=io.github.intramuros.weatherbuddy
AVD=weather-buddy
IMAGE="system-images;android-36;google_apis;x86_64"
OUT=${OUT:-/tmp/weather-buddy}
mkdir -p "$OUT"

die() { echo "error: $*" >&2; exit 1; }

# Labelled nodes of the current screen: "x y<TAB>label<TAB>flags", one per line.
nodes() {
  "$ADB" shell uiautomator dump /sdcard/ui.xml >/dev/null
  "$ADB" exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys, xml.etree.ElementTree as ET
for n in ET.fromstring(sys.stdin.read()).iter("node"):
    label = n.get("text") or n.get("content-desc") or ""
    flags = ",".join(f for f in ("clickable", "checked", "selected") if n.get(f) == "true")
    if not label and not flags:
        continue
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    print(f"{(x1 + x2) // 2} {(y1 + y2) // 2}\t{label}\t{flags}")
'
}

# Centre of the first node whose label contains $1 (case-insensitive), or nothing.
find_text() { nodes | awk -F'\t' -v t="$1" 'index(tolower($2), tolower(t)) { print $1; exit }'; }

cmd=${1:-help}
shift || true
case $cmd in
  setup)
    # JDK 21 (as CI) and the SDK pieces this project needs, into $ANDROID_HOME. Idempotent.
    [ -n "${JAVA_HOME:-}" ] || { brew install openjdk@21; export JAVA_HOME=/home/linuxbrew/.linuxbrew/opt/openjdk@21; }
    if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
      zip=$(curl -s https://dl.google.com/android/repository/repository2-3.xml \
        | grep -oE 'commandlinetools-linux-[0-9]+_latest\.zip' | sort -t- -k3 -n -u | tail -1)
      mkdir -p "$ANDROID_HOME/cmdline-tools"
      curl -sSfLo "$OUT/clt.zip" "https://dl.google.com/android/repository/$zip"
      unzip -q -o "$OUT/clt.zip" -d "$ANDROID_HOME/cmdline-tools"
      mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    fi
    yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
    "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" platform-tools emulator \
      "platforms;android-37.0" "build-tools;37.0.0" "$IMAGE" | grep -v '^\[' || true
    [ -d "$HOME/.android/avd/$AVD.avd" ] || echo no | "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" \
      create avd -n "$AVD" -k "$IMAGE" -d pixel_6 >/dev/null
    echo "setup done: JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME avd=$AVD"
    ;;
  env)
    echo "export JAVA_HOME=$JAVA_HOME ANDROID_HOME=$ANDROID_HOME"
    ;;
  boot)
    # Headless emulator; returns once Android has finished booting (~50 s cold).
    # "boot --wipe" starts from a factory-fresh device (no app, stock wallpaper).
    if ! "$ADB" get-state >/dev/null 2>&1; then
      wipe=(); [ "${1:-}" = --wipe ] && wipe=(-wipe-data)
      nohup "$ANDROID_HOME/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-boot-anim \
        -no-snapshot -gpu swiftshader_indirect "${wipe[@]}" >"$OUT/emulator.log" 2>&1 &
    fi
    "$ADB" wait-for-device
    for _ in $(seq 120); do
      [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { echo booted; exit 0; }
      sleep 2
    done
    die "emulator did not boot; see $OUT/emulator.log"
    ;;
  build)
    (cd "$ROOT" && ./gradlew -q :app:assembleDebug)
    echo "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  install)
    "$ADB" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  launch)
    # Fresh main screen (NEW_TASK|CLEAR_TASK) without killing the process: a force-stop
    # makes Android drop our live wallpaper. "launch --clear" wipes the app's data first
    # (which also drops the wallpaper and the location permission).
    [ "${1:-}" = --clear ] && "$ADB" shell pm clear "$PKG" >/dev/null
    "$ADB" shell am start -W -f 0x10008000 -n "$PKG/.ui.MainActivity" | grep -E 'Status|TotalTime'
    sleep 3  # the first weather fetch and picture load land after the activity is drawn
    ;;
  ss)
    f=${1:-$OUT/screen.png}
    "$ADB" exec-out screencap -p >"$f"
    echo "$f"
    ;;
  ui)
    nodes
    ;;
  tap)
    # tap <text>: scrolls down (up to 5 screens) until a node with that text shows, then taps its centre.
    [ -n "${1:-}" ] || die "tap <text>"
    for _ in 1 2 3 4 5 6; do
      xy=$(find_text "$1")
      if [ -n "$xy" ]; then "$ADB" shell input tap $xy; sleep 1; echo "tapped '$1' at $xy"; exit 0; fi
      "$ADB" shell input swipe 540 1800 540 900 300; sleep 1
    done
    die "no '$1' on screen"
    ;;
  tap-xy)
    "$ADB" shell input tap "$1" "$2"
    ;;
  scroll)
    case ${1:-down} in
      down) "$ADB" shell input swipe 540 1800 540 700 300 ;;
      up) "$ADB" shell input swipe 540 700 540 1800 300 ;;
      top) for _ in 1 2 3 4; do "$ADB" shell input swipe 540 600 540 2000 150; done ;;
    esac
    sleep 1
    ;;
  back)
    "$ADB" shell input keyevent KEYCODE_BACK
    ;;
  home)
    "$ADB" shell input keyevent KEYCODE_HOME
    ;;
  wallpaper)
    # Applies the live wallpaper to home and lock screen through the system preview (what "Set" does).
    "$ADB" shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
      --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT "$PKG/.wallpaper.BuddyWallpaperService" >/dev/null
    sleep 3
    "$0" tap "Set wallpaper" >/dev/null
    sleep 1
    "$0" tap "Home screen and lock screen" >/dev/null
    sleep 2
    "$ADB" shell dumpsys wallpaper | grep -q "mWallpaperComponent=.*BuddyWallpaperService" \
      && echo "live wallpaper set" || die "live wallpaper not set"
    ;;
  widget)
    # Adds the 2x2 widget to the home screen through the launcher's widget picker (Pixel launcher).
    "$ADB" shell input keyevent KEYCODE_HOME; sleep 1
    "$ADB" shell input swipe 540 1000 540 1000 1200; sleep 1.5  # long-press empty home screen
    "$0" tap Widgets >/dev/null; sleep 2
    "$0" tap "Weather Buddy" >/dev/null; sleep 2
    "$0" tap "Weather Buddy widget" >/dev/null; sleep 2
    "$0" tap Add >/dev/null; sleep 4
    nodes | awk -F'\t' '/km\/h/ { print "widget: " $2; found = 1 } END { exit !found }' || die "widget not on the home screen"
    ;;
  locate)
    # locate <lat> <lon>: fakes the device location, then taps "Use my location".
    # The app asks the fused provider at BALANCED power, which `adb emu geo fix` (GPS) never reaches,
    # so this replaces the fused provider with a test provider.
    [ -n "${2:-}" ] || die "locate <lat> <lon>"
    "$ADB" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION
    "$ADB" shell appops set com.android.shell android:mock_location allow
    "$ADB" shell cmd location providers add-test-provider fused 2>/dev/null || true
    "$ADB" shell cmd location providers set-test-provider-enabled fused true
    "$ADB" shell cmd location providers set-test-provider-location fused --location "$1,$2" --accuracy 100
    "$0" launch >/dev/null
    "$0" tap "Use my location"
    sleep 8  # location, place name and the new forecast
    # The place is the label printed just before the "Location" row's button.
    nodes | awk -F'\t' '$2 == "Use my location" { print "location: " prev; exit } $2 != "" { prev = $2 }'
    ;;
  pref)
    # The app's DataStore preferences (style, wallpaper switches, location), decoded loosely.
    "$ADB" shell run-as "$PKG" cat files/datastore/settings.preferences_pb | strings
    ;;
  log)
    # App log since launch: our own tags plus crashes.
    pid=$("$ADB" shell pidof "$PKG" | tr -d '\r')
    if [ -n "$pid" ]; then "$ADB" logcat -d --pid="$pid"; fi
    "$ADB" logcat -d -b crash
    ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true
    for _ in $(seq 30); do pgrep -f "qemu-system.*-avd $AVD" >/dev/null || break; sleep 1; done
    echo stopped
    ;;
  *)
    sed -n '2,7p' "$0"
    echo "commands: setup env boot [--wipe] build install launch [--clear] ss [file] ui tap <text> tap-xy <x> <y>"
    echo "          scroll [down|up|top] back home wallpaper widget locate <lat> <lon> pref log stop"
    ;;
esac
