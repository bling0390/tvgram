# Tvgram — Development Workflow

> The dev loop for a TV app **without an emulator**.

## Verification ladder

| Level | What | Where | Speed |
|---|---|---|---|
| **L1 Compile** | Type / syntax / R / manifest | vultr | 10-30s (incremental) |
| **L2 Unit / UI test** | ViewModel, Repository, Compose | vultr | 30-60s |
| **L3 Real device** | UI render, focus, D-pad, real video | Sony Bravia | 1-2 min |
| **L4 End-to-end** | QR login, real chat, real media | Sony Bravia | 5+ min |

L1+L2 run on vultr. L3+L4 require the actual TV.

## One-time TV setup

### Enable developer mode on Sony Bravia 国行

```
Settings → About → Build number  (连续点击 7 次)
  → "You are now a developer!"
Settings → Developer options
  → USB debugging: ON
  → Network debugging: ON   (有些固件叫 "Wireless debugging")
Settings → Network → 查看 IP   (e.g. 192.168.1.50)
```

### Connect from vultr

```bash
adb connect 192.168.1.50:5555
# TV 会弹 "Allow USB debugging?" → Allow
# (可选)勾 "Always allow from this computer"

adb devices
# 192.168.1.50:5555    device
```

> Tip: add a heartbeat to detect drops. If `adb` says `offline`,
> `adb -s 192.168.1.50:5555 reconnect`.

## Daily dev loop (the 80% path)

```bash
# Single command: build + install + launch + tail logs
bash scripts/dev-install.sh
```

This runs:
```bash
./gradlew installDebug \
  && adb shell am start -n tv.telegram/.ui.MainActivity \
  && adb logcat -c \
  && adb logcat -v color Tvgram:V *:S
```

Loop:
1. Edit code
2. `bash scripts/dev-install.sh`
3. Watch TV screen + logcat
4. `Ctrl+C` to stop tail
5. Repeat

## L2 — Unit & Compose UI tests (JVM, no emulator)

Robolectric runs the Android framework in a JVM, so we can test Compose
UI, focus navigation, and D-pad key handling **without** a device.

```kotlin
// Example: TV-style key-press test
@Test
fun chatScreen_dpadRight_advancesToNextMessage() {
    composeTestRule.setContent { ChatScreen(...) }
    composeTestRule.onNodeWithTag("media-card-0").performKeyPress(Key.DirectionRight)
    composeTestRule.onNodeWithTag("media-card-1").assertIsFocused()
}
```

Required deps (will be added when we write `build.gradle.kts`):

```toml
robolectric = "4.13"
androidx-compose-ui-test-junit4 = "1.7.x"
androidx-compose-ui-test-manifest = "1.7.x"
```

Run:
```bash
./gradlew test
```

## Useful adb commands (real-device equivalents of "emulator features")

| Want | Command |
|---|---|
| Live logs | `adb logcat -v color Tvgram:V *:S` |
| Screenshot | `adb exec-out screencap -p > shot.png` |
| Record (≤ 30s on most Androids) | `adb shell screenrecord /sdcard/test.mp4` |
| Simulate D-pad right | `adb shell input keyevent KEYCODE_DPAD_RIGHT` |
| Simulate OK | `adb shell input keyevent KEYCODE_ENTER` |
| Simulate Back | `adb shell input keyevent KEYCODE_BACK` |
| UI element tree | `adb shell uiautomator dump && adb pull /sdcard/window_dump.xml` |
| Which view has focus | `adb shell dumpsys window \| grep -i mCurrentFocus` |
| App CPU / memory | `adb shell top -n 1 -p $(adb shell pidof tv.telegram)` |
| Restart app | `adb shell am force-stop tv.telegram && adb shell am start -n tv.telegram/.ui.MainActivity` |
| Clear app data (re-test login) | `adb shell pm clear tv.telegram` |
| Install from local APK | `adb install -r app/build/outputs/apk/debug/app-universal-debug.apk` |

## APK distribution (L4 milestone releases)

For weekly / milestone testing outside the vultr↔TV adb loop:

```bash
# Build all 4 APKs (3 ABI splits + 1 universal)
bash scripts/release-apks.sh

# Output: dist/
#   app-arm64-v8a-release.apk
#   app-armeabi-v7a-release.apk
#   app-x86_64-release.apk
#   app-universal-release.apk
```

Distribute via:
- **GitHub Releases** (primary, see `docs/RELEASE.md`)
- 国内网盘(secondary, optional)

Download → copy to U-disk / phone → sideload to TV:

```bash
# On TV, enable unknown sources:
# Settings → Security → Unknown sources: ON
# (Some 2024+ Android TV: Settings → Device preferences → Security & restrictions → Unknown sources)

# Then install via:
adb install -r path/to/app-universal-release.apk
# OR on TV: open file manager → click APK → install
```

## CI (optional but recommended)

`.github/workflows/ci.yml` runs L1 + L2 on every push:

- `./gradlew assembleDebug`
- `./gradlew test`
- `./gradlew lint`

Failure blocks merge. Free for public repos. We can wire this up
once the project is bootstrapped.

## What we lose without an emulator (and how to compensate)

| Lost | Mitigation |
|---|---|
| Rapid AVD screenshot iteration | `adb exec-out screencap` works on real TV, ~50ms |
| Performance profiler | `adb shell top` + Android Studio Profiler over adb (Studio not required, but `simpleperf` is) |
| GPU rendering modes | Not critical for TV (Sony Bravia has fixed display pipeline) |
| Configuration changes (rotate) | TVs don't rotate; this is N/A |
| Multi-window | TV doesn't support; N/A |

**Net: nothing critical is lost.** The real device is more honest
about TV-specific behavior than any emulator.
