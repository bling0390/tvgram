# TDLib native libraries

This directory is **gitignored** — the four `libtdjson.so` binaries
(arm64-v8a / armeabi-v7a / x86 / x86_64) are downloaded from
the prebuilt mirror at build time, NOT committed to source.

## Why gitignored

- Combined size: ~108 MB, on the edge of GitHub's 100 MB single-file cap
- APKs containing these .so files are the actual deliverable; they
  ship as GitHub Release assets
- Source of truth = the upstream mirror, not our repo

## Source

- Upstream: <https://github.com/bling0390/telegram-android-tv/tree/main/core/tdlib/src/main/jniLibs>
- License: Boost Software License 1.0 (TDLib) + whatever the mirror applies

## How to (re)download

```bash
mkdir -p app/src/main/jniLibs/{arm64-v8a,armeabi-v7a,x86,x86_64}
BASE="https://raw.githubusercontent.com/bling0390/telegram-android-tv/main/core/tdlib/src/main/jniLibs"
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    curl -L -o "app/src/main/jniLibs/$abi/libtdjson.so" "$BASE/$abi/libtdjson.so"
done
```

Then `./gradlew assembleDebug` and the .so files are picked up
automatically (Android Gradle Plugin merges `jniLibs/<abi>/*.so`
into the APK).
