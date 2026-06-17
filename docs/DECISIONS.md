# Tvgram — Decision Log

> Full log of architectural and product decisions, with the date and
> rationale for each. This is the "why" companion to `ARCHITECTURE.md`.

---

## D-001 · Project name: `Tvgram` · 2026-06-15

**Decision:** GitHub repo `bling0390/tvgram`, package `tv.telegram`,
app label `Telegram TV`.

**Why:**
- "Tvgram" reads as "TV + Telegram" — short, distinctive
- Repo name lowercase (`tvgram`) for URL/path friendliness
- Package `tv.telegram` is the reverse-domain; using the literal
  word `telegram` is fine because we're not publishing to Play Store
- App label "Telegram TV" is descriptive and honest

---

## D-002 · TDLib runs in the APK, not on vultr · 2026-06-15

**Decision:** No vultr-side relay. The TV app bundles TDLib (JNI `.so`
per ABI) and connects directly to Telegram DC.

**Why:**
- Simpler architecture — no middleman, no home-network forwarding
- TDLib is the official, most-complete TG client library
- Modern Android TV devices have 2-3 GB RAM, can host TDLib daemon
- Sidesteps "tvgram.com" type trust issues — your login, your data

**Trade-off accepted:** APK is +15-20 MB per ABI for the JNI lib.

---

## D-003 · Login is QR-code only · 2026-06-15

**Decision:** No phone number / SMS code on the TV. Login flow:
1. TV screen shows a QR code (`tg://login?token=...` from TDLib)
2. User opens Telegram on phone, scans, confirms
3. TDLib on TV receives `authorizationStateReady`

**Why:**
- No keyboard / no phone-SMS-receiving UX on TV
- QR login is the only sane login path for TV-side apps
- TDLib supports it natively (`getLoginUrl`)

**Trade-off accepted:** User must have TG on phone. (Reasonable
assumption — same as the TV app's audience.)

---

## D-004 · Single-user · 2026-06-15

**Decision:** One Telegram account per APK install. No account
switcher in MVP.

**Why:**
- TV is a single-user device
- Multi-account complicates TDLib session handling
- YAGNI — add later if requested

---

## D-005 · Multi-ABI: arm64-v8a + armeabi-v7a + x86_64 · 2026-06-15

**Decision:** Ship APKs for all three ABIs, plus a universal APK.

**Why:**
- The APK will be **distributed publicly** (no Play Store) — must
  work on whatever TV/box the user has
- `arm64-v8a`: 2020+ TVs, 90%+ of installed base
- `armeabi-v7a`: 2015-2020 boxes, 5-8% of users
- `x86_64`: 国产小米盒子、当贝、英伟达 Shield 2017 前
- `x86` (32-bit): < 1%, skipped

**Trade-off accepted:** APK total ~45 MB. Acceptable.

---

## D-006 · UI: Compose for TV (SmartTube pattern) · 2026-06-15

**Decision:** Use Jetpack Compose for TV (`androidx.tv:tv-foundation`
+ `tv-material`). Take visual inspiration from SmartTube's layout
(top tabs + hero + horizontal card rows). **Do not** use the
SmartTube source code (it's Leanback + Java; we're Kotlin + Compose).

**Why:**
- Compose for TV is the **current Google-recommended path**
  (Leanback fragment lib is in maintenance mode)
- Less code, better D-pad affordances, `Carousel` / `ImmersiveList`
  are purpose-built
- SmartTube's visual language is well-tuned for TV (D-pad friendly,
  big focus rings, leanback aesthetics)

**Avoid:** `com.google.android.tv:leanback` (the old GMS-dependent
one — won't work on 国行 Bravia which has no Google Play Services).

---

## D-007 · JDK 17 (Temurin) · 2026-06-15

**Decision:** Use Eclipse Temurin 17 as the JDK.

**Why:**
- AGP 8.3+ (required for Compose Compiler bundled with Kotlin 2.0)
  hard-requires JDK 17+
- Kotlin 2.0 compiler targets JVM 17 by default
- 17 is the current LTS standard in the Java ecosystem (until 2029)

**Alternatives considered:**
- JDK 11: AGP 8.x won't run
- JDK 21: works (8.5+ AGP), but Robolectric + some K1 plugins have
  edge-case bugs in 2026 — wait until late 2025 for full ecosystem

---

## D-008 · No vultr-side network proxy · 2026-06-15

**Decision:** vultr only does build + dispatch. No reverse proxy,
no WebSocket bridge, no media transcoding on vultr.

**Why:**
- User has a home side-router that handles GFW bypass
- TV is on the same LAN, MTProto goes: TV → side-router → TG DC
- Keeps vultr resource use low (it's a dev host, not a runtime host)
- Removes a class of operational headaches (key management, uptime,
  cert rotation, etc.)

---

## D-009 · No Play Store distribution · 2026-06-15

**Decision:** Distribute via **GitHub Releases** + optionally
**国内网盘** (115 / 百度 / 阿里云盘).

**Why:**
- "Telegram" is a registered trademark; Play Store has stricter
  policies and may reject the app
- 国行 Bravia doesn't have Play Store anyway
- Direct APK sideload is one click on TV
- GitHub Releases gives version history, checksums, release notes
  for free

---

## D-010 · No emulator on vultr · 2026-06-15

**Decision:** vultr has no `/dev/kvm`, no GPU; do not install the
Android emulator. Verify via **L1+L2 on vultr** (compile + JVM-side
Robolectric tests) and **L3+L4 on real TV** (adb install).

**Why:**
- Software-rendered ARM emulator is unusable
- TV app is best validated on a real TV anyway
- Robolectric covers 80% of UI bugs in seconds

**See `DEV-WORKFLOW.md` for the full verification ladder.**

---

## D-011 · MVP scope: image + video browsing only · 2026-06-15

**Decision:** MVP v0.1.0 ships:
- QR login
- Channel / group / private chat list
- Image + video message browser (D-pad pagination)
- ExoPlayer for video

**Out of MVP:**
- Text messages (hidden, but `N text-only messages` indicator at top)
- Voice / audio (muted, hidden)
- Sending messages
- Search
- Downloads to TV storage
- Stickers / GIFs / polls

**Why:** Get something usable on the TV fast, then iterate.

---

## D-012 · AGP 8.3+ / Kotlin 2.0+ / Gradle 8.5+ · 2026-06-15

**Decision:** Use the AGP 8.3 / Kotlin 2.0 / Gradle 8.5 baseline.

**Why:**
- Compose Compiler is now bundled with Kotlin 2.0 (no separate
  `kotlinCompilerExtensionVersion` to set)
- AGP 8.3 supports compileSdk 34 out of the box
- Gradle 8.5 is required for AGP 8.3

---

## D-013 · Compose for TV (NOT androidx.leanback fragment lib) · 2026-06-15

**Decision:** Use `androidx.tv:tv-foundation` and
`androidx.tv:tv-material`. Avoid `androidx.leanback:leanback`.

**Why:**
- Compose is the current Google-recommended TV path
- Leanback is fragment-based Java; we're Compose Kotlin
- The "old GMS leanback" (`com.google.android.tv:leanback`) requires
  GMS — fails on 国行 Bravia. The AndroidX leanback doesn't, but is
  still in maintenance mode.

---

## D-014 · Sony 国行 Bravia: GMS-less, but Leanback/Compose TV work · 2026-06-15

**Decision:** Target Sony 国行 Bravia as the primary test device.
Bypass GMS-only features.

**Why:**
- 国行 Bravia has no Google Play Services
- `androidx.leanback` and `androidx.tv.*` are AOSP — no GMS needed
- adb 无线调试 works fine on 国行
- Anything calling `GoogleApiAvailability.isGooglePlayServicesAvailable()`
  must wrap in try-catch and degrade

---

## D-015 · Verified vultr config: 4 vCPU / 7.7 GB / 150 GB / 6.5 GB swap · 2026-06-15

**Decision:** Confirmed vultr is upgraded to a config that satisfies
Android TV build needs.

**Why:** First config (2 vCPU / 4 GB / 75 GB) would OOM on
`./gradlew assembleDebug` while media-shuttle was running.

**Capacity margin after upgrade:**
- ~1.2 GB reserved for media-shuttle Docker stack
- ~5 GB for Gradle + Kotlin daemon + JDK 17 (peak ~3 GB)
- 115 GB free disk for SDK + Gradle cache + build outputs
- 6.5 GB swap as OOM safety net

---

## D-016 · api_id / api_hash: per-account, not shared · 2026-06-15

**Decision:** Each developer gets their own `api_id` / `api_hash` from
[my.telegram.org/apps](https://my.telegram.org/apps). The values are
kept in `local.properties` (gitignored).

**Why:** Telegram ToS forbids shared credentials. Sharing causes
session kicks and possible API ban.

**Project's `api_id` / `api_hash`** (jason's account, not for reuse):
- `TG_API_ID=28653083`
- `TG_API_HASH=6e4b99e9f8c7473d8ea1274541279255`

> If you fork this project, **get your own** at
> <https://my.telegram.org/apps>. The values above are committed to
> `local.properties.example` as a reference; `local.properties` itself
> is gitignored.

---

## D-017 · Verification ladder: L1/L2 on vultr, L3/L4 on TV · 2026-06-15

**Decision:** No single verification step is sufficient. Use a
4-level ladder:

| L | What | Where |
|---|---|---|
| L1 | Compile + lint | vultr |
| L2 | Unit + Compose UI tests (Robolectric) | vultr |
| L3 | Real-device UI, focus, D-pad, video playback | Sony Bravia |
| L4 | Real TG login + real chat media browsing | Sony Bravia |

**Why:** No emulator on vultr; real TV is the source of truth. L2
catches ~80% of bugs before L3.

**See `DEV-WORKFLOW.md` for the SOP.**

---

## D-018 · Media cache lives in `/data/data/.../cache/media` (LRU) · 2026-06-15

**Decision:** TV stores downloaded media in app-private cache
directory, evicted by LRU policy, **capped at 2 GB**.

**Why:**
- 国行 Bravia has 8-16 GB total; keeping 2 GB for media cache is
  safe
- LRU auto-eviction avoids manual cleanup
- App-private storage; no permission needed for our own files

**Out:** No "Save to TV public storage" in MVP.

---

## D-019 · TDLib `.bin` key file in `filesDir`, not `cacheDir` · 2026-06-15

**Decision:** TDLib's `td.bin` (auth state) is stored in
`context.filesDir`, not `context.cacheDir`.

**Why:**
- `cacheDir` can be wiped by the system under storage pressure
- `filesDir` persists across reboots and is the right place for
  login state
- Trade-off: `td.bin` is wiped on app uninstall. **MVP accepts this**
  (user just re-scans QR to re-login)

---

## D-020 · No `restart: unless-stopped` on media-shuttle · 2026-06-15

**Decision:** Leave `media-shuttle` `docker-compose.yml` untouched.
Containers do not auto-restart on vultr reboot.

**Why:**
- User explicitly asked to keep current state during a vultr plan
  upgrade
- Restarting media-shuttle is a 1-liner (`docker compose up -d`)
- Modifying production code is out of scope for Tvgram decisions

**Reconsidered later?** Yes — if vultr reboots become frequent, add
`restart: unless-stopped` to the 5 services.

---

## D-021 · No KVM, no Android Studio on vultr · 2026-06-15

**Decision:** vultr is a **headless** dev host. Only install:
- JDK 17
- Android cmdline-tools + platform-34 + build-tools-34.0.0
- platform-tools (adb)
- Gradle (via wrapper)

**Do not install:** Android Studio, emulator, system images,
AVD Manager UI.

**Why:** No desktop, no GPU, no KVM. CLI is enough. APK build is
fully scriptable.

---

## D-022 · Gradle JVM args: cap at 2 GB · 2026-06-15

**Decision:** Set `org.gradle.jvmargs=-Xmx2g -Xms512m` in
`gradle.properties`. Don't push higher.

**Why:**
- 8 GB vultr minus media-shuttle (~1.2 GB) leaves ~6.5 GB usable
- 2 GB for Gradle is the sweet spot: fast enough, leaves headroom
- 3 GB is tempting but risks Linux OOM killer if media-shuttle
  peaks
- `org.gradle.daemon=true` keeps the daemon warm; `caching=true`
  reuses outputs across runs

---

## D-023 · Robolectric for Compose UI tests · 2026-06-15

**Decision:** Use Robolectric 4.13+ as the JVM-side Android
framework simulator. Required for testing Compose TV UI, focus
navigation, and D-pad key handling without an emulator.

**Why:**
- 80% of UI bugs are catchable in JVM tests
- D-pad `performKeyPress()` works in Robolectric
- Faster feedback loop (30-60s vs. 5+ min on real device)

**Test framework:**
- `androidx.compose.ui:ui-test-junit4`
- `org.robolectric:robolectric:4.13`

---

## D-024 · Github Releases is the primary APK distribution · 2026-06-15

**Decision:** For every milestone release (v0.1.0, v0.2.0, …),
publish all 4 multi-ABI APKs as GitHub Release assets.

**Why:**
- Free, version-controlled, global CDN
- Auto-generated checksums
- Easy for users to find older versions
- 国内网盘 is a secondary, optional backup

---

## D-025 · Decision log is canonical; README/ARCHITECTURE reference it · 2026-06-15

**Decision:** This file is the source of truth for *why* a choice
was made. Other docs (`ARCHITECTURE.md`, `README.md`) reference it
but don't duplicate the rationale.

**Why:** Decisions evolve. A separate log file means we update one
place and the change is discoverable in `git log docs/DECISIONS.md`.

---

## D-026 · TDLib Maven dependency was retired; use community fork · 2026-06-15

**Decision:** Do **not** depend on `org.drinkless:tdlib` from Maven.
For MVP v0.1.0, use **`ca.denisab85:tdlib:v1.8.8-20221107`**
(the only community-maintained Java binding still on Maven Central).

**Why:**
- The official `org.drinkless:tdlib` artifact (referenced in TDLib's
  own README and most tutorials) was **removed from Maven Central** in
  2023. `gradle` 404s on every version.
- The community fork `ca.denisab85:tdlib` is the de-facto replacement,
  last published 2022-11. TDLib's wire protocol hasn't changed
  meaningfully since, so 1.8.8 still works for our MVP scope
  (auth, chat list, message fetch, file download).
- Building TDLib from source (the "ideal" path) requires NDK, CMake,
  OpenSSL, ~30 min of compile time, and 3 GB+ of additional SDK
  components. Not worth it for v0.1.0.

**Trade-off accepted:** We're on TDLib 1.8.8 (Nov 2022). The MTProto
core is stable, but some 2023+ Telegram features (e.g. stories,
some 2024 chat list filters) won't be available.

**Reconsider later:** When we need a 2024+ TDLib feature, build
TDLib from source via `cmake-android` toolchain and ship the
generated AAR as a local module.

**Implication for the build:**
- The current `gradle/libs.versions.toml` has the TDLib line **commented out**
- `app/build.gradle.kts` has the TDLib `implementation` line **commented out**
- The app builds without TDLib for now; the first "Hello World" APK
  has no real TG integration
- Step 2 of MVP work: switch to `ca.denisab85:tdlib:v1.8.8-20221107`

---

## D-027 · Switched from TDLib Java bindings to JSON interface (libtdjson.so) · 2026-06-15

**Decision:** Drop the `ca.denisab85:tdlib` Maven dependency.
Use TDLib's **JSON interface** (`libtdjson.so`) instead, bundled as
`jniLibs/<abi>/libtdjson.so`.

**Why:**
- The Maven Central TDLib Java jar ships without `libtdjni.so`. Building
  it from source via `docker build` ran 25+ minutes and stalled in the LTO
  link step on the 4 vCPU / 8 GB vultr host.
- The `bling0390/telegram-android-tv` repo at
  `core/tdlib/src/main/jniLibs/<abi>/libtdjson.so` ships pre-built
  binaries for all four ABIs (arm64-v8a, armeabi-v7a, x86, x86_64).
- Using the JSON interface means **no JNI calls, no JNI bridge class**;
  we just spawn the .so as a child process and exchange newline-delimited
  JSON objects over stdin/stdout. This is the same protocol the official
  TDLib team documents and supports.
- One build, four ABIs, zero compile time on the dev host.

**Trade-off accepted:**
- TdApi's 1782 typed classes are no longer directly callable. We work
  with `org.json.JSONObject` and write `@type` strings by hand. This is
  verbose, but the type tags are stable and well-documented.
- Slight process overhead vs. JNI (one child process, two file descriptors).
  Trivial for TV-class hardware.
- We lose TDLib's built-in reference sample (`org.drinkless.tdlib.example.Example`),
  but the JSON protocol is documented in the TDLib README.

**Bypass for `useLegacyPackaging = false`:**
Android 7+ (our minSdk = 21, but devices in 2026 are all 7+) extracts
.so files from the APK to `nativeLibraryDir` and exposes them as
regular files. `libtdjson.so` is ~30 MB but already on-disk after install.

**Where the .so comes from:**
- `https://github.com/bling0390/telegram-android-tv/tree/main/core/tdlib/src/main/jniLibs`
- Public GitHub mirror; reproducible by anyone; same TDLib version
  pinned in that repo.

**Future work (out of scope for v0.1.0):**
- If we ever need a typed Kotlin wrapper, write it ourselves using
  `data class` + a JSON ↔ class mapper. The `org.json` types are
  the only API surface we depend on.

**Implication for the build:**
- The current `gradle/libs.versions.toml` has the TDLib Maven line commented out
- `app/build.gradle.kts` has the TDLib `implementation` line commented out
- All four ABI .so files are bundled under `app/src/main/jniLibs/`
- TdClient.kt was rewritten to use the JSON protocol
- TgTvApp.kt was updated to pass `context` to TdClient (for nativeLibraryDir lookup)

---

## D-014 · Dedicated Player screen + Media3 1.7 + compileSdk 35 · 2026-06-16

**Decision:** v0.7.0-debug introduces a dedicated `PlayerScreen` route
for video playback, separate from the photo viewer. Video cards in the
chat grid jump straight to the player; photo cards still use
`FullScreenMedia` in ChatScreen. PlayerSurface (Compose-native) replaces
the legacy PlayerView. Stack: Media3 1.7.1, AGP 8.4.2, Gradle 8.6,
compileSdk 35. `targetSdk` stays 34 (runtime behavior unchanged).

**Why:**
- v0.6's `PlayerView` is an Android `View`; its D-pad controller behaves
  more like a phone app. For a TV product this is the wrong primitive.
  `PlayerSurface` from `androidx.media3.ui.compose` is Compose-native
  and integrates cleanly with `Modifier.focusable` for D-pad focus.
- Mixing photos and videos in one viewer is convenient code-wise but
  blurs two product intents: "look at this image" vs "play this video".
  Splitting the routes lets the player ship video-only affordances
  (10s skip, playback speed, resume position, auto-advance to next
  video) without affecting the photo path.
- Media3 1.4.x is the last branch compatible with compileSdk 34. We
  wanted `ui-compose` for v0.7.0, so we had to take the compileSdk
  bump as a coupled change.

**Trade-offs accepted:**
- compileSdk 35 emits a "compile against newer SDK" warning under
  AGP 8.4.2 (officially tested up to 34). Doesn't affect runtime; no
  device rejects the APK. Bumping to AGP 8.5+ would silence the
  warning but adds churn. We accept the warning.
- Universal APK grows by ~5 MB (Media3 1.7 + ui-compose). Worth it.
- The chat media grid already pre-downloads thumbnails and full files
  via TdFileRepository; the new PlayerScreen re-uses that path instead
  of building a parallel download pipeline.

**Scope of v0.7.0:**
- ✅ Independent PlayerScreen route (`ui/player/PlayerScreen.kt`)
- ✅ PlayerSurface (Compose) replaces PlayerView
- ✅ D-pad controller: ← / → seek 10s, OK toggles play, Menu cycles
  speed (1.0 → 1.25 → 1.5 → 2.0), Back closes
- ✅ Auto-advance to next video on ENDED (skips photos)
- ✅ Per-fileId resume position (in-memory)
- ✅ Controller overlay auto-hides after 4s of inactivity
- ✅ Sticky top hint: "← Back · ◀ ▶ ±10s · OK Play/Pause · Menu Speed"

**Out of scope (deferred to v0.7.1+):**
- ❌ Persisted resume positions (currently in-memory only)
- ❌ Volume up / down on Up / Down (stubbed, no audio focus wiring)
- ❌ Clickable progress bar to seek
- ❌ Picture-in-picture, casting
- ❌ Subtitle track selection

---

## D-015 · Nav rail layout (B-plan) · 2026-06-17

**Decision:** v0.8.0-debug collapses the old "ChatListScreen + ChatScreen"
two-screen model into a single `HomeScreen` with an 80dp left NavRail
and three sections:

  - 🔍 Search   — full-page global search with the v0.6 D-pad keyboard
  - 💬 Chats    — left sidebar (chat list) + right pane (media grid)
  - ⚙ Settings  — 5-row settings (Account / Language / Theme / About / Sign out)

**Why:**
- SmartTube / YouTube TV pattern is the de-facto TV-side information
  density reference. A vertical nav rail is visually compact and gives
  D-pad users a single "I'm in section X" affordance.
- The old v0.6 ChatListScreen had a `TabRow` filter (Channels/Groups/
  Private) and a 4-col grid; that worked but pushed chat selection into
  "click into a list, click again into a media grid" which is two
  navigation steps for a 3-meter-lean-back use case. The new sidebar
  collapses that to one step (left/right between sidebar and grid).
- Settings is now reachable from a fixed entry point, so the user
  can sign out / change language without an obscure gesture.

**Trade-offs accepted:**
- Three focus zones (NavRail / sidebar / media grid) instead of two.
  D-pad users have to learn ← / → to switch zones; not entirely
  discoverable, but the persistent NavRail highlights the active
  section.
- The v0.6 TabRow (Channels / Groups / Private) is removed. Chat
  list now shows all chat types in a single sort. We accept this
  because most TV users have < 100 chats; if this becomes painful
  we can re-introduce the filter as a header chip inside the sidebar.

**Scope of v0.8.0:**
- ✅ HomeScreen with NavRail (Search / Chats / Settings)
- ✅ SearchScreen (lifted from v0.6 ChatListScreen, full-page)
- ✅ ChatsScreen (sidebar + media grid; videos → PlayerScreen,
   photos → in-place FullScreenMedia)
- ✅ SettingsScreen with 5 rows
- ✅ SettingsRepository: SharedPreferences-backed enum persistence
- ✅ Theme: TvgramTheme now accepts ThemeMode; v0.8.0 ships Dark only
- ✅ Old ChatListScreen.kt + ChatScreen.kt removed

**Out of scope (deferred to v0.8.1+):**
- ❌ Light theme / System theme
- ❌ Non-English UI strings (v0.8.0 UI is hardcoded English; only
   Language.English is wired up)
- ❌ Real TG user info on the Account row (currently shows a
   placeholder "Account ID: ..."; needs TDLib `getMe` plumbing)
- ❌ Real sign-out (v0.8.0 calls TdAuth.cancelQrLogin() but does
   not clear the local TDLib database or restart the process)
- ❌ Sidebar search / type-ahead within the Chats module
