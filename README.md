# MusicFlow

A fast, private, ad-free music player for Android — built with Kotlin, Jetpack Compose,
Material 3 and Media3 — with a premium dark design, background playback and on-device
recommendations.

MusicFlow plays **music stored on your device**, **authorised direct audio links** (HTTPS, M3U)
and — through an *experimental, optional* provider built on
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) — **public YouTube Music
content**. It contains no advertising, no analytics and no tracking, and needs no account.

> MusicFlow is not affiliated with YouTube, Google, Spotify or any other service.
> Read [PROVIDERS.md](PROVIDERS.md) before enabling the online provider.

## Features

| Area | What you get |
|---|---|
| Playback | Crossfade 0–12 s, audio quality switch in the player (instant), background & screen-off playback (foreground `MediaLibraryService`), lock-screen & notification controls, Bluetooth / wired headset buttons, car head units, **Android Auto** browse + voice search, gapless where the format allows, queue with drag-and-drop, shuffle, repeat one/all, autoplay of related songs, sleep timer (with fade-out or end of track), speed 0.5–2×, built-in equaliser + system equaliser hand-off, volume levelling, skip silence |
| Streaming reliability | Lazy stream resolution, expiry-aware URL cache, transparent renewal of expired URLs (HTTP 403/410), exponential backoff, offline-aware recovery when the network returns, Wi-Fi ⇄ mobile quality switching, next-track pre-resolution, request de-duplication, per-provider circuit breaker, disk cache with configurable limit |
| Discovery | Home (greeting, continue listening, quick picks, personalised mixes, recommended albums, trending, new releases, discover, favourite artists, recently played, on repeat), Explore (18 genre/mood/decade/chart categories), search with suggestions, history, filters, pagination and de-duplication |
| Library | Liked songs, saved albums, favourite artists, playlists (create, rename, delete, reorder, duplicate detection), recently/most played, full listening history, on-device music — all stored locally in Room and available offline |
| Import / export | Public playlist import (order preserved), M3U import/export, JSON backup & restore |
| Recommendations | Local, explainable scoring (frequency, completion vs skips, favourites, recency, release year), artist-repetition cap, familiar/new balance, Discovery mode — no paid AI API |
| Design | Original AMOLED-first dark theme, light theme, pure-black mode, Material You dynamic colour, artwork-tinted player, skeleton loading, edge-to-edge |
| Languages | English and Polish (per-app language picker) |
| Privacy | No ads, no analytics, no account, no cloud backup, HTTPS only, sanitised diagnostics export |

## Screenshots

Taken on an Android 11 emulator during development (offline; local files):
Explore, Library, Home with listening history, full player. See `docs/screenshots/`.

## Install

1. Download `MusicFlow-<version>.apk` from the [Releases](../../releases) page, or the
   `MusicFlow-debug-*` artifact from the latest successful **Android CI** run.
2. On your phone allow *Install unknown apps* for your browser/file manager and open the APK.
3. Android 9 (API 28) or newer is required.

## Testing the app (testers)

Test builds are published as GitHub **pre-releases**. Polish tester guide and checklist:
[docs/TESTY.md](docs/TESTY.md).

## Build

Everything builds in GitHub Actions — no Android Studio needed. See [BUILD.md](BUILD.md).

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew lint test                   # static checks + all JVM/Robolectric tests
```

## Documentation

[ARCHITECTURE.md](ARCHITECTURE.md) · [PROVIDERS.md](PROVIDERS.md) · [PRIVACY.md](PRIVACY.md) ·
[TESTING.md](TESTING.md) · [BUILD.md](BUILD.md) · [CHANGELOG.md](CHANGELOG.md) ·
[AGENTS.md](AGENTS.md) · [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)

## Licence

MusicFlow is free software: GNU General Public License v3.0 or later ([LICENSE](LICENSE)).
It links NewPipe Extractor (GPL-3.0-or-later), which requires the combined work to be GPL.
