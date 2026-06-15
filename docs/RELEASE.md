# Tvgram — Release

> Signing, packaging, and distributing APKs (no Play Store).

## Release signing

Debug builds use `~/.android/debug.keystore` (auto-generated, OK for dev).
**Release builds need a real keystore** — anyone with this keystore can
publish updates in your name, so guard it.

### One-time: generate a release keystore

```bash
bash scripts/generate-keystore.sh
```

You'll be prompted for two passwords. **Write them down somewhere safe**
(1Password / Bitwarden / your encrypted notes — your call). If you lose
the keystore, you can never sign an update with the same identity.

The keystore is created at `keystore/tvgram-release.jks` (gitignored).
Its passwords go into `keystore.properties` (also gitignored).

### `keystore.properties` template

```properties
storeFile=keystore/tvgram-release.jks
storePassword=...   # the one you set during generate-keystore.sh
keyAlias=tvgram
keyPassword=...     # the one you set during generate-keystore.sh
```

### Wire it into `app/build.gradle.kts`

(MVP scaffolding step — not done yet.) The `signingConfigs.release`
block reads from `keystore.properties`:

```kotlin
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String?
            keyPassword = keystoreProperties["keyPassword"] as String?
            storeFile = keystoreProperties["storeFile"]?.let { rootProject.file(it as String) }
            storePassword = keystoreProperties["storePassword"] as String?
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

## Multi-ABI packaging

`app/build.gradle.kts` config (to be added when we bootstrap):

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}
```

This produces 4 APKs per build:
- `app-arm64-v8a-*.apk`    — modern TVs, smallest
- `app-armeabi-v7a-*.apk`  — pre-2020 boxes
- `app-x86_64-*.apk`       — 小米盒子、当贝盒子、Shield 等
- `app-universal-*.apk`    — works on all, biggest

## Build + sign + bundle

```bash
bash scripts/release-apks.sh
```

This:
1. `./gradlew clean assembleRelease`
2. Copies signed APKs from `app/build/outputs/apk/release/` to `dist/`
3. Shows file sizes

## Distribution

### Primary: GitHub Releases

```bash
# Tag and push
git tag v0.1.0 -m "MVP: QR login + image/video browsing"
git push --tags

# Create release with all 4 APKs
gh release create v0.1.0 \
    dist/app-arm64-v8a-release.apk \
    dist/app-armeabi-v7a-release.apk \
    dist/app-x86_64-release.apk \
    dist/app-universal-release.apk \
    --title "Tvgram v0.1.0 (MVP)" \
    --notes "First public MVP. See README for scope."

# Or, if you don't have gh CLI:
#   1. Visit https://github.com/bling0390/tvgram/releases/new
#   2. Choose tag v0.1.0
#   3. Drag dist/*.apk into the assets area
#   4. Publish
```

### Secondary: 国内网盘

```bash
# 115 / 百度 / 阿里云盘 — manual upload is fine for MVP frequency
# 把 dist/*.apk 上传,然后在 release notes / TG 频道贴下载链接
```

## Version strategy

- `versionCode`: monotonic integer, increment per release (1, 2, 3, …)
- `versionName`: semver-like (0.1.0, 0.2.0, 1.0.0)
- Tags: `git tag` uses the same as `versionName` with `v` prefix

## Release checklist (per release)

- [ ] All changes committed & pushed
- [ ] `./gradlew test` passes
- [ ] `./gradlew lint` clean (or warnings acknowledged)
- [ ] `./gradlew assembleDebug` tested on real TV (L3)
- [ ] QR login works on TV (L4)
- [ ] Image + video playback work on TV
- [ ] `bash scripts/release-apks.sh` produces 4 signed APKs
- [ ] Tag created and pushed
- [ ] GitHub Release published with all 4 APKs
- [ ] 国内网盘上传(可选)
- [ ] Announce on TG 频道 / saved messages

## Why no Play Store

- Logo / icon copyright risk (we'd need to avoid "Telegram" wordmark)
- 国行 Bravia has no Play Store anyway
- 5% friction for users, but APK sideload is one click on TV
- Trademark exposure ("Telegram" is a registered mark)
- Decision: stick with direct APK + GitHub Releases
