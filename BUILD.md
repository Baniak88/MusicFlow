# Building MusicFlow

## Without a computer setup: GitHub Actions

1. Push to any branch → **Android CI** runs lint, all unit tests and builds a debug APK.
   Download it from the run page → *Artifacts* → `MusicFlow-debug-<run>`.
2. Push a tag `vX.Y.Z` → **Release** builds `MusicFlow-X.Y.Z.apk`, attaches it and
   `SHA256SUMS.txt` to a GitHub Release.
3. **Device tests (emulator)** (manual / weekly) runs instrumented tests on API 28 and 34 emulators.

### Signed release builds

Create a keystore once (never commit it):

```bash
keytool -genkeypair -v -keystore musicflow-release.jks -alias musicflow \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 musicflow-release.jks > keystore.b64
```

Add repository secrets (*Settings → Secrets and variables → Actions*):

| Secret | Value |
|---|---|
| `MUSICFLOW_KEYSTORE_BASE64` | contents of `keystore.b64` |
| `MUSICFLOW_KEYSTORE_PASSWORD` | keystore password |
| `MUSICFLOW_KEY_ALIAS` | `musicflow` |
| `MUSICFLOW_KEY_PASSWORD` | key password |

Without these secrets CI still works: it builds the debug APK and the release job publishes a
clearly labelled `-debug.apk`. Keep the keystore safe — updates must be signed with the same key.

## Local build

Requirements: JDK 17+ (CI uses 21), Android SDK with `platforms;android-37.0` and
`build-tools;36.0.0`. Point Gradle at the SDK with `ANDROID_HOME` or `local.properties`
(`sdk.dir=…`, untracked).

```bash
./gradlew :app:assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # minified (R8); signed if keystore.properties or env vars exist
./gradlew lint test                 # lint + JVM/Robolectric tests
./gradlew :data:providers:testDebugUnitTest -PliveTests=true   # live YouTube Music checks
./gradlew :app:connectedDebugAndroidTest                        # on a connected device/emulator
```

Local release signing alternative: `keystore.properties` in the project root (git-ignored):

```
storeFile=/absolute/path/musicflow-release.jks
storePassword=…
keyAlias=musicflow
keyPassword=…
```

Version: `-PversionName=1.2.3 -PversionCode=42` (CI derives them from the tag and run number).

## Toolchain (verified together)

Gradle 9.6.1 (wrapper, checksum pinned) · AGP 9.2.1 · Kotlin 2.4.10 · KSP 2.3.4 · Hilt 2.60.1 ·
Compose BOM 2026.09.00 · Media3 1.10.1 · Room 2.8.4 · compileSdk 37, targetSdk 36, minSdk 28.

`settings.gradle.kts` lists Google's official Maven Central mirror before `mavenCentral()` because
Central throttled large artifacts (HTTP 429) during development; JitPack is restricted to the
NewPipe group.

## Outputs

| Variant | Size (approx.) | Notes |
|---|---|---|
| debug | ~84 MB | unminified, `app.musicflow.debug` id (installs beside release) |
| release | ~9.4 MB | R8 + resource shrinking, `app.musicflow` |
