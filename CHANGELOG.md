# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: [SemVer](https://semver.org).

## [1.0.0-beta.3] - 2026-11-25

### Fixed (navigation, reported by a tester)
- Tapping the tab you are already in now returns to that tab's main screen (it did nothing
  when a sub-screen such as an artist or Settings was open).
- The current tab stays highlighted on sub-screens (artist, album, category, settings…).
- The search icon on Home switches to the Search tab instead of stacking Search on Home.
- Status-bar icons no longer overlap scrolling content (background behind the status bar).

## [1.0.0-beta.2] - 2026-11-25

### Added
- **Crossfade** (Settings → Playback → Crossfade, 0–12 s): Spotify-style overlap of consecutive
  songs with an equal-power curve; notification/lock screen/Android Auto follow the new song.
- **Audio quality in the player** (tap the format line or ⋮ → Audio quality). Applies immediately
  to the current song, keeping the position; shows what is actually playing.
- Pasting a YouTube / YouTube Music link into search opens that track or playlist.

### Fixed
- Many songs were missing from search: only YouTube Music's official catalogue was searched.
  Songs now also include YouTube uploads (merged, de-duplicated, exact matches first).
- Changing audio quality in Settings only affected the next song.

## [1.0.0-beta.1] - 2026-11-25
First test build.

## [1.0.0] - 2026-11-24 (internal)

### Added
- Multi-module Kotlin/Compose/Material 3 app (15 modules) with Hilt, Room, DataStore, Coil, OkHttp.
- Media3 playback service: background/screen-off playback, notification & lock-screen controls,
  headset/Bluetooth buttons, Android Auto browse/search, resumption, queue editing, shuffle,
  repeat, autoplay, sleep timer, speed, equaliser, volume levelling, skip silence.
- Streaming reliability layer: lazy resolution, expiry-aware cache, URL renewal on 403/410,
  exponential backoff, connectivity-driven recovery, chunked YouTube transport, request
  de-duplication, next-track pre-resolution, disk cache, circuit breaker.
- Providers: on-device music, direct HTTPS audio, YouTube Music (experimental, NewPipe Extractor
  v0.27.1), LRCLIB lyrics.
- Home, Explore (18 categories), Search (suggestions, history, filters, pagination), Library
  (likes, albums, artists, playlists with drag-and-drop, history, most played, local music),
  album/playlist/artist pages, full-screen player with gestures, synced lyrics, queue.
- Playlist import (public links, M3U), M3U export, JSON backup/restore, duplicate detection.
- Local recommendation engine and Discovery mode.
- English and Polish translations; per-app language selection.
- Settings, provider status, diagnostics screen with sanitised export, licences screen.
- GitHub Actions: CI, tagged releases, emulator device tests.

### Fixed during device verification
- MediaController accessed off the main thread (crash on start).
- Local provider was chosen as the primary catalogue over the online provider.
- MediaStore rows with `is_music = NULL` were hidden.
- Home showed nothing (instead of an error) when every online section failed.
