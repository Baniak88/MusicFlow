# Testing

Status as of 2026-11-24. "Executed" means run by the author in this repository's environment;
CI re-runs all JVM tests on every push.

## 1. Automated JVM / Robolectric tests — executed, all passing

Command: `./gradlew lint test` (exit 0). 93 tests passed, 0 failed; 6 live tests skipped by default.

| Area (requirement) | Tests | Module |
|---|---|---|
| Search: debounce, pagination, cross-page de-duplication, did-you-mean, filters, history, suggestions | `SearchViewModelTest` (4), `RepositoryTest.searchHistory…/searchDedupe…` | feature:search, data:repositories |
| Provider responses: URL/id parsing, artwork upscaling, stream selection per quality, original-track preference, error classification, derivative filter | `YouTubeHelpersTest` (7), `DerivativeFilterTest` | data:providers |
| Provider failure handling: retries, circuit breaker, permanent errors, disabled providers, routing, online-catalogue selection | `ProviderRegistryTest` (8) | data:providers |
| Other providers: LRCLIB (MockWebServer), LRC parser, direct URLs (HTTPS only, honest lossless flag) | `OtherProvidersTest` (3) | data:providers |
| Database operations | `DatabaseTest` (5): favourites, playlists order + cascade, history aggregation, search history | core:database |
| Playlist management | `RepositoryTest`: CRUD, duplicate detection, reorder, remove, public import across pages (order preserved), M3U import/export, backup/restore | data:repositories |
| Queue management & audio playback | `PlaybackPipelineTest`: real ExoPlayer plays a 3-track queue through the full resolve → route → HTTP → decode pipeline; queue move/remove/shuffle/repeat; MediaItem round trip | playback |
| Stream expiration handling | `PlaybackPipelineTest.expiredStreamUrlIsRenewedTransparently` (HTTP 403 → renewal → completes), `StreamRepositoryTest` (expiry margin, invalidation, de-duplication) | playback |
| Network recovery | `StreamErrorClassifierTest` (backoff, offline delays, 404/permanent = no retry), `PlaybackPipelineTest.permanentErrorStopsWithoutEndlessRetries`, `RetryTest` | playback, core:common |
| Chunked YouTube transport | `ChunkedDataSourceTest` (POST, `range`/`rn` stitching, 403 surfacing) | playback |
| User settings | `RepositoryTest.settingsPersistAndDefault`, play recording honours history setting | data:repositories |
| Theme switching | `ThemeTest` (6): dark, AMOLED, light, system day/night, WCAG contrast | app |
| Application navigation | `NavigationTest` (2): launches real `MainActivity` with Hilt, all four tabs, Explore category, Settings, back; `PlayerNavigationTest`: mini player → full player → controls → collapse | app |
| Recommendations | `RecommendationEngineTest` (7): artist cap, no back-to-back artist, skip/completion signals, recency, discovery mode, familiar/new balance | data:repositories |
| Privacy | `SanitizerTest` (tokens, cookies, signed URLs, e-mails redacted) | core:common |
| Misc | M3U parser, formatting, text matching, model invariants, sleep timer | core:*, playback |

Lint: `./gradlew lint` passes for all modules (abortOnError).

## 2. Live provider tests — executed manually, passing

`./gradlew :data:providers:testDebugUnitTest -PliveTests=true` against real YouTube Music
(2026-11-24): search songs + page 2, artists, playlists, "All", album/playlist page (14 tracks),
artist page, stream resolution (Opus 160 kbps HIGH, 50 kbps DATA_SAVER, expiry & length parsed),
related tracks. Suggestions and trending could not be reached from the build sandbox (host
blocked by its network policy); album search returned no results there (see PROVIDERS.md).
Not run in CI to avoid flaky builds.

## 3. Device tests — executed on an Android 11 (API 30) emulator, software CPU emulation

Performed with `adb` on a headless x86_64 emulator (no hardware acceleration available):

| Scenario | Result |
|---|---|
| Install & cold start | ✅ launches; Home, Explore, Library, Search, Settings render |
| **Found & fixed**: `MediaController` used off main thread → crash on start | ✅ fixed, re-verified |
| **Found & fixed**: local provider chosen over online catalogue | ✅ fixed, regression test added |
| **Found & fixed**: MediaStore rows with `is_music = NULL` hidden | ✅ fixed |
| Local music listing & playback | ✅ `state=PLAYING`, position advancing |
| **Background + screen off** (Home pressed, device asleep) | ✅ position 86 s → 186 s in 90 s; foreground service, MediaStyle notification, public on lock screen |
| Media keys PLAY/PAUSE/PLAY_PAUSE/NEXT/PREVIOUS (headset/Bluetooth path) | ✅ all work |
| Queue persistence across force-stop | ✅ restored, paused at saved position |
| Offline Home | ✅ clear "offline" error with retry, local library still usable |
| Synthetic `cmd media_session dispatch` (shell tool) | ⚠️ crashes inside Media3 1.10/1.11 (`RemoteUserInfo` with empty package from the shell caller). Real key events are unaffected. Upstream issue; documented. |

Instrumented smoke tests (`app/src/androidTest`: app launch + tabs, MediaSession reachable)
compile and run in CI on API 28 and 34 emulators via `android-device-tests.yml`
(workflow_dispatch / weekly). They were **not** executed locally (no KVM).

## 4. Device tests requiring a physical device — NOT executed

Procedures for a tester (record results in an issue):

| Test | Procedure | Pass criterion |
|---|---|---|
| 60-minute continuous playback | Queue a long online playlist, screen off, 60 min; then export Diagnostics | No stop; ≤ expected auto-renewals (URLs expire ~6 h); no errors |
| Wi-Fi ⇄ mobile switching | Play online; toggle Wi-Fi off/on 3× during playback | Resumes within ~10 s each time; quality setting follows network (Diagnostics shows network events) |
| Bluetooth headphones / car | Pair, play, use device buttons, disconnect | Buttons work; disconnect pauses (if enabled); car shows metadata & browse tree |
| Android Auto | Desktop Head Unit or car: browse Recently played / Favourites / Playlists, voice search | Items play; search returns songs |
| Incoming phone call | Call the device during playback | Pauses for the call, resumes after (audio focus) |
| Returning from background | Use other apps 30 min, return | UI reflects current track & position; no restart |
| Memory | `adb shell dumpsys meminfo app.musicflow` after 30 min browsing + playback | PSS stable (no monotonic growth), no leaks in LeakCanary run |
| Battery | 1 h screen-off playback, `dumpsys batterystats` | No wakelocks beyond playback; no excessive network wakeups |
| Android 9 & 15/16 | Repeat smoke checks on API 28 and newest device | Same behaviour |
