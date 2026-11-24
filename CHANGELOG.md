# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: [SemVer](https://semver.org).

## [1.0.0] - 2026-11-24

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
