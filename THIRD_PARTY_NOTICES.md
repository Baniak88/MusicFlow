# Third-party notices

MusicFlow is licensed under the GNU GPL v3.0 or later. It includes or links the following
components. Full licence texts: GPL-3.0 → `LICENSE`; Apache-2.0 → https://www.apache.org/licenses/LICENSE-2.0;
MIT, BSD-3-Clause, MPL-2.0 texts at https://opensource.org/licenses.

| Component | Version | Licence | Copyright / source |
|---|---|---|---|
| NewPipe Extractor | v0.27.1 | GPL-3.0-or-later | © NewPipe e.V. and contributors — github.com/TeamNewPipe/NewPipeExtractor |
| nanojson (TeamNewPipe fork) | e9d656d | MIT / Apache-2.0 | © Matt Mastracci and contributors |
| Mozilla Rhino | 1.8.1 | MPL-2.0 | © Mozilla Foundation — source available at github.com/mozilla/rhino |
| jsoup | 1.24.1 | MIT | © Jonathan Hedley |
| Protocol Buffers (javalite) | 4.36.2 | BSD-3-Clause | © Google LLC |
| JSR-305 annotations | 3.0.2 | BSD-3-Clause | © FindBugs |
| AndroidX: Media3, Compose, Material 3, Room, DataStore, Lifecycle, Navigation, Activity, AppCompat, Core, Palette, Media, Hilt-Navigation | see `gradle/libs.versions.toml` | Apache-2.0 | © The Android Open Source Project / Google LLC |
| Material Icons | via Compose BOM | Apache-2.0 | © Google LLC |
| Dagger / Hilt | 2.60.1 | Apache-2.0 | © The Dagger Authors |
| Kotlin stdlib, kotlinx.coroutines, kotlinx.serialization | 2.4.10 / 1.11.0 / 1.13.0 | Apache-2.0 | © JetBrains s.r.o. |
| OkHttp, Okio | 4.12.0 | Apache-2.0 | © Square, Inc. |
| Coil 3 | 3.5.0 | Apache-2.0 | © Coil Contributors |
| Reorderable | 3.1.0 | Apache-2.0 | © Calvin Liang |
| Guava (transitive, Media3) | — | Apache-2.0 | © Google LLC |
| Desugar JDK libs | 2.1.5 | GPL-2.0 with Classpath Exception | © Oracle / Google |

**iPhone app (`ios/`):** uses only Apple system frameworks (SwiftUI, AVFoundation, MediaPlayer,
Foundation, UIKit) — no third-party code is shipped. Build tool only: XcodeGen (MIT, © Yonas
Kolb). Its YouTube Music requests mirror NewPipe Extractor's (GPL-3.0) client parameters; no
NewPipe code is included.

Build/test only (not shipped): Robolectric (MIT), JUnit 4 (EPL-1.0), Truth (Apache-2.0),
Turbine (Apache-2.0), MockWebServer (Apache-2.0), AndroidX Test (Apache-2.0).

Reference projects studied (no code copied): OuterTune (GPL-3.0), InnerTune (GPL-3.0). The
dependency version baseline follows OuterTune's build configuration.

Services: LRCLIB (lrclib.net) lyrics; YouTube / YouTube Music content is © its respective owners
and is not distributed with MusicFlow. SoundCloud content is © its owners; "SoundCloud" is a
trademark of SoundCloud Global Limited & Co. KG. Open FM (Grupa WP), RMF ON (Grupa RMF) and ESKA (Grupa ZPR Media) channel lists, logos and
streams belong to their broadcasters; MusicFlow only links to them. Station directory data: Radio Browser
(radio-browser.info, community-maintained, public domain); streams belong to their broadcasters.
Music metadata: MusicBrainz (musicbrainz.org) core data, CC0 1.0, by the MetaBrainz Foundation and
contributors; cover images: Cover Art Archive (coverartarchive.org), © their respective owners. "YouTube" is a trademark of Google LLC; MusicFlow is not
affiliated with or endorsed by Google.
