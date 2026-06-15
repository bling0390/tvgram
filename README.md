# Tvgram — Telegram for Android TV

A non-official third-party Telegram client for Android TV, focused on browsing
**images and videos** in channels, groups, and private chats. Optimized for
**D-pad navigation** and a **leanback / Google TV** experience.

> Not affiliated with Telegram FZ-LLC. Built on top of the official
> [TDLib](https://github.com/tdlib/td) Java bindings.

## Status

**Pre-MVP / scaffolding.** No code yet — only docs, scripts, and the
agreed-upon architecture. See [`docs/DECISIONS.md`](docs/DECISIONS.md) for the
full decision log and [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for
the architecture.

## Scope (MVP v0.1.0)

- ✅ QR-code login (no phone number / SMS code on TV)
- ✅ Channel / group / private chat list
- ✅ Browse **image and video messages only** within a chat
- ✅ Single-user
- ❌ Text messages (hidden)
- ❌ Sending messages
- ❌ Voice / audio messages (muted)
- ❌ Search, downloads, multi-account

## Target

| Spec | Value |
|---|---|
| App label | `Telegram TV` |
| Package id | `tv.telegram` |
| Min SDK | 21 (Android 5.0) |
| Target SDK | 34 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Distribution | Direct APK (no Play Store) |

## Toolchain

| Tool | Version | Why |
|---|---|---|
| JDK | **17 (Temurin)** | AGP 8.3+ / Kotlin 2.0 hard requirement |
| Android SDK | Platform 34, Build-Tools 34.0.0 | compileSdk target |
| Gradle | 8.5+ | Compatible with AGP 8.3 |
| AGP | 8.3+ | Modern Compose / TV baseline |
| Kotlin | 2.0+ | Compose Compiler bundled |
| UI | Compose for TV (`androidx.tv:tv-foundation` + `tv-material`) | SmartTube-style leanback, modern API |
| TDLib | `org.drinkless:tdlib:1.8.30` | Official Java bindings, JNI direct to TG DC |
| ExoPlayer | `androidx.media3:media3-exoplayer:1.4.1` | TV video playback |
| QR | `com.google.zxing:core:3.5.3` | QR-code rendering for login |

## Repository

```
https://github.com/bling0390/tvgram
```

## Quick start

```bash
# 1. Install toolchain (one-time, downloads ~3GB)
bash scripts/install-sdk.sh

# 2. Add your Telegram API credentials
cp local.properties.example local.properties
# Edit local.properties and fill TG_API_ID / TG_API_HASH
# (Never commit local.properties — it's in .gitignore)

# 3. Build debug APK
./gradlew assembleDebug

# 4. Develop on real device (Sony Bravia)
#    See docs/DEV-WORKFLOW.md for full SOP
bash scripts/dev-install.sh

# 5. Build release APKs (multi-ABI + universal)
bash scripts/release-apks.sh
```

## Documentation

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — system architecture
- [`docs/DEV-WORKFLOW.md`](docs/DEV-WORKFLOW.md) — dev loop (compile → verify → ship)
- [`docs/BUILD.md`](docs/BUILD.md) — toolchain install, build, troubleshoot
- [`docs/RELEASE.md`](docs/RELEASE.md) — signing, packaging, distribution
- [`docs/DECISIONS.md`](docs/DECISIONS.md) — full decision log

## License

TBD.
