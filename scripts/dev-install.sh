#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# dev-install.sh — the daily dev loop
#
# 1. Builds debug APK
# 2. Installs to connected TV via adb
# 3. Launches the main activity
# 4. Clears logcat buffer
# 5. Tails filtered logcat (Ctrl+C to exit)
#
# Prereqs:
#   • TV is in developer mode with network debug ON
#   • adb connect <TV-IP>:5555  has been run
#   • adb devices  shows your TV
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_ID="tv.telegram"
ACTIVITY="${APP_ID}/.ui.MainActivity"

log()  { printf '\033[1;36m[dev-install]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[dev-install]\033[0m %s\n' "$*" >&2; exit 1; }

# Sanity
[ -f "local.properties" ] || fail "local.properties missing — cp local.properties.example local.properties"

# 1. Build + install
log "Building debug APK ..."
./gradlew installDebug

# 2. Pick a device (the only connected one, or first TV)
DEV=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
[ -n "$DEV" ] || fail "No adb device connected. Run: adb connect <TV-IP>:5555"
log "Target device: $DEV"

# 3. Launch
log "Launching $ACTIVITY ..."
adb -s "$DEV" shell am force-stop "$APP_ID" || true
adb -s "$DEV" shell am start -n "$ACTIVITY" >/dev/null

# 4. Clear + tail logs
log "Tailing logcat (filter: Tvgram:V *:S) — Ctrl+C to exit"
adb -s "$DEV" logcat -c
exec adb -s "$DEV" logcat -v color "$APP_ID:V" "AndroidRuntime:E" "*:S"
