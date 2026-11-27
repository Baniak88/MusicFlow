# Changelog

All notable changes are documented here. Format: [Keep a Changelog](https://keepachangelog.com),
versioning: [SemVer](https://semver.org).

## [1.0.0-beta.19] - 2026-11-27

### Fixed
- **Radio "RDS" in the app.** The song playing now (title, artist and cover from RMF ON and
  ESKA, or the title sent in the stream) now also shows in the player and the mini player.
  Before, it appeared only in the notification and Android Auto.
- After a station announced a new song, the next station change could be ignored: it was not
  added to history and got no now-playing updates. A test covers the metadata update.

### Removed
- **Open FM.** Its streams play only through Wirtualna Polska's own player: it encrypts the
  stream address to defeat ad blockers, and the server refuses any other request (HTTP 403).
  MusicFlow does not circumvent that protection, so the Open FM channels are gone instead of
  failing. RMF ON and ESKA stay, because their streams are public.

## [1.0.0-beta.18] - 2026-11-27

### Added
- **Android Auto: the whole app**, in four tabs:
  - Home: continue listening, recently played, most played, trending;
  - Library: liked songs, playlists, saved albums, favourite artists, on this device;
  - Radio: Open FM, RMF ON, ESKA, popular here, worldwide, genres (stations as a grid);
  - Explore: genres, moods, decades and charts.
- Choosing a song or a station in Android Auto plays the rest of its list after it.
- Voice/text search in Android Auto also finds radio stations ("RMF FM").
- **Loudness normalisation for radio.** Stations carry no loudness data, so MusicFlow now
  measures each stream while it plays and steers it to the same target as songs:
  - measurement follows EBU R128 / BS.1770 (K-weighting, gated 400 ms blocks);
  - the gain changes slowly (−15…+9 dB), and boosts go through a soft limiter;
  - it follows the *Volume normalisation* setting (Off / Quiet / Normal / Loud).

- **"RDS" for radio: the song playing now.**
  - Open FM, RMF ON and ESKA publish what they play. MusicFlow reads it every 20 s while a
    station plays and shows title, artist and, for RMF ON and ESKA, the album cover.
  - Shown in the player, the notification, the lock screen and Android Auto.
  - Other stations keep using the title sent in the stream (ICY).

### Fixed
- Open FM: every stream request (also HLS chunk lists and segments) now carries the headers of
  Open FM's own web player (browser User-Agent, Origin and Referer). Not verifiable from here:
  the CDN refuses servers outside Poland.

### Changed
- *Add to playlist* is a glass sheet with playlist covers instead of a plain dialog.
- Home, *Continue listening*:
  - the rows use the full width, so titles are no longer cut;
  - the per-row play button is gone (tap the row);
  - only the playing track shows bars.
- Home, *For you* mixes: a dark scrim at the bottom instead of a violet overlay, so the cover
  stays visible and the title is readable.
- Mini player: the output-device icon (e.g. Bluetooth) moved into the subtitle line.

## [1.0.0-beta.17] - 2026-11-27

### Added
- **Open FM, RMF ON and ESKA** channels, read from the broadcasters' own websites
  (unofficial):
  - about 200 Open FM channels, 154 RMF ON stations and 15 ESKA channels;
  - shown first in Radio search;
  - Android: Explore tiles *Open FM channels*, *RMF ON stations* and *Radio ESKA channels*;
  - iPhone code (not released): Open FM / RMF ON / ESKA tiles on Home;
  - RMF ON plays MP3 128 kbps, with AAC as a fallback.

### Known limitation
- Open FM refused its streams (HTTP 403) from servers outside Poland, so playback could not be
  verified here.

## [iOS 0.1.0-beta.1] - 2026-11-26

### Added
- **MusicFlow for iPhone** (stage 1), a native SwiftUI app in `ios/`, iOS 17+:
  - YouTube Music search and playback (AAC 128 kbps, or 48 kbps in data-saver mode);
  - Radio Browser stations: search by name, city and genre, popular stations and live titles;
  - MusicBrainz catalogue, played only through a reliable YouTube Music match;
  - background playback with lock screen, Control Center, headphone and car controls;
  - a queue (play next, add, shuffle, repeat, reorder), likes, playlists and history, all
    stored on the device;
  - the glass look of the Android app, in Polish and English.
- iOS workflow on a macOS runner:
  - runs unit tests with recorded responses, live provider tests and a UI test with
    screenshots;
  - builds an unsigned IPA for sideloading (see `docs/IPHONE.md`).

## [1.0.0-beta.16] - 2026-11-26

### Changed
- Player: the *Lyrics* tab at the bottom is gone (lyrics are in the ⋯ menu); the bottom row keeps
  shuffle and repeat.
- Explore and Library in the glass style: frosted category tiles with a coloured glow and icon;
  Library shortcuts as glass tiles with gradient icons; glass header buttons.
- Consistent accent everywhere: chips are a violet→magenta gradient when selected and glass
  otherwise; the dark theme always uses the violet glass palette (wallpaper colours now apply to the
  light theme only); glass search field; violet tonal buttons; a more opaque bottom dock.

## [1.0.0-beta.15] - 2026-11-26

### Changed
- **New "glass" look** (tester's reference design): dark backdrop with soft ember / magenta / violet /
  ocean glows; round glass buttons; Home with menu and search buttons and rows with gradient play
  buttons; mini player with round artwork, like and gradient play button; full player with round
  artwork in a glass ring, audio-style (decorative) progress bar, gradient play button, previous/next
  glass buttons, add-to-playlist and like next to the artist, and a curved **Lyrics** tab that opens
  a full-screen lyrics panel over the blurred artwork (current line highlighted, tap a line to seek).
- **New app icon** matching the design (glass ring with a violet→magenta play button over the glows);
  themed (monochrome) icon included.
- **Faster start of playback**: the first songs of search results, opened albums/playlists and Home
  quick picks are resolved in the background (max. 3, never radio or local files), so tapping them
  starts almost instantly; playback now starts after 1 s of buffer (was 1.5 s).

### Fixed
- **"Adding to a playlist / to the queue doesn't work"**: it worked, but the confirmation was hidden
  under the open player, and *Play next* / *Add to queue* showed none. Confirmations now appear above
  everything, also for the queue. Covered by UI tests.

## [1.0.0-beta.14] - 2026-11-26

### Fixed
- **"Unexpected error (… was cancelled)" while typing a new search**: a replaced search reported its
  cancellation as an error (and a late result could overwrite newer ones). Cancellations are no
  longer errors anywhere in the UI, and only the current search may update the screen.
- **HLS radio stations stopping with "Source error"**: follow-up requests (playlist refreshes,
  segments) went through the disk cache, so live playlists went stale; for HTTP stations the HTTPS
  guard also blocked them. Such requests now bypass the cache, and may use HTTP only while
  *Radio: allow unencrypted stations* is on. SoundCloud segments are still cached.
- **DRM-only SoundCloud tracks**: a clear message ("DRM-protected … skipping") instead of
  "content unavailable"; the queue continues with the next track.

### Changed
- Settings is now one tap away from Library and Explore too (gear icon), not only from Home.

## [1.0.0-beta.13] - 2026-11-25

### Fixed
- **Radio search**: words typed together are split ("danceradio" → "dance radio", "openfm" →
  "open fm"), an exact station name wins, and a country code in the query ("danceradio cz") prefers
  that country. Verified live: Sunshine Live, Dance rádio CZ and Radio FAMA Kielce are found first
  and play.
- **Radio sound quality**: the directory lists many stations several times (e.g. AAC+ 32 kbps next
  to MP3 192/320 kbps); only the best-sounding version of each station is shown, and the list shows
  its format and bitrate as reported by the directory.

### Known limitation
- Open FM: the directory only contains third-party relays (reconv.pl) that refuse playback
  (HTTP 403); Open FM's own streams are not published in Radio Browser.

## [1.0.0-beta.12] - 2026-11-25

### Fixed
- **Radio search missed many stations** (e.g. "Radio Fama Kielce"): it matched only the whole phrase
  in station names, and only HTTPS stations were offered. Now every distinctive word is searched
  in names and regions (plus genre tags) and stations matching the most words come first; local
  stations without HTTPS play when *Radio: allow unencrypted stations* is on (default, Settings →
  Providers). HTTPS stays enforced in code (`CleartextGuard`) for everything else.
- Release workflow compared the signature with an older beta; it now uses the newest release.

## [1.0.0-beta.11] - 2026-11-25

### Added
- **Internet radio (Radio Browser)**: ~50 000 stations — search by name or genre, Explore → Radio
  (popular in your country, worldwide, pop/rock/dance/jazz/classical/news), live playback (HTTPS
  only, never cached), the current song from the station shown as now playing.
- **MusicBrainz**: search recordings, albums and artists; albums with tracklists and Cover Art
  Archive covers; artist discographies and facts; *About · MusicBrainz* on YouTube/SoundCloud artist
  pages (exact matches only). MusicBrainz tracks play through YouTube Music only when title, artist
  and duration agree — otherwise they are reported as unavailable, never guessed.
- Search source chips now: YouTube Music · SoundCloud · Radio Browser · MusicBrainz; switches for
  each in Settings → Providers.

### Fixed
- **SoundCloud tracks that "are unavailable"**: monetised label tracks are served by SoundCloud
  only as DRM-encrypted HLS (`cbc/ctr-encrypted-hls`), which MusicFlow does not decrypt. They now
  play the identical recording from YouTube Music when title, artist and duration agree; otherwise
  the message says the track is DRM-protected. Queue items are re-typed (HLS ↔ file) to match the
  stream actually returned.
- History: tapping a song plays it and then the following songs of the list.

## [1.0.0-beta.10] - 2026-11-25

### Fixed
- **SoundCloud search failing with "unexpected error"**: when SoundCloud found nothing in one
  category, NewPipe's *NothingFoundException* surfaced as an error and, for the tracks category,
  failed the whole *All* search. No results are now an empty list (also for YouTube), and *All*
  shows whatever categories succeeded.
- Unexpected errors now show their technical reason under the message, so a screenshot is
  enough to diagnose them.

## [1.0.0-beta.9] - 2026-11-25

### Added
- **SoundCloud** (experimental, NewPipe Extractor): search tracks / artists / sets, open sets,
  albums and artist pages, related tracks, pasted links, playback (HLS: AAC 160 / MP3 128 /
  Opus 64 kbps by quality setting). Source chips in Search; switch in Settings → Providers.
  SoundCloud Go+ previews are reported as restricted, not played as full tracks.
- **Background updates**: every ~6 h MusicFlow checks for a newer release and (setting
  *Install updates automatically*, on by default) downloads, verifies (SHA-256, package,
  version, same signing key) and installs it — silently on Android 12+ after allowing installs
  once. New NewPipe Extractor versions (bumped automatically in CI) reach the phone this way.
  The Home banner now installs in the app; Settings shows the NewPipe Extractor version.

## [1.0.0-beta.8] - 2026-11-25

### Changed
- No code changes: first beta signed with the same key as its predecessor, to confirm
  in-place updates (beta.7 → beta.8) on devices.

## [1.0.0-beta.7] - 2026-11-25

### Fixed
- **Updates install over the previous version again.** The CI test signing key was never
  persisted, so every beta up to beta.6 was signed with a different key and Android refused to
  update. Betas now use a dedicated, cached test key; the release workflow verifies the
  certificate against the previous beta and refuses to publish on a mismatch. One last
  uninstall is needed when moving from beta.6 or older to beta.7.
- **Dynamic range compression (and the equaliser / quiet-track boost) silently stopping** until
  toggled off and on: the phone's own sound effects (invited to the session for system EQ apps)
  could take control of MusicFlow's effects or disable them. Effects now request above-normal
  priority, listen for loss of control, and are re-checked and restored on every play start and
  track change (rate-limited, so two apps never fight in a loop).

## [1.0.0-beta.6] - 2026-11-25

### Fixed
- **Crossfade now behaves like Spotify's**: fade curves follow the media positions instead of a
  timer that started only once the next track played. Previously a next track that needed 1–2 s
  to buffer (typical for YouTube) made the outgoing track stop abruptly mid-fade; now it always
  fades smoothly to silence at its own end while the next one fades in from its start.
- A short buffering stall of the incoming track no longer aborts the crossfade (only a user pause
  does), and the hand-over between the two copies of the outgoing track is aligned to within
  ~40 ms (start-up latency compensated), so there is no audible stutter at the start of the fade.

## [1.0.0-beta.5] - 2026-11-25

### Fixed
- **Crossfade gaps**: the outgoing track's tail is now pre-buffered by the fader ~6 s ahead and
  the switch happens only once the fader is audibly playing; if it is not ready in time the
  transition plays gaplessly instead of with a hole. Verified by simulations that check there is
  sound at every 50 ms step.
- **Sound quality**: defaults are now Best (Wi-Fi) / High (mobile) — Opus ≈160 kbps instead of
  AAC 128 kbps on mobile; normalisation on Quiet/Normal only attenuates loud tracks (no limiter in
  the signal path); boosting quiet tracks is limited to the *Loud* level.
- Text colours on the new transparent, gradient-backed screens.

### Added
- **Automatic NewPipe Extractor updates** (`.github/workflows/extractor-update.yml`): every 6 h CI
  checks the current extractor against live YouTube, picks up new NewPipe releases (or, if YouTube
  is broken, the latest `dev` commit), runs lint + unit + live tests, commits the bump and publishes
  a new beta. Failures open/update a GitHub issue instead of shipping a broken build.
- **In-app update notice**: MusicFlow checks the GitHub release list (at most every 6 h, can be
  turned off; *Check now* in Settings) and shows a Home banner with a download link. Nothing is
  downloaded or installed automatically; the library code is never replaced at runtime.

### Changed
- New look: plum gradient theme, pill chips, "Continue listening" rows with now-playing bars,
  one bottom dock with mini player + navigation, redesigned player ("Playing on <device>",
  share, thin progress, "Playing from <source>"), new app icon.
- Real audio output detection (phone, wired, USB, Bluetooth device name, car head units).

## [1.0.0-beta.4] - 2026-11-25

### Added
- **Loudness normalisation** (Settings → Playback, default *Normal*): per-track gain to a
  Spotify-like target (Quiet −19 / Normal −14 / Loud −11 LUFS) using YouTube's measured track
  loudness (no extra requests) or ReplayGain tags in local files. Cuts via volume, boosts (max
  +6 dB) via LoudnessEnhancer with limiter. Shown in the player's quality dialog.
- Optimised test build type `beta` (R8, ~9.5 MB instead of ~84 MB).
- Test builds are signed with a stable key, so new betas install over old ones.

### Fixed
- Navigation: double taps no longer open a screen twice; double "back" no longer pops two
  screens; fast transitions (were a slow 0.7 s cross-fade); tapping a liked song in Library plays
  it; Search no longer pops up the keyboard every time.
- Artist pages: albums/singles via album search when the channel exposes no releases tab;
  better matching of artist credits.
- "Volume levelling" renamed "Dynamic compression" to distinguish it from normalisation.

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
