# Music providers

| Provider | Id prefix | Status | Capabilities | Network |
|---|---|---|---|---|
| On this device | `local` | Stable | Search (local), streams | none |
| Direct audio URL | `url` | Stable | Streams (HTTPS; MP3/AAC/Opus/FLAC/WAV/HLS) | the URL's host |
| YouTube Music (NewPipe Extractor) | `yt`, `ytpl`, `ytch` | **Experimental**, optional | Search, suggestions, albums/playlists, artists, related/radio, trending, streams | YouTube hosts |
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
