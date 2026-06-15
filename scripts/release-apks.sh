#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# release-apks.sh — build + bundle signed release APKs
#
# Output: dist/
#   app-arm64-v8a-release.apk
#   app-armeabi-v7a-release.apk
#   app-x86_64-release.apk
#   app-universal-release.apk
#
# Prereqs:
#   • keystore/tvgram-release.jks exists (run scripts/generate-keystore.sh)
#   • keystore.properties is filled
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

log()  { printf '\033[1;36m[release]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[release]\033[0m %s\n' "$*" >&2; exit 1; }

[ -f "keystore/tvgram-release.jks" ] || fail "Missing keystore. Run: bash scripts/generate-keystore.sh"
[ -f "keystore.properties" ]        || fail "Missing keystore.properties — see docs/RELEASE.md for template"

log "Cleaning previous build ..."
./gradlew clean

log "Building release APKs (signs all 4 splits) ..."
./gradlew assembleRelease

DIST=dist
rm -rf "$DIST"
mkdir -p "$DIST"

log "Copying to $DIST/ ..."
cp -v app/build/outputs/apk/release/*.apk "$DIST/"

log "Verifying signatures ..."
for apk in "$DIST"/*.apk; do
    log "  $apk"
    jarsigner -verify -verbose "$apk" 2>&1 | tail -2
done

log "Listing dist/ ..."
ls -lh "$DIST/"

log "✅ Done. Next: git tag vX.Y.Z && gh release create vX.Y.Z $DIST/*.apk"
