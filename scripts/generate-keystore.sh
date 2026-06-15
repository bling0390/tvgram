#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# generate-keystore.sh — one-time, creates the release signing keystore
#
# Generates:
#   • keystore/tvgram-release.jks  (the key — keep it forever)
#   • keystore.properties          (passwords — keep these too)
#
# IMPORTANT: If you lose the keystore or its passwords, you can NEVER
# publish an update under the same identity. Back it up.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

log()  { printf '\033[1;36m[keystore]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[keystore]\033[0m %s\n' "$*" >&2; exit 1; }

KEYSTORE_DIR=keystore
KEYSTORE_FILE=$KEYSTORE_DIR/tvgram-release.jks
ALIAS=tvgram
VALIDITY=10000  # ~27 years

[ -f "$KEYSTORE_FILE" ] && fail "Keystore already exists at $KEYSTORE_FILE. Refusing to overwrite."

mkdir -p "$KEYSTORE_DIR"
chmod 700 "$KEYSTORE_DIR"

log "Creating release keystore ..."
log "  file:    $KEYSTORE_FILE"
log "  alias:   $ALIAS"
log "  validity: $VALIDITY days"
log ""

# Use non-interactive form if both passwords are in env, else prompt.
if [ -n "${KEYSTORE_STORE_PASSWORD:-}" ] && [ -n "${KEYSTORE_KEY_PASSWORD:-}" ]; then
    STORE_PASS="$KEYSTORE_STORE_PASSWORD"
    KEY_PASS="$KEYSTORE_KEY_PASSWORD"
else
    read -r -s -p "Store password (min 6 chars): " STORE_PASS; echo
    read -r -s -p "Key password (min 6 chars, can match store): " KEY_PASS; echo
fi

[ "${#STORE_PASS}" -ge 6 ] || fail "Store password must be ≥ 6 chars"
[ "${#KEY_PASS}"  -ge 6 ] || fail "Key password must be ≥ 6 chars"

keytool -genkey -v \
    -keystore "$KEYSTORE_FILE" \
    -keyalg RSA -keysize 2048 -validity "$VALIDITY" \
    -alias "$ALIAS" \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=Tvgram, OU=Mobile, O=Tvgram, L=City, S=State, C=CN"

chmod 600 "$KEYSTORE_FILE"

# Write keystore.properties (gitignored)
cat > keystore.properties <<EOF
storeFile=$KEYSTORE_FILE
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 keystore.properties

log "✅ Keystore created: $KEYSTORE_FILE"
log "✅ Config written:   keystore.properties"
log ""
log "⚠️  Back up BOTH files now. Without them, you cannot sign updates."
