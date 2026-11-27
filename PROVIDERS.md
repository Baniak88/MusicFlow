# Music providers

| Provider | Id prefix | Status | Capabilities | Network |
|---|---|---|---|---|
| On this device | `local` | Stable | Search (local), streams | none |
| Direct audio URL | `url` | Stable | Streams (HTTPS; MP3/AAC/Opus/FLAC/WAV/HLS) | the URL's host |
| YouTube Music (NewPipe Extractor) | `yt`, `ytpl`, `ytch` | **Experimental**, optional | Search, suggestions, albums/playlists, artists, related/radio, trending, streams | YouTube hosts |
| SoundCloud (NewPipe Extractor) | `sc`, `scpl`, `scu` | **Experimental**, optional | Search (tracks, users, sets), suggestions, sets/albums, artists, related, links, streams (HLS) | SoundCloud hosts |
| Radio Browser | `radio` | Stable, optional | Station search (name and genre tag), popular by country/worldwide/genre, live streams (HTTPS only), ICY now-playing | `*.api.radio-browser.info`, station hosts |
| MusicBrainz (+ Cover Art Archive) | `mbr`, `mbrg`, `mba` | Stable, optional | Search recordings/albums/artists, album tracklists, discographies, artist facts; playback via a verified YouTube Music match | `musicbrainz.org`, `coverartarchive.org`, `archive.org` |
| LRCLIB | — | Stable | Synced and plain lyrics | `lrclib.net` |

## YouTube Music (experimental)

**How it works.** MusicFlow does not use the official YouTube Data API. It uses
[NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) v0.27.1, which talks to the
same public, undocumented InnerTube endpoints as YouTube's own clients and resolves playable
audio streams (it currently uses the visionOS client). MusicFlow never signs in, sends no cookies
and attaches no account.

**Terms of service.** YouTube's Terms of Service restrict access through means other than their
official apps and APIs, and forbid downloading content without permission. Whether using an
independent client is permitted depends on your jurisdiction and circumstances. The provider is
therefore *disabled-able* (Settings → Music providers), clearly labelled experimental, and isolated
from the core app. MusicFlow does **not** claim that official YouTube APIs permit ad removal,
audio-only extraction or background playback; they do not. MusicFlow itself shows no ads.

**Reliability.** Expect breakage whenever YouTube changes clients or response formats; the fix is
usually an extractor update. Behaviour MusicFlow implements:

* Errors are classified (rate limit / CAPTCHA, geo / age / Premium restriction, unavailable,
  parsing) and shown with clear messages; permanent errors are never retried in a loop.
* 5 consecutive transient failures open a 60-second circuit breaker; local playback is unaffected.
* Stream URLs expire (`expire=` parameter, ~6 h). URLs are resolved lazily right before playback,
  cached until 60 s before expiry and renewed automatically on HTTP 403/410.
* visionOS stream URLs need POST requests with `&range=` and `&rn=` parameters; MusicFlow uses
  1 MiB chunks (`YouTubeChunkedDataSource`).
* Only lossy formats exist (Opus ≈ 50–160 kbps, AAC 48–129 kbps). MusicFlow shows the codec and
  bitrate actually reported, never "lossless".

**What "Songs" contains.** YouTube Music's *Songs* search covers only its official catalogue.
Much music exists only as uploads by fans or artists (e.g. Polish disco-polo, regional, older or
independent releases — reported example: "D-Bomb – Powiedz mi co czujesz"). MusicFlow therefore
merges catalogue songs **with** YouTube music-video/upload results, removes duplicates (same id,
or same normalised title + artist) and moves results matching every word of the query to the
top. Pasting a YouTube / YouTube Music link into search opens that track or playlist directly.

**Known limitations (verified with live tests on 2026-11-24).**

* YouTube Music withholds its licensed *Songs* and *Albums* catalogue on some networks
  (observed from cloud/data-centre IPs: the search returns "No results" and offers only Artists,
  Videos, Playlists, Podcasts). Songs then come only from uploads/music videos (logged in
  `Diagnostics`); album search may be empty. On normal consumer
  connections the full catalogue is returned.
* Trending uses `charts.youtube.com` and suggestions use `suggestqueries-clients6.youtube.com`; if
  those are blocked by a network, the sections are hidden / local history suggestions are used.
* Artist "Releases" tabs are not exposed for every channel; artist pages then show top songs only.
* Related tracks come from the YouTube "radio" mix, falling back to related videos; derivative
  uploads ("sped up", "432 Hz", "nightcore"…) are filtered out.
* Age-restricted, members-only and Premium-only tracks cannot be played (no sign-in).
* Public playlist import works for YouTube / YouTube Music playlist links (up to 1000 tracks,
  order preserved). Private playlists cannot be imported.
* No PoToken provider is implemented; if YouTube starts requiring one for the visionOS client,
  stream resolution will fail until the extractor or MusicFlow adds one.

**Loudness.** YouTube's player response contains the measured track loudness
(`trackAbsoluteLoudnessLkfs`, LUFS; per-format `loudnessDb` relative to −14 LUFS). NewPipe does not
expose it, so MusicFlow reads the already-downloaded response reflectively (no extra request);
if a future extractor version renames the field, normalisation for YouTube tracks is silently
skipped and logged — playback is unaffected.

**Keeping the extractor current — up to the phone.** Installed apps follow those releases on
their own: `UpdateJobService` (JobScheduler, every ~6 h when online and the battery is not low)
checks GitHub Releases and, with *Install updates automatically* on, downloads the APK, verifies
its SHA-256 (`SHA256SUMS.txt`), package, version and signing certificate, and installs it via
`PackageInstaller` — silently on Android 12+ once installs are allowed, otherwise Android asks
and the Home banner offers *Install*. Code is never loaded outside the system installer.

**Keeping the extractor current.** `.github/workflows/extractor-update.yml` runs every 6 hours:
it live-tests the pinned NewPipe Extractor, bumps to a newer upstream release when one exists (or
to the latest `dev` commit when the current one is broken and no release fixes it yet), runs
`lint test` plus the live provider tests, commits the bump and dispatches `release.yml` for the
next beta. Inconclusive live results caused by YouTube rate-limiting CI runners
(`RateLimited`, "not a bot") never count as breakage. On failure it opens or comments on the
issue "YouTube provider needs attention (NewPipe Extractor)". Installed apps learn about the new
build through the in-app update check (see PRIVACY.md); code is never downloaded at runtime.

**Alternative providers.** When a track fails permanently, MusicFlow may play an on-device file
instead — only when title, artist (similarity ≥ 0.7) and duration (± 5 s) match reliably.

### On iPhone (`ios/`)

NewPipe Extractor is Java and cannot run on iOS, so the iPhone app sends the same InnerTube requests
itself (`ios/MusicFlow/Providers/YouTubeMusic.swift`):

* **Search:** `music.youtube.com/youtubei/v1/search` with the WEB_REMIX client and NewPipe's
  *Songs* filter. When that tab comes back empty (seen from some data-centre networks), the
  unfiltered results are used: songs and music videos only, no podcasts.
* **Streams:** `visitor_id`, then `youtubei.googleapis.com/youtubei/v1/player` with the VISIONOS
  client. This returns direct, unciphered URLs.
  * Only AAC (`audio/mp4`) is used, because AVPlayer cannot play WebM/Opus: itag 140
    (≈128 kbps), or itag 139 (≈48 kbps) in data-saver mode.
  * The audio is read in bounded POST ranges (`&range=…&rn=…`) through an
    `AVAssetResourceLoader` (`YouTubeAudioLoader`), like Android's `YouTubeChunkedDataSource`.
* **When YouTube changes its API,** update the client version and parameters in
  `YouTubeMusic.swift`, matching the current NewPipe Extractor release. The live tests in the
  **iOS** workflow (`LiveTests`) show what broke.

## SoundCloud (experimental)

Built on the same NewPipe Extractor as YouTube Music (shared initialisation in
`newpipe/NewPipeRuntime`), using SoundCloud's public web API without an account.

* **Search**: pick *SoundCloud* in the source chips on the results screen (shown when more than
  one online source is enabled). Pasted `soundcloud.com` links open the track/set/profile
  directly, whichever source is selected. Home and Explore stay on YouTube Music.
* **Ids**: `sc:soundcloud.com/<user>/<track>`, `scpl:soundcloud.com/<user>/sets/<set>`,
  `scu:soundcloud.com/<user>` — the public permalink, which NewPipe resolves.
* **Streams**: SoundCloud serves HLS only (NewPipe 0.27.1 lists AAC 160, MP3 128, Opus 64 and
  AAC 96 kbps; encrypted variants are skipped). Quality: Best/High → AAC 160, Standard → MP3 128,
  Data saver → Opus 64. Items are queued as HLS up front; segment cache keys ignore the URL
  signature, so re-resolved tracks still play from the cache.
* **Restrictions**: SoundCloud Go+ tracks only offer a 30-second preview — reported as
  *restricted (paid)* instead of being played as if complete; geo-blocked tracks likewise.
* **Live check**: `SoundCloudLiveTest` (opt-in) — run it on GitHub with the *Provider live tests*
  workflow (this repo's CI network can reach SoundCloud).

## Radio Browser

Community directory of internet radio (public API, no key, open data). `RadioBrowserProvider`:

* **Search** (source chip *Radio Browser*): station names, plus genre-tag matches on the first page
  (e.g. "jazz"); albums/artists filters return nothing. **Explore → Radio**: popular stations in
  your country (device locale), worldwide, and by genre.
* **Search by words**: names often differ from what people type ("Radio FAMA" in region "Kielce"),
  so besides the whole phrase each distinctive word is searched in names and regions, plus the
  phrase as a genre tag; stations matching the most words (name, region, tags, country) come
  first, then the most popular (`RadioSearch`).
* **HTTPS vs HTTP**: HTTPS streams are preferred. Many local broadcasters have no HTTPS; their
  stream may use plain HTTP only while *Radio: allow unencrypted stations* is on (default). The
  request is marked for `CleartextGuard`, which refuses HTTP for everything else.
* **Mirrors**: `de1`, `fi1`, `de2`, `nl1.api.radio-browser.info`, tried in order.
* **Clicks**: playing a station calls `/json/url/<uuid>` (station id only), as the API asks, which
  also returns the current stream URL.
* **Playback**: live (`ResolvedStream.isLive`) → no disk cache (`TRANSPORT_LIVE` route), no
  crossfade (no duration). HLS stations are queued as `radio:hls/<uuid>`. ICY `StreamTitle`
  updates the now-playing title (notification, lock screen, Android Auto) without interrupting.

### Open FM, RMF ON and ESKA (inside Radio)

Polish networks whose channels are not (or only partly) in Radio Browser. MusicFlow reads their
public websites (`RadioNetworks` on Android and iOS). This is unofficial: there is no documented
API, so a site redesign can break it. The parsers are unit-tested with saved copies of the pages.

* **Open FM:** the channel list embedded in `https://open.fm/` (`__NEXT_DATA__` →
  `fallback.stations`), with HLS streams `stream-cdn-1.open.fm/OFM<id>/…/playlist.m3u8`.
  Premium channels are skipped. Every stream request carries the web player's headers
  (browser User-Agent, `Origin`/`Referer: https://open.fm`), also for HLS follow-up requests
  (`PlaybackDataSources.withSiteHeaders`). The CDN answered HTTP 403 to servers outside Poland
  even with those headers, so playback is verified only by testers in Poland.
* **RMF ON:** `https://www.rmfon.pl/json/stations.txt` (id, name), then at play time
  `stacje/flash_aac_<id>.xml.txt` for the server list: MP3 servers first (128 kbps), AAC as
  fallback.
* **ESKA:** the channel tiles of `https://player.eska.pl/` (`data-stream-url`, HLS on
  `liveradio.timesa.pl`).
* Now playing ("RDS"):
  * Open FM: `open.fm/api/radio/playlist` (`currentSong`);
  * RMF ON: `www.rmfon.pl/stacje/ajax_playing_main.txt` (`radio<id>`: artist, title, cover);
  * ESKA: `front-api.grupazprmedia.pl/music/v2/now_playing/<id>/` (`current`).

  These are polled every 20 s while the station plays (each document cached 15 s).
* Lists are cached for 6 h; a stale list is used while a site is unreachable. They appear in
  Radio search (first, before Radio Browser), as Explore tiles on Android, and as Home tiles
  on iPhone. Track ids: `radio:net/<network>/<id>` (Android adds `hls/` for HLS).

## MusicBrainz

The open music encyclopedia (core data CC0). `MusicBrainzProvider`:

* **Search** (source chip *MusicBrainz*): recordings, albums (release groups), artists. MusicBrainz
  ranks by text only, so results are re-ranked: the item that *is* what was typed (title, or artist
  + title) first, then recordings on studio releases, then the most released versions. Recording
  queries search title and artist fields, studio releases first (broad fallback).
* **Albums**: tracklist of the most representative release (official, earliest); covers from the
  Cover Art Archive (`/release-group/<id>/front-250|500`; may be missing → placeholder).
* **Artists**: discography (albums; singles & EPs; no live/compilation/remix), and a factual line
  (type · area · years · genres). On YouTube/SoundCloud artist pages an *About · MusicBrainz*
  section appears when the name matches exactly with a high score — never for a guess.
* **Playback**: MusicBrainz has no audio. A recording is searched on YouTube Music and played only
  if title, artist and duration (±7 s) agree (`TextMatching.isReliableMatch`); otherwise it is
  reported as unavailable. Not available while YouTube Music is turned off.
* **API etiquette**: identifying User-Agent (`MusicFlow/<version> ( github URL )`), ≤ 1 request/s,
  503/`Retry-After` honoured with up to 3 retries.

## Local music

Reads `MediaStore` (permission `READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` on 9–12,
requested only when opening *On this device*). Files with `is_music = NULL` are included.
Lossless is reported only for FLAC/WAV/ALAC.

## Direct audio URLs

HTTPS only (cleartext is blocked by the network security configuration). Used for M3U entries
and user-supplied links; MusicFlow never scrapes or guesses stream URLs.

## LRCLIB

Free, keyless, open lyrics database. Only title, artist, album and duration of the current track
are sent, and only when the lyrics sheet is opened.
