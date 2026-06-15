#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# install-sdk.sh — one-time toolchain install for vultr dev host
#
# Installs:
#   • Eclipse Temurin 17 (JDK)
#   • Android cmdline-tools (latest)
#   • Android SDK platform 34
#   • Android SDK build-tools 34.0.0
#   • Android SDK platform-tools (adb)
#   • Accepts all SDK licenses
#   • Persists ANDROID_HOME / PATH into ~/.bashrc
#
# Idempotent — safe to re-run.
# Disk: ~1.5-3 GB. Time: 5-15 min on vultr High Frequency.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

log()  { printf '\033[1;36m[install-sdk]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[install-sdk]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[install-sdk]\033[0m %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || fail "Run as root:  sudo bash scripts/install-sdk.sh"

# ── 1. JDK 17 (Temurin) ────────────────────────────────────────────────
if ! java -version 2>&1 | grep -q '"17\.'; then
    log "Installing Eclipse Temurin 17 ..."
    apt-get update -y
    apt-get install -y --no-install-recommends \
        wget apt-transport-https gnupg ca-certificates
    install -m 0755 -d /usr/share/keyrings
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
        | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
    chmod 0644 /usr/share/keyrings/adoptium.gpg
    . /etc/os-release
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb ${VERSION_CODENAME} main" \
        > /etc/apt/sources.list.d/adoptium.list
    apt-get update -y
    apt-get install -y --no-install-recommends temurin-17-jdk
else
    log "JDK 17 already installed: $(java -version 2>&1 | head -1)"
fi

# Resolve JAVA_HOME
JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(which java)")")")
export JAVA_HOME
log "JAVA_HOME=$JAVA_HOME"

# ── 2. Android SDK ─────────────────────────────────────────────────────
ANDROID_HOME=${ANDROID_HOME:-/opt/android-sdk}
export ANDROID_HOME
mkdir -p "$ANDROID_HOME/cmdline-tools"

CMDLINE_TOOLS_VERSION=11076708  # 2024-02 latest
NEED_INSTALL=1
if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
    log "cmdline-tools already present"
    NEED_INSTALL=0
fi

if [ "$NEED_INSTALL" -eq 1 ]; then
    log "Downloading Android cmdline-tools ..."
    TMP=$(mktemp -d)
    cd "$TMP"
    curl -fsSL -o cmdline-tools.zip \
        "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip"
    apt-get install -y --no-install-recommends unzip
    unzip -q cmdline-tools.zip
    rm -rf "$ANDROID_HOME/cmdline-tools/latest"
    mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
    mv cmdline-tools/* "$ANDROID_HOME/cmdline-tools/latest/"
    cd /
    rm -rf "$TMP"
fi

# ── 3. Install SDK packages ───────────────────────────────────────────
PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export PATH

log "Accepting SDK licenses ..."
yes 2>/dev/null | sdkmanager --licenses >/dev/null 2>&1 || true

log "Installing platform-34, build-tools 34.0.0, platform-tools ..."
sdkmanager --install \
    "platforms;android-34" \
    "build-tools;34.0.0" \
    "platform-tools" \
    >/dev/null

# ── 4. Persist env into ~/.bashrc AND ~/.profile ──────────────────────
# ~/.bashrc → interactive bash logins
# ~/.profile → POSIX sh logins (incl. `sh -l`, `dash -l`, `su -`)
# Also drop into /etc/profile.d/ so any login shell picks it up.
ENV_BLOCK=$(cat <<EOF

# >>> android-dev-env >>>
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_HOME
export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools
# <<< android-dev-env <<<
EOF
)
MARK="# >>> android-dev-env >>>"

for f in "$HOME/.bashrc" "$HOME/.profile"; do
    if [ -f "$f" ] && ! grep -q "$MARK" "$f" 2>/dev/null; then
        log "Persisting env into $f"
        printf '%s\n' "$ENV_BLOCK" >> "$f"
    fi
done

# /etc/profile.d/ — system-wide, picked up by login shells
if [ -d /etc/profile.d ] && [ ! -f /etc/profile.d/android-dev-env.sh ]; then
    log "Persisting env into /etc/profile.d/android-dev-env.sh"
    cat > /etc/profile.d/android-dev-env.sh <<EOF
$ENV_BLOCK
EOF
    chmod 644 /etc/profile.d/android-dev-env.sh
fi

# ── 5. Verify ──────────────────────────────────────────────────────────
log "Verifying ..."
JAVA_HOME=$JAVA_HOME PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools \
    bash -c '
    java -version 2>&1
    sdkmanager --version
    adb --version
    echo "JAVA_HOME=$JAVA_HOME"
    echo "ANDROID_HOME=$ANDROID_HOME"
'

log "✅ Done. Open a new shell or run:  source ~/.bashrc"
