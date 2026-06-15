# Tvgram — Build

> Toolchain installation and build commands for the vultr dev host.

## Requirements (already met on vultr)

- Ubuntu 22.04 LTS
- 4 vCPU / 8 GB RAM / 150 GB disk / 6.5 GB swap ✅
- `adb` (will be installed by `scripts/install-sdk.sh`)
- `git`, `curl`, `unzip` (preinstalled)

## One-time install (vultr)

```bash
bash scripts/install-sdk.sh
```

This installs:
1. **JDK 17 (Eclipse Temurin)** via Adoptium apt repo
2. **Android cmdline-tools** to `/opt/android-sdk/cmdline-tools/latest/`
3. **Android SDK platform 34** + **build-tools 34.0.0** + **platform-tools** (adb)
4. **Accepts SDK licenses** automatically
5. Sets up `ANDROID_HOME` and `PATH` for current + future shells

> Download size: ~1.5-3 GB. Takes 5-15 minutes on a vultr High Frequency
> instance. Idempotent — re-running is safe.

### Manual fallback (if the script breaks)

```bash
# JDK 17
sudo apt install -y wget apt-transport-https gnupg
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb jammy main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update && sudo apt install -y temurin-17-jdk

# Android SDK
sudo mkdir -p /opt/android-sdk/cmdline-tools
cd /tmp
curl -L -o cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
sudo unzip -q cmdline-tools.zip -d /tmp/clt
sudo mv /tmp/clt/cmdline-tools /opt/android-sdk/cmdline-tools/latest

export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

Persist into `~/.bashrc`:
```bash
cat >> ~/.bashrc <<'EOF'

# Android dev
export ANDROID_HOME=/opt/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
EOF
source ~/.bashrc
```

## Per-project setup

```bash
cd ~/workspace/projects/tvgram

# 1. Create local.properties from template
cp local.properties.example local.properties
# Edit and fill in real TG_API_ID / TG_API_HASH
# (api_id and api_hash are already filled in the example,
#  but you can change them — your own are at https://my.telegram.org/apps)

# 2. Verify toolchain
./gradlew --version
# Expected:
#   Gradle 8.5+
#   JVM:    17.x.x (Eclipse Adoptium)
#   Kotlin: 2.0.x
```

## Build commands

| Goal | Command | Output |
|---|---|---|
| Debug APK (universal) | `./gradlew assembleDebug` | `app/build/outputs/apk/debug/app-universal-debug.apk` |
| Debug APK (per-ABI) | `./gradlew assembleDebug` | 3 ABI splits + 1 universal |
| Release APK (unsigned) | `./gradlew assembleRelease` | 4 APKs (need keystore to sign) |
| All tests | `./gradlew test` | `app/build/reports/tests/` |
| Lint | `./gradlew lint` | `app/build/reports/lint-results-*.html` |
| Clean | `./gradlew clean` | removes `build/` |

For distribution, use `bash scripts/release-apks.sh` which handles
all four APKs at once.

## Gradle JVM args (`gradle.properties`)

To avoid OOM on the 8 GB vultr, lock Gradle memory:

```properties
org.gradle.jvmargs=-Xmx2g -Xms512m -XX:MaxMetaspaceSize=512m
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.daemon=true
kotlin.incremental=true
```

(This will be written when we bootstrap `gradle.properties`.)

## Common errors

| Error | Fix |
|---|---|
| `Unsupported class file major version 61` | You're on JDK 11. Reinstall JDK 17. |
| `SDK location not found` | Set `sdk.dir` in `local.properties` |
| `Failed to install the following Android SDK packages...` | Run `yes \| sdkmanager --licenses` |
| `OutOf memory` | Lower `org.gradle.jvmargs` is wrong direction; raise it (e.g. `-Xmx3g`) |
| `adb: device offline` | `adb -s <ip> reconnect`, or re-toggle network debug on TV |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature mismatch (debug vs release); `adb uninstall tv.telegram` first |
| `INSTALL_FAILED_INSUFFICIENT_STORAGE` | TV full; `adb shell pm clear tv.telegram` or free space |
