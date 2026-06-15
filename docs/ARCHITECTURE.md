# Tvgram — Architecture

> Last updated: 2026-06-15

## High-level

Tvgram is a **fat Android TV client**: TDLib (MTProto) runs **inside the
APK** as a JNI library, and connects **directly** to Telegram DC. There is
**no vultr-side relay / proxy** in the runtime path. The vultr host is
purely a **build & dispatch** environment.

```
┌──────────────────────────────────────────────────────────────┐
│  Sony Bravia 国行 (Android TV)                                │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐   │
│  │  APK process                                            │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌────────────┐  │   │
│  │  │ Compose TV   │  │ TDLib JNI    │  │ Media3     │  │   │
│  │  │ UI           │←→│ (.so, ABI)   │  │ ExoPlayer  │  │   │
│  │  │              │  │ 直连 TG DC   │  │            │  │   │
│  │  └──────────────┘  └──────┬───────┘  └─────▲──────┘  │   │
│  │         │                  │ media dl       │        │   │
│  │         │                  ▼                │        │   │
│  │         │           ┌──────────────┐        │        │   │
│  │         │           │ /data/data/  │        │        │   │
│  │         │           │  /cache/media│        │        │   │
│  │         │           └──────┬───────┘        │        │   │
│  │         └──────────────────┴─────────────────┘        │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                                │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ MTProto (TCP/443) 直连
                              │ (家庭旁路由代理,无 GFW 限制)
                              ▼
                  ┌──────────────────────────┐
                  │  Telegram DC             │
                  │  149.154.175.50:443 ...  │
                  └──────────────────────────┘
```

```
┌──────────────────────────────────────────────────────────────┐
│  vultr (Ubuntu 22.04, 4 vCPU / 8GB / 150GB)                  │
│                                                                │
│  角色:开发 + 构建 + 分发                                        │
│  不参与运行时                                                    │
│                                                                │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ JDK 17     │  │ Android SDK  │  │ scripts/             │  │
│  │ Temurin    │  │ cmdline-tools│  │  install-sdk.sh      │  │
│  │            │  │ platform-34  │  │  dev-install.sh      │  │
│  │            │  │ build-tools  │  │  release-apks.sh     │  │
│  └────────────┘  └──────────────┘  │  generate-keystore.sh│  │
│                                     └──────────────────────┘  │
│  ┌────────────┐                                                 │
│  │ Gradle 8.5 │  ./gradlew assembleDebug / test / lint          │
│  └────────────┘                                                 │
│                                                                │
│  ┌────────────┐                                                 │
│  │ adb        │  adb connect <TV IP>:5555 → installDebug        │
│  └────────────┘                                                 │
│                                                                │
└──────────────────────────────────────────────────────────────┘
                              │
                              │ 编译产物分发
                              ▼
                  ┌──────────────────────────┐
                  │  GitHub Releases         │
                  │  bling0390/tvgram        │
                  │  + 国内网盘备份(可选)     │
                  └──────────────────────────┘
```

## Component map

### 1. TDLib layer (`tv/telegram/td/`)

| Class | Responsibility |
|---|---|
| `TdClient.kt` | Wraps `org.drinkless.tdlib.TdApi` client; thread-safe `send` / `receive` |
| `TdAuth.kt` | Login state machine: `WaitTdlibParameters` → `WaitEncryptionKey` → `WaitQrCode` → `Ready` |
| `TdChatRepository.kt` | Lists channels / groups / private chats; filters by type |
| `TdMediaRepository.kt` | Loads media-only messages from a chat; triggers file download to local cache |

TDLib runs on its own thread (TDLib Java binding requires this), and pushes
updates via `Client.ResultHandler` callbacks. We marshal all UI-facing
state into a `StateFlow<UiState>` exposed from these classes.

### 2. UI layer (`tv/telegram/ui/`)

| Screen | File | Purpose |
|---|---|---|
| `QrLoginScreen` | `login/` | Renders QR code from TDLib `getLoginUrl`; subscribes to `TdAuth` for state |
| `HomeScreen` | `home/` | SmartTube-style: top tabs + hero + horizontal card rows |
| `ChatScreen` | `chat/` | Media-only message browser; D-pad left/right pagination |
| `PlayerScreen` | `player/` | Full-screen ExoPlayer for video |

Navigation: single `NavHost` in `ui/nav/`. TV-aware — uses
`androidx.tv:tv-foundation` `Carousel`, `ImmersiveList`, `TabRow`.

### 3. Player layer (`tv/telegram/player/`)

- `ExoPlayerHolder` — lifecycle-aware wrapper; uses
  `androidx.media3:media3-exoplayer` 1.4+
- Pulls from `content://` URI backed by the in-app cache directory
- Sony Bravia hardware-decodes H.264 + HEVC; AV1 is software-decoded
  (acceptable for MVP, may revisit later)

## Data flow

```
TdLib (event thread)
   │
   │ onUpdateAuthorizationState / onUpdateNewMessage / ...
   ▼
TdClient.updateHandler
   │
   │ update StateFlow<TdState>
   ▼
Repository (ViewModel scope)
   │
   │ expose StateFlow<UiState>
   ▼
Compose TV UI (collectAsStateWithLifecycle)
```

## Key constraints (from DECISIONS.md)

1. **TDLib runs in-app**, no vultr relay
2. **QR-code login only** (no phone/SMS)
3. **Single-user**
4. **Multi-ABI** APK: `arm64-v8a` + `armeabi-v7a` + `x86_64`
5. **UI = SmartTube pattern, Compose for TV** (not the old Leanback fragment library)
6. **Compose for TV, not `com.google.android.tv:leanback`** (the latter requires GMS, unavailable on 国行 Bravia)
7. **JDK 17 (Temurin)**, AGP 8.3+, Kotlin 2.0+
8. **Direct-to-DC, no vultr-side proxy**; GFW mitigated by the user's home side-router
9. **No Play Store** distribution; APK + GitHub Releases only
10. **No KVM emulator** on vultr; rely on real device (Sony Bravia) for L3/L4 verification

## Performance budget (Sony Bravia 国行 2GB RAM / 8GB storage)

| Component | Memory |
|---|---|
| TDLib daemon | 50-150 MB |
| ExoPlayer | 30-60 MB |
| Compose TV renderer | 50-100 MB |
| Media cache (LRU) | ≤ 2 GB on disk |
| **Total steady-state** | **< 500 MB RAM** |

## What's NOT in the architecture

- ❌ Text-message rendering (hidden)
- ❌ Voice/audio message playback (muted)
- ❌ Sending messages
- ❌ Search
- ❌ Multi-account
- ❌ Local storage of media on TV (cache only, LRU)
- ❌ Push notifications
- ❌ Stickers / GIFs / polls / etc.
