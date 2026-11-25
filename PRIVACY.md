# Privacy

MusicFlow is local-first. It has **no advertising SDKs, no analytics, no crash reporting service,
no account and no server of its own.**

## Data stored on your device

| Data | Where | Purpose | Delete |
|---|---|---|---|
| Liked songs, saved albums, favourite artists, playlists | Room database | Your library | Library / backup restore |
| Listening history (track, time, listened ms, completed/skipped) | Room | History, "most played", on-device recommendations | Settings → Delete listening history; or turn off *Save listening history* |
| Search history | Room | Suggestions | Settings → Delete search history, or per entry |
| Playback queue & position | Room + SharedPreferences | Resume where you left off | Clearing the queue |
| Settings | DataStore | Preferences | Uninstall / clear data |
| Audio cache, artwork cache | App cache dir | Fewer downloads | Settings → Clear audio cache |
| Diagnostic events (max 300) | Memory only | Troubleshooting | Lost on exit; Diagnostics → Clear |

Android cloud backup and device-transfer are disabled (`allowBackup=false`, data extraction
rules exclude everything); use *Backup & restore* to save a JSON file you control.

## Network connections

All remote connections use HTTPS; plain HTTP is refused in code for every request and redirect
(`CleartextGuard`). The single exception is the audio stream of an internet radio station that has
no HTTPS, while *Radio: allow unencrypted stations* is on (see the table).

| Host | When | Data sent |
|---|---|---|
| `music.youtube.com`, `www.youtube.com`, `youtubei.googleapis.com` | YouTube Music provider enabled: search, browse, stream resolution | Search terms, video/playlist/channel ids, locale & content country |
| `*.googlevideo.com` | Playing YouTube audio | Stream requests (byte ranges) |
| `charts.youtube.com` | Home → Trending | Content country |
| `suggestqueries-clients6.youtube.com` | Typing in Search | Partial query |
| `i.ytimg.com`, `*.googleusercontent.com`, `yt3.ggpht.com` | Artwork | Image URL |
| `lrclib.net` | Opening lyrics | Title, artist, album, duration |
| Hosts in your M3U files / direct links | Playing them | Standard HTTP request |
| `api.github.com` | Update check (at most every 6 h; Settings → *Check for app updates*, on by default, can be turned off) | Request for the public release list of the MusicFlow repository; no device or user identifiers |
| `github.com`, `objects.githubusercontent.com`, `release-assets.githubusercontent.com` | Downloading an update: when you tap *Install*, or in the background if *Install updates automatically* is on (Settings, can be turned off) | Standard download request for the release APK and its `SHA256SUMS.txt` |
| `de1.api.radio-browser.info`, `fi1.api.radio-browser.info`, `de2.api.radio-browser.info`, `nl1.api.radio-browser.info` | Radio Browser enabled: browsing/searching stations, playing a station (its id is sent to `/json/url/…`, as the directory asks) | Search terms, country code, genre tag, station id |
| Radio station hosts | Playing a station you chose. HTTPS when the station offers it; plain HTTP only for stations that have no HTTPS and only while *Radio: allow unencrypted stations* is on (default on; turn it off to keep everything encrypted) — a network observer can then see which station you listen to | Standard streaming request (with `Icy-MetaData` for song titles) |
| `musicbrainz.org`, `coverartarchive.org`, `archive.org` | MusicBrainz enabled: searching it, opening its albums/artists, artist pages (artist name lookup), album covers | Search terms, artist names, MusicBrainz ids; User-Agent with the app version |
| `soundcloud.com`, `api-v2.soundcloud.com`, `api-widget.soundcloud.com`, `a-v2.sndcdn.com`, `*.sndcdn.com`, `playback.media-streaming.soundcloud.cloud` | SoundCloud provider enabled: searching SoundCloud, opening and playing SoundCloud tracks | Search terms, track/set/user links, locale; no account |

Requests carry a generic browser or client User-Agent; no identifiers, cookies or tokens are
attached. The YouTube provider can be turned off entirely in Settings; MusicFlow then makes no
YouTube connections. The same applies to SoundCloud (separate switch).

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE` (streaming, connectivity monitoring), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` (background playback),
`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE ≤ API 32` (only if you open *On this device*).
`REQUEST_INSTALL_PACKAGES`, `UPDATE_PACKAGES_WITHOUT_USER_ACTION` (in-app updates of MusicFlow
itself: only APKs from this project's GitHub releases, after checking the SHA-256 checksum, the
package name, a higher version and the same signing certificate as the installed app; Android
asks you to allow installs once, and on Android 12+ later updates can install without a prompt;
turn off *Install updates automatically* to keep full manual control).
No location, contacts, microphone, phone or notification permission is requested.

## Diagnostics

Logs are sanitised when written: signed stream URLs, `sig`/`n`/`pot`/`key`/`token` parameters,
`Authorization`/`Cookie` headers, bearer tokens, e-mail addresses and long secret-like strings
are redacted (`core/common/Sanitizer.kt`, covered by unit tests). Export happens only when you
choose a file location.
