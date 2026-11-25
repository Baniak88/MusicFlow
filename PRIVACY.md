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

All remote connections use HTTPS; cleartext is refused (`network_security_config.xml`).

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
| `github.com`, `objects.githubusercontent.com` | Only when you tap *Download* on the update banner (handled by your browser) | Standard download request |

Requests carry a generic browser or client User-Agent; no identifiers, cookies or tokens are
attached. The YouTube provider can be turned off entirely in Settings; MusicFlow then makes no
YouTube connections.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE` (streaming, connectivity monitoring), `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` (background playback),
`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE ≤ API 32` (only if you open *On this device*).
No location, contacts, microphone, phone or notification permission is requested.

## Diagnostics

Logs are sanitised when written: signed stream URLs, `sig`/`n`/`pot`/`key`/`token` parameters,
`Authorization`/`Cookie` headers, bearer tokens, e-mail addresses and long secret-like strings
are redacted (`core/common/Sanitizer.kt`, covered by unit tests). Export happens only when you
choose a file location.
