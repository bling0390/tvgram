#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# dev-emulator.sh — macOS local dev loop with Android TV emulator
#
# 1. Boots a TV AVD if no device is already connected
#    (override via AVD_NAME=...; skip boot animation/audio for speed)
# 2. Builds the debug APK (skip with SKIP_BUILD=1)
# 3. Installs + launches on the emulator
# 4. Clears logcat buffer
# 5. Tails filtered logcat (Ctrl+C to exit; emulator stays alive)
#
# Prereqs:
#   • Android Studio installed (it sets ANDROID_HOME in ~/.zshrc)
#   • A TV AVD created: Tools → Device Manager → Create Device → TV
#       - Apple Silicon (M1/M2/M3): system image MUST be arm64-v8a
#       - Intel Mac: system image MUST be x86_64
#
# Usage:
#   bash scripts/dev-emulator.sh
#   AVD_NAME="Tvgram_API_34" bash scripts/dev-emulator.sh
#   SKIP_BUILD=1 bash scripts/dev-emulator.sh         # hot-iteration: no rebuild
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

APP_ID="tv.telegram"
ACTIVITY="${APP_ID}/.ui.MainActivity"

log()  { printf '\033[1;36m[dev-emulator]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[dev-emulator]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[dev-emulator]\033[0m %s\n' "$*" >&2; exit 1; }

# ── 1. Locate SDK ─────────────────────────────────────────────────────
if [ -z "${ANDROID_HOME:-}" ]; then
    for candidate in \
        "$HOME/Library/Android/sdk" \
        "$HOME/Android/Sdk" \
        "/opt/android-sdk"; do
        if [ -d "$candidate" ]; then
            ANDROID_HOME="$candidate"
            break
        fi
    done
fi
[ -n "${ANDROID_HOME:-}" ] && [ -d "$ANDROID_HOME" ] \
    || fail "Android SDK not found. Open Android Studio once so it sets ANDROID_HOME, or export it manually."

ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"

[ -x "$ADB" ] || fail "adb not found at $ADB — SDK Manager → SDK Tools tab → install Android SDK Platform-Tools"
[ -x "$EMU" ] || fail "emulator not found at $EMU — SDK Manager → SDK Tools tab → install Android Emulator"

log "SDK: $ANDROID_HOME"

# ── 2. Sanity ──────────────────────────────────────────────────────────
[ -f "local.properties" ] \
    || fail "local.properties missing — cp local.properties.example local.properties"

# ── 3. Pick an AVD ─────────────────────────────────────────────────────
list_avds() {
    "$EMU" -list-avds 2>/dev/null \
        | grep -E '^[[:space:]]*Name:' \
        | awk '{print $2}'
}

AVDS=$(list_avds)
[ -n "$AVDS" ] \
    || fail "No AVDs found. Create one in Android Studio: Tools → Device Manager → Create Device → TV"

if [ -n "${AVD_NAME:-}" ]; then
    echo "$AVDS" | grep -qx "$AVD_NAME" \
        || fail "AVD '$AVD_NAME' not found. Available:\n$AVDS"
else
    AVD_NAME=$(echo "$AVDS" | head -n 1)
    log "Picked first AVD: $AVD_NAME  (override via AVD_NAME=...)"
fi

# ── 4. Start emulator if not already running ──────────────────────────
RUNNING_DEV=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')

if [ -n "$RUNNING_DEV" ]; then
    log "Device already connected: $RUNNING_DEV  (skipping boot)"
    DEV="$RUNNING_DEV"
else
    log "Cold-booting $AVD_NAME ..."
    log "  • first boot: 1-3 min; subsequent boots: ~20-40s"
    log "  • logs: /tmp/tvgram-emu.log"

    # -no-snapshot     faster cold boot, no saved-state restore
    # -no-audio        no macOS speaker pop on startup
    # -no-boot-anim    skip Android boot animation
    # -gpu host        use host GPU (auto-picks Metal on Apple Silicon)
    # -no-window       headless mode (set HEADLESS=1 if you're SSH'd in)
    ARGS=( -avd "$AVD_NAME" -no-snapshot -no-audio -no-boot-anim -gpu host )
    [ "${HEADLESS:-0}" = "1" ] && ARGS+=( -no-window )

    nohup "$EMU" "${ARGS[@]}" >/tmp/tvgram-emu.log 2>&1 &
    EMU_PID=$!
    log "Emulator PID: $EMU_PID"

    log "Waiting for adb to detect device ..."
    "$ADB" wait-for-device

    log "Waiting for boot to complete (sys.boot_completed=1) ..."
    BOOT=""
    for i in $(seq 1 180); do
        BOOT=$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')
        if [ "$BOOT" = "1" ]; then
            break
        fi
        sleep 2
        if ! kill -0 $EMU_PID 2>/dev/null; then
            echo "────────── /tmp/tvgram-emu.log (tail) ──────────" >&2
            tail -n 40 /tmp/tvgram-emu.log >&2
            fail "Emulator process died during boot."
        fi
    done

    [ "$BOOT" = "1" ] \
        || { tail -n 60 /tmp/tvgram-emu.log >&2; fail "Emulator didn't finish boot in 6 min."; }

    DEV=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
    log "✅ Emulator ready: $DEV"
fi

# ── 5. Build + install ────────────────────────────────────────────────
if [ "${SKIP_BUILD:-0}" = "1" ]; then
    log "SKIP_BUILD=1 — skipping ./gradlew installDebug"
    APK="app/build/outputs/apk/debug/app-universal-debug.apk"
    [ -f "$APK" ] \
        || fail "SKIP_BUILD=1 but $APK not found. Run without SKIP_BUILD first to build it."
    log "Installing existing $APK ..."
    "$ADB" -s "$DEV" install -r "$APK" >/dev/null
else
    log "Building + installing debug APK ..."
    ./gradlew installDebug
fi

# ── 6. Launch ──────────────────────────────────────────────────────────
log "Launching $ACTIVITY ..."
"$ADB" -s "$DEV" shell am force-stop "$APP_ID" || true
"$ADB" -s "$DEV" shell am start -n "$ACTIVITY" >/dev/null

# ── 7. Tail logcat (Ctrl+C to exit; emulator stays alive) ─────────────
log "Tailing logcat (filter: $APP_ID:V AndroidRuntime:E *:S) — Ctrl+C to exit"
log "Re-run this script to rebuild + reinstall; emulator stays up."
"$ADB" -s "$DEV" logcat -c
exec "$ADB" -s "$DEV" logcat -v color "$APP_ID:V" "AndroidRuntime:E" "*:S"