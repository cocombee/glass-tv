#!/usr/bin/env bash
# Build the launcher APK and install it on the TV as the default home screen.
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
# macOS: java_home or Homebrew; Linux: the java on PATH.
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || { [ -d /opt/homebrew/opt/openjdk@17 ] && echo /opt/homebrew/opt/openjdk@17; } || java -XshowSettings:properties -version 2>&1 | awk '/java.home/ {print $3}')}"
# Android Studio's default SDK location on macOS, then on Linux, then Homebrew's command-line tools.
export ANDROID_HOME="${ANDROID_HOME:-$(for d in ~/Library/Android/sdk ~/Android/Sdk; do [ -d "$d" ] && echo "$d" && break; done)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease -q --console=plain
TOOLS="$(cd ../tools && pwd)"   # absolute, so the venv's Python doesn't warn about "../" in its prefix
PY=$TOOLS/venv/bin/python
[ -x "$PY" ] || PY=$TOOLS/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
# Which app owns Home differs by build (com.google.android.tvlauncher on Android TV,
# com.google.android.apps.tv.launcherx on newer Google TV), so ask the TV instead of guessing.
# Only a launcher we recognise is accepted: the answer can also be Android's chooser ("android"),
# the setup wizard, or another third-party launcher, none of which should be disabled by accident.
# Pass STOCK_LAUNCHER=<package> to name it yourself, or STOCK_LAUNCHER=none to disable nothing.
KNOWN_STOCK_LAUNCHERS="com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher com.mitv.tvhome"
# Refuse a TV the home screen cannot run on before anything changes, and say why.
SDK=$("$PY" -I ../tools/tv.py 'getprop ro.build.version.sdk' | tail -n 1 | tr -d '\r ') || {
  echo "ERROR: could not reach the TV. Check TV_HOST and the debugging prompt on the TV. Nothing was changed." >&2; exit 1; }
if [ "${SDK:-0}" -lt 26 ] 2>/dev/null; then
  echo "ERROR: this TV runs Android API $SDK; the Glass TV home screen needs Android 8 (API 26) or newer." >&2
  echo "Debloating still works (tools/disable.sh). Nothing was changed." >&2; exit 1
fi
MAKER=$("$PY" -I ../tools/tv.py 'getprop ro.product.manufacturer' | tail -n 1 | tr -d '\r')
if [ "$MAKER" = Amazon ]; then
  echo "ERROR: this is a Fire TV. Fire OS takes Home back from any other launcher, so the Glass TV home screen" >&2
  echo "cannot stay. Debloating still works (tools/disable.sh). Nothing was changed." >&2; exit 1
fi
# Where Home goes now, so a failed install can put it back exactly.
ORIG_HOME=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
  | tail -n 1 | tr -d '\r')
if [ -z "${STOCK_LAUNCHER:-}" ]; then
  HOME_PKG=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
    | tail -n 1 | tr -d '\r' | cut -d/ -f1) || {
    echo "ERROR: could not ask the TV which launcher owns Home. Check TV_HOST and the debugging prompt on the TV," >&2
    echo "or pass STOCK_LAUNCHER=<package> yourself." >&2; exit 1; }
  case " $KNOWN_STOCK_LAUNCHERS " in
    *" $HOME_PKG "*) STOCK_LAUNCHER=$HOME_PKG ;;
    *)
      if [ "$HOME_PKG" = com.gathin.tvhome ]; then
        STOCK_LAUNCHER=none; echo "Home already belongs to this launcher; nothing will be disabled."
      elif [ "$HOME_PKG" = android ]; then
        # Android's chooser: two or more launchers tie and no default is set, which is where a TV lands
        # after tools/restore.sh followed by a re-applied list. Take the one known stock launcher among
        # the HOME claimants, if there is exactly one.
        CLAIMANTS=$("$PY" -I ../tools/tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME' \
          | tr -d '\r' | grep -o '[A-Za-z0-9_.]*/' | tr -d / | sort -u)
        FOUND=""; N=0
        for k in $KNOWN_STOCK_LAUNCHERS; do
          if printf '%s\n' "$CLAIMANTS" | grep -qx "$k"; then FOUND=$k; N=$((N + 1)); fi
        done
        if [ "$N" -eq 1 ]; then
          STOCK_LAUNCHER=$FOUND; echo "Home is Android's chooser; the stock launcher among the candidates is $STOCK_LAUNCHER."
        else
          echo "ERROR: Home is Android's launcher chooser and $N known stock launchers claim Home: pick a default on the TV first," >&2
          echo "or run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
        fi
      else
        echo "ERROR: Home currently resolves to '${HOME_PKG:-nothing}', which is not a stock launcher this script knows." >&2
        echo "  android                                  = Android's launcher chooser (two launchers tie); pick a default on the TV first" >&2
        echo "  com.google.android.tungsten.setupwraith  = Google TV's setup wizard; see README, Troubleshooting" >&2
        echo "  anything else                            = another launcher is the default" >&2
        echo "Run again with STOCK_LAUNCHER=<package to disable> or STOCK_LAUNCHER=none." >&2; exit 1
      fi ;;
  esac
fi
echo "Stock launcher: $STOCK_LAUNCHER"
if [ "$STOCK_LAUNCHER" = none ]; then DISABLE_STOCK="echo stock launcher left alone"
else DISABLE_STOCK="pm disable-user --user 0 $STOCK_LAUNCHER"; fi
APK=app/build/outputs/apk/release/app-release.apk

# The safeguard. From the install on, any failure (a check below, or a dropped connection that stops the
# script) puts the TV back as it was: Glass TV removed, the original launcher enabled and made Home again.
# SAFEGUARD_TEST=start|home makes that check fail on purpose and prints the rollback instead of running it.
ARMED=0
rollback() {
  [ "$ARMED" = 1 ] || return 0
  ARMED=0
  echo "Putting the TV back the way it was..." >&2
  # The launcher to bring back: the one disabled above, or, when Glass TV was already Home, any known stock
  # launcher an earlier install disabled.
  local restore="" k
  if [ "$STOCK_LAUNCHER" != none ]; then restore=$STOCK_LAUNCHER
  else
    local disabled
    disabled=$("$PY" -I ../tools/tv.py 'pm list packages -d' 2>/dev/null | tr -d '\r' | sed 's/^package://') || true
    for k in $KNOWN_STOCK_LAUNCHERS; do printf '%s\n' "$disabled" | grep -qx "$k" && restore="$restore $k"; done
  fi
  local cmds="pm uninstall com.gathin.tvhome"
  for k in $restore; do cmds="$cmds
pm enable --user 0 $k"; done
  case "$ORIG_HOME" in
    com.gathin.tvhome/*|android|"") ;;
    */*) cmds="$cmds
cmd package set-home-activity --user 0 $ORIG_HOME" ;;
  esac
  cmds="$cmds
input keyevent KEYCODE_HOME"
  if [ -n "${SAFEGUARD_TEST:-}" ]; then
    printf 'SAFEGUARD_TEST: would run on the TV:\n%s\n' "$cmds" >&2
  elif printf '%s\n' "$cmds" | "$PY" -I ../tools/tv.py --batch >/dev/null 2>&1; then
    echo "Done: Glass TV removed and the TV's own home screen is back${restore:+ ($restore)}." >&2
  else
    echo "Could not reach the TV to undo it. Once it is back on the network, run:" >&2
    echo "  TV_HOST=$TV_HOST tools/restore.sh <your debloat list>${restore:+ $restore}" >&2
  fi
}
trap 'st=$?; [ $st -ne 0 ] && rollback; exit $st' EXIT
# Glass TV in front, and the same process a few seconds later (not crashing and restarting).
healthy() {
  [ "${SAFEGUARD_TEST:-}" = "$1" ] && return 1
  local s p1 p2 f
  s=$("$PY" -I ../tools/tv.py 'sleep 4; a=$(pidof com.gathin.tvhome); sleep 3; b=$(pidof com.gathin.tvhome); f=$(dumpsys window windows 2>/dev/null | grep -m1 mCurrentFocus); echo "$a|$b|$f"' | tail -n 1 | tr -d '\r') || return 1
  p1=${s%%|*}; s=${s#*|}; p2=${s%%|*}; f=${s#*|}
  [ -n "$p1" ] && [ "$p1" = "$p2" ] && case "$f" in *com.gathin.tvhome*) return 0 ;; *) return 1 ;; esac
}
"$PY" -I ../tools/b64push.py "$APK" /data/local/tmp/tvhome.apk
# Install on its own: in the batch below a failed install (e.g. "not enough space" on a full /data)
# would not stop the stock launcher from being disabled.
OUT=$("$PY" -I ../tools/tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "ERROR: pm install failed; the stock launcher was left untouched." >&2; exit 1 ;;
esac
ARMED=1
printf '%s\n' \
  "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted" \
  "cmd package compile -m speed -f com.gathin.tvhome" \
  "am start -W -n com.gathin.tvhome/.MainActivity | grep -E '^(Status|Error)'" \
  | "$PY" -I ../tools/tv.py --batch
# Check it really runs on this TV before the TV's own launcher is touched.
if ! healthy start; then
  echo "ERROR: Glass TV did not start properly on this TV (it is not in front, or it keeps restarting)." >&2
  exit 1
fi
printf '%s\n' \
  "$DISABLE_STOCK" \
  "cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" \
  | "$PY" -I ../tools/tv.py --batch
# Order matters: the new launcher is started before the stock one is disabled, and the home
# preference is set after. Android drops the preference whenever the set of HOME apps changes, and
# on Google TV 14 the next candidate is the setup wizard's RecoveryActivity, which crash-loops
# system_server (see examples/xiaomi-tv-a-pro-2026). So check where Home ended up, and put the
# stock launcher back if it is not ours.
HOME_NOW=$("$PY" -I ../tools/tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | tail -n 1 | tr -d '\r')
case "$HOME_NOW" in
  com.gathin.tvhome/*) echo "Home now resolves to $HOME_NOW" ;;
  *)
    echo "ERROR: Home resolves to ${HOME_NOW:-nothing}, not to the new launcher." >&2
    echo "On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to" >&2
    echo "your debloat list (see README, Troubleshooting), apply the list, then run this script again." >&2
    exit 1 ;;
esac
# Press Home the way the remote does, and check Glass TV answers and stays up.
"$PY" -I ../tools/tv.py 'input keyevent KEYCODE_HOME' >/dev/null
if ! healthy home; then
  echo "ERROR: pressing Home did not bring up a working Glass TV." >&2
  exit 1
fi
ARMED=0

# Optional accessibility services. Off unless asked for, and Android switches them off again on
# every reinstall, so pass the flag each time you run this script.
#   INPUT_KEY_SERVICE=1   the remote's Input button (KEYCODE_TV) opens the input switcher from any
#                         app; Google TV needs this once the stock launcher is gone (InputKeyService)
#   HOME_KEY_SERVICE=1    remotes whose Home button sends KEYCODE_ALL_APPS instead of HOME, e.g. Xiaomi
#                         sticks: the key is routed to the launcher (HomeKeyService)
#   KEEP_HIGH_CONTRAST_TEXT=1  leave Android's High contrast text on (it is switched off otherwise, below)
enable_service() {   # appends to enabled_accessibility_services without dropping other services
  svc=com.gathin.tvhome/.$1
  "$PY" -I ../tools/tv.py "s=\$(settings get secure enabled_accessibility_services); case \"\$s\" in *$1*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; *) settings put secure enabled_accessibility_services \"\$s:$svc\" ;; esac; settings put secure accessibility_enabled 1; echo $1 enabled"
}
if [ "${INPUT_KEY_SERVICE:-0}" = 1 ]; then enable_service InputKeyService; fi
if [ "${HOME_KEY_SERVICE:-0}" = 1 ]; then enable_service HomeKeyService; fi

# High contrast text, an Android accessibility setting, draws a dark outline round every letter in every
# app; over Glass TV's photos it reads as a drop shadow under the clock, date and place names. It is
# switched off here, and said so, unless KEEP_HIGH_CONTRAST_TEXT=1. Settings > Accessibility turns it back on.
if [ "${KEEP_HIGH_CONTRAST_TEXT:-0}" != 1 ]; then
  HC=$("$PY" -I ../tools/tv.py 'settings get secure high_text_contrast_enabled' | tail -n 1 | tr -d '\r ') || HC=""
  if [ "$HC" = 1 ]; then
    "$PY" -I ../tools/tv.py 'settings put secure high_text_contrast_enabled 0' >/dev/null &&
      echo "High contrast text was on (it outlines all text in a dark border), so it was switched off. If you" &&
      echo "need it to read, switch it back on in Settings > Accessibility, or install with KEEP_HIGH_CONTRAST_TEXT=1."
  fi
fi
echo "Installed. Press Home on the remote to see it."
