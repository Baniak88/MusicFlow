# AGENTS.md — guidance for AI coding agents and contributors

## Ground rules
- Licence is GPL-3.0-or-later; keep notices in THIRD_PARTY_NOTICES.md up to date when adding deps.
- No ads, analytics, tracking or crash-reporting SDKs. No new permissions without updating PRIVACY.md.
- Never fabricate catalogue data in production code. Empty → honest empty/error state.
- Never claim lossless for lossy sources; display only what the source/decoder reports.
- Never commit keystores, `keystore.properties`, `local.properties` or secrets.
- Do not disable or skip tests to make a build pass.

## Layout
See ARCHITECTURE.md. Features must not depend on each other — use `LocalNavigator` /
`LocalTrackMenu` from `core:designsystem`. Provider-specific code stays in
`data/providers/<provider>/`.

## Conventions
- Kotlin, Compose, `StateFlow` + `collectAsStateWithLifecycle`, Hilt constructor injection.
- All provider failures must be `ProviderError`; classify new failure modes in `YouTubeErrors`
  / `defaultClassify` and add a test.
- `MediaController` must only be touched on the main thread (see `MediaControllerEngine`).
- User-visible strings go to `res/values/strings.xml` **and** `res/values-pl/strings.xml`.
- Media3 `@UnstableApi` usages need an explicit `@OptIn`/`@UnstableApi` (lint enforces it).

## Commands
```bash
./gradlew lint test :app:assembleDebug                     # what CI runs
./gradlew :data:providers:testDebugUnitTest -PliveTests=true   # live provider checks (network)
```

## When the YouTube provider breaks
1. Run the live tests; read `Diagnostics` export from a device.
2. Check NewPipe Extractor releases; bump `newpipeExtractor` in `gradle/libs.versions.toml`.
3. Update PROVIDERS.md "Known limitations".
