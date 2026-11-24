# Architecture

## Decision: independent app on NewPipe Extractor (not a fork)

Reference projects were inspected at their latest commits (November 2026):

| Project | Licence | State | Notes |
|---|---|---|---|
| OuterTune | GPL-3.0 | **Discontinued** (README: "no longer in active development"); its YouTube layer was removed from the tree | Good Media3/Compose patterns; proven AGP 9.2 / Kotlin 2.4 / Hilt 2.60 dependency set (reused as our version baseline) |
| InnerTune (z-huang) | GPL-3.0 | Only translation commits since mid-2025 | Own Ktor-based InnerTube client (WEB_REMIX browse/search); no signature/n-param deciphering, so stream access breaks whenever YouTube rotates clients |
| NewPipe Extractor | GPL-3.0-or-later | **Actively maintained** (v0.27.1, 2026-11-10; commits this month) | Handles client selection (currently visionOS), signature & throttling deciphering, PoToken hook, YouTube Music search |

Forking a discontinued app would inherit its debt; building on the maintained extractor keeps the
fragile part (stream access) in the hands of a large upstream community. MusicFlow therefore is an
**independent application** whose only YouTube dependency is NewPipe Extractor, isolated behind a
provider interface. Consequence: MusicFlow is licensed **GPL-3.0-or-later**.

Findings from live testing (documented in PROVIDERS.md): NewPipe's YouTube Music *Songs/Albums*
search filters return no results from some networks (YouTube withholds its licensed catalogue,
observed from data-centre IPs), so the provider falls back to official music videos instead of
showing nothing; visionOS stream URLs require POST requests in bounded byte ranges, so MusicFlow
ships a dedicated chunked data source.

## Modules

```
app                    Application, MainActivity, navigation shell, DI wiring, manifest
core:model             Pure Kotlin domain models (Track, MediaId, ResolvedStream, ProviderError…)
core:common            Retry/backoff, text matching, sanitiser, diagnostics log, M3U codec
core:network           Shared OkHttp client, ConnectivityMonitor
core:database          Room schema, DAOs (library, playlists, history, queue)
core:designsystem      Theme, typography, components, navigation contracts (CompositionLocals)
data:providers         Provider interfaces, ProviderRegistry, YouTube Music / local / direct URL / LRCLIB
data:repositories      Settings (DataStore), library, playlists, search, catalogue, home, explore, recommendations
playback               MusicService (MediaLibraryService), stream pipeline, error policy, effects, PlaybackEngine
feature:home|explore|search|library|player|settings   Compose screens + ViewModels
```

Dependency rule: `feature:*` → `data:repositories`, `playback`, `core:designsystem`; features never
depend on each other. Cross-feature navigation goes through `AppNavigator` / `TrackMenu`
CompositionLocals defined in `core:designsystem` and implemented once in `app`.

## Layers

* **Presentation**: Compose screens observe `StateFlow`s from Hilt ViewModels
  (`collectAsStateWithLifecycle`), one-shot events via `SharedFlow`.
* **Domain**: models in `core:model`; recommendation scoring (`RecommendationEngine`) is pure Kotlin.
* **Data**: repositories combine Room (user-owned data), DataStore (settings) and providers.

## Provider system

```
MusicProvider ─┬─ SearchProvider          search, suggestions
               ├─ MetadataProvider        track / album / artist pages
               ├─ StreamResolver          MediaId → ResolvedStream (url, codec, bitrate, expiry, headers)
               ├─ RecommendationProvider  related tracks (radio), trending
               └─ PlaylistProvider        public playlists, URL parsing
LyricsProvider (LRCLIB)                   separate
PlaybackEngine (playback module)          UI-facing playback contract
```

`MediaId` = `"<provider>:<key>"` (`yt:…`, `ytpl:…`, `ytch:…`, `local:…`, `url:…`) routes every
item to its provider. `ProviderRegistry` wraps every call with retry (only transient errors),
health tracking and a circuit breaker (5 consecutive transient failures → 60 s fail-fast); per-item
errors (unavailable, restricted) never degrade a provider. Errors are a sealed `ProviderError`
taxonomy: Network, RateLimited, StreamExpired, Authentication, Unavailable, Restricted(geo/age/paid),
Parsing, NotSupported, Unknown.

The experimental provider (`YouTubeMusicProvider`, `isExperimental = true`) is one class plus
helpers in `data/providers/youtube/`, can be disabled in Settings, and its absence leaves the
local player fully functional.

## Playback pipeline

```
MediaItem(uri = musicflow://media?id=yt:abc)            ← queue never stores expiring URLs
  → ResolvingDataSource ── StreamRepository.resolve()   ← cache by (id, quality), expiry-aware,
  │                                                       in-flight de-duplication, reliable
  │                                                       local-file alternative on permanent errors
  → LocalOrRemoteDataSource
      ├─ content:// / file://  → DefaultDataSource
      └─ https               → CacheDataSource(SimpleCache, LRU, user limit)
                                  └─ RemoteRoutingDataSource
                                       ├─ YouTubeChunkedDataSource (POST, &range=a-b&rn=n, 1 MiB chunks)
                                       └─ OkHttpDataSource
```

`StreamErrorPolicy` (ExoPlayer `LoadErrorHandlingPolicy`): 403/410 → invalidate URL & retry
(max 2, i.e. renewal); 5xx/429/IO → exponential backoff (≤ 8 attempts, longer when offline);
404/permanent provider errors → fail immediately. The service then auto-skips permanently
unplayable tracks (max 5 in a row) and resumes transient failures when `ConnectivityMonitor`
reports the network back.

`MusicService` (`MediaLibraryService`, `foregroundServiceType="mediaPlayback"`): ExoPlayer with
audio focus handling (calls, other apps), becoming-noisy pause, `WAKE_MODE_NETWORK`,
configurable buffer profile, media notification with a Favourite button, Android Auto browse tree
and search, playback resumption from the persisted queue, sleep timer, effects, autoplay
continuation and listening-event recording. The UI talks to it only via `MediaController`
(`MediaControllerEngine`, confined to the main thread).

## Data

Room tables: `tracks` (metadata cache), `favorite_tracks`, `saved_albums`, `favorite_artists`,
`playlists`, `playlist_entries` (ordered, cascade delete), `play_events`, `search_history`,
`queue_items`. Schema JSON is exported to `core/database/schemas`.

## Key technical decisions

* Kotlin 2.4.10, AGP 9.2.1 (classic DSL, `android.builtInKotlin=false`), Gradle 9.6.1, KSP 2.3.4,
  Hilt 2.60.1, Compose BOM 2026.09.00, Media3 1.10.1, Room 2.8.4, compileSdk 37 / targetSdk 36 /
  minSdk 28 — the set OuterTune ships with, extended where needed.
* OkHttp 4.12 (shared by Coil, Media3 and NewPipe downloader) — one connection pool.
* Core-library desugaring (NewPipe uses Java 11+ APIs) on all modules.
* No WebView, no React Native, no ads, no analytics SDKs.
