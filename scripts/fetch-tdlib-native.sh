#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# fetch-tdlib-native.sh — download libtdjson.so for all 4 ABIs
#
# The .so files are NOT in git (see app/src/main/jniLibs/README.md).
# Run this once after cloning the repo, or whenever you need to refresh
# the binaries to a newer TDLib version pinned by the upstream mirror.
#
# Source: https://github.com/bling0390/telegram-android-tv/tree/main/core/tdlib/src/main/jniLibs
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

BASE="https://raw.githubusercontent.com/bling0390/telegram-android-tv/main/core/tdlib/src/main/jniLibs"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/jniLibs"

log() { printf '\033[1;36m[fetch-tdlib]\033[0m %s\n' "$*"; }

mkdir -p "$DEST/arm64-v8a" "$DEST/armeabi-v7a" "$DEST/x86" "$DEST/x86_64"

for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    log "Downloading $abi/libtdjson.so ..."
    curl -fSL --progress-bar \
        -o "$DEST/$abi/libtdjson.so" \
        "$BASE/$abi/libtdjson.so"
done

log "Verifying ..."
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    f="$DEST/$abi/libtdjson.so"
    [ -s "$f" ] || { echo "FAIL: $f is empty"; exit 1; }
    printf '  %-12s %s B\n' "$abi" "$(stat -c %s "$f")"
done

log "✅ Done. Now run: ./gradlew assembleDebug"
