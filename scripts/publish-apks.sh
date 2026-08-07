#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# publish-apks.sh — sync tvgram APKs to docker-app file-nginx
#
# Run after `./gradlew assembleDebug` (or assembleRelease):
#   bash scripts/publish-apks.sh                       # debug (default)
#   VARIANT=release bash scripts/publish-apks.sh       # release
#
# Prereq: only meaningful on the vultr dev host where /root/docker-app
#         is the bind mount target for the file-nginx container.
#         On macOS / other machines this script no-ops.
#
# Why cp (not symlink):
#   Docker bind mount does NOT follow symlinks pointing outside the
#   mount source — security default. APKs must be real files in the
#   /root/docker-app/file-data tree.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

# Skip on non-vultr machines (no docker-app stack)
[ -d /root/docker-app ] || { echo "[publish] not vultr — skipping"; exit 0; }

VARIANT=${VARIANT:-debug}
SRC="/root/.openclaw/workspace/projects/tvgram/app/build/outputs/apk/$VARIANT"
DST="/root/docker-app/file-data/tvgram/$VARIANT"

log()  { printf '\033[1;36m[publish]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[publish]\033[0m %s\n' "$*" >&2; exit 1; }

[ -d "$SRC" ] || fail "Build output not found: $SRC — run ./gradlew assemble${VARIANT^} first"
[ -d /root/docker-app/file-data ] \
    || fail "/root/docker-app/file-data missing — don't rmdir it; instead: docker compose -C /root/docker-app restart file-nginx"

mkdir -p "$DST"

# Find APKs at top level (skip output-metadata.json etc.)
APKS=( "$SRC"/*.apk )
[ ${#APKS[@]} -gt 0 ] || fail "No .apk files in $SRC"

log "Syncing $VARIANT APKs → $DST"
# Clean stale APKs from previous naming convention (e.g. app-arm64-v8a-debug.apk)
# so we don't end up with both old AND new filenames coexisting on nginx.
find "$DST" -maxdepth 1 -name "*.apk" -delete 2>/dev/null || true
cp -f "${APKS[@]}" "$DST/"

# Rename to: tvgram-<version>-<buildType>-<abi|universal>.apk
#   - debug:    tvgram-1.0.0-debug-arm64-v8a.apk
#   - release:  tvgram-1.0.0-release-arm64-v8a.apk
#   - universal: tvgram-1.0.0-<buildType>-universal.apk
# Done in shell rather than Gradle DSL because AGP 8.4's
# androidComponents.onVariants API exposes VariantOutput as read-only,
# and the legacy applicationVariants API has Kotlin DSL type-resolution
# issues. This script-based approach survives AGP upgrades.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Read VERSION_NAME from the generated BuildConfig.java — that's the
# RESOLVED value after Kotlin string interpolation. build.gradle.kts
# itself may contain template tokens like "$buildNumber" that haven't
# been interpolated yet. Fall back to the source file only if no
# generated BuildConfig.java exists yet.
BUILD_CONFIG=$(find "$ROOT/app/build/generated/source/buildConfig" -name "BuildConfig.java" 2>/dev/null | head -1)
if [ -n "$BUILD_CONFIG" ] && [ -f "$BUILD_CONFIG" ]; then
    VERSION=$(grep -oP 'VERSION_NAME\s*=\s*"\K[^"]+' "$BUILD_CONFIG" | head -1)
else
    VERSION=$(grep -oP 'versionName\s*=\s*"\K[^"]+' "$ROOT/app/build.gradle.kts" | head -1)
fi
VERSION="${VERSION:-0.0.0}"
# Strip trailing -debug / -release suffix that BuildConfig.VERSION_NAME
# already carries (versionNameSuffix is applied at build time). Without
# this strip, filenames come out as tvgram-1.0.0.2-debug-debug-arm64.apk.
VERSION="${VERSION%-debug}"
VERSION="${VERSION%-release}"
cd "$DST"
for f in app-*.apk; do
    [ -f "$f" ] || continue
    # app-<suffix>-<variant>.apk → tvgram-<version>-<variant>-<suffix>.apk
    if [[ "$f" =~ ^app-(.+)-${VARIANT}\.apk$ ]]; then
        suffix="${BASH_REMATCH[1]}"  # arm64-v8a, armeabi-v7a, x86_64, x86, universal
        mv "$f" "tvgram-${VERSION}-${VARIANT}-${suffix}.apk"
    fi
done

log "✅ Published:"
shopt -s nullglob
for f in "$DST"/tvgram-*.apk; do
    name=$(basename "$f")
    size=$(stat -c '%s' "$f")
    printf '  %8d  %s\n' "$size" "$f"
done
shopt -u nullglob

log "URLs (HTTPS, LE cert via traefik):"
shopt -s nullglob
for f in "$DST"/tvgram-*.apk; do
    name=$(basename "$f")
    log "  https://file.goatv.org/tvgram/$VARIANT/$name"
done
shopt -u nullglob

# Clean copy-paste block (no log prefix) — for the assistant to grab and
# paste into chat after each build+publish.
echo ""
echo "── copy-paste URLs ─────────────────────────────────────"
shopt -s nullglob
for f in "$DST"/tvgram-*.apk; do
    echo "https://file.goatv.org/tvgram/$VARIANT/$(basename "$f")"
done
echo "─────────────────────────────────────────────────────────"