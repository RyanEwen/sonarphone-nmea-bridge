# SonarBridge: Agent Instructions

SonarBridge gives owners of a Vexilar SonarPhone SP200A (T-Box) a modern sonar display and feeds live NMEA 0183 depth and temperature to Garmin Navionics. It has two clients: the primary Android app, shipped on Google Play and as the Play-signed APK on GitHub Releases, and an ESPHome-based standalone display and NMEA bridge for an ESP32-8048S050 panel. It builds on no shared kits.

## Standards

This repo follows the shared standards at https://github.com/Dynamic-Solutions-Canada/app-standards. Read the index for this stack, https://github.com/Dynamic-Solutions-Canada/app-standards/blob/main/stacks/android.md, then the topic file for the area you are changing. If `external/standards/` exists, it is the same content pinned for offline use. Where this file differs from the standards, this file wins and says why.

## Architecture

`docs/protocol.md` is the byte-level protocol source of truth for both clients. A protocol change updates it in the same commit as the code.

| Area | Files |
| --- | --- |
| T-Box protocol client | `android/app/src/main/java/ca/dynamicsolutions/sonarbridge/Sp200a.kt` |
| Connection state machine and foreground service | `BridgeService.kt`, `BridgeState.kt` (same package) |
| NMEA sentences and the local TCP server | `Nmea.kt`, `NmeaServer.kt` |
| Screen and tab navigation (Views in code) | `MainActivity.kt` |
| Status tab and Settings tab | `StatusTab.kt`, `SettingsTab.kt` |
| Every setting's key, default and range (one typed wrapper, CONF-8) | `AppSettings.kt` |
| The one dialog shape | `AppDialog.kt` |
| Sonar waterfall (custom `Canvas` view) and its history | `SonarView.kt`, `EchoHistory.kt` |
| Units, regional formats and status text | `Units.kt`, `RegionalFormat.kt`, `StatusText.kt` |
| App language selection | `AppLanguage.kt`, `App.kt`; strings in `android/app/src/main/res/values*/strings.xml` |
| About screen (name, version, updates, links, third-party notices) | `AboutActivity.kt`, reached from the last row of Settings |
| Every web address the app opens | `AppLinks.kt` |
| Shared list row and section heading | `ListRow.kt` |
| GitHub and Play download guidance | `AndroidDownloads.kt` |
| T-Box passphrase, encrypted with an Android Keystore key and excluded from backup | `SonarPassphrase.kt`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| Generated third-party notices | The `generate<Variant>ThirdPartyNotices` task in `android/app/build.gradle.kts` |
| Flavor-only manifest entries | `android/app/src/github/AndroidManifest.xml` |
| ESP32 sonar client, display and NMEA bridge | `esp32-client/components/sp200a/` |
| ESP32 display driver (intentional local fork) | `esp32-client/components/mipi_rgb/` |
| ESP32 device config and its secrets template | `esp32-client/sp200a-8048S050.yaml`, `esp32-client/secrets.example.yaml` |
| Play listing source (text and images, one copy each) | `store-assets/play/`; `store-assets/web/screenshots/` for README-only images |
| Play publishing, release notes and versioning | `scripts/play-*.mjs`, `scripts/release-notes.mjs`, `scripts/release-version.sh`, `release-notes/`, `.github/workflows/` |
| Test phone discovery over wireless ADB | `scripts/find-phone.py` |
| Command entry points and the Gradle runner | `package.json`, `scripts/gradle` |

## Behaviour

- The Android app must keep the phone's normal internet route while connected to the T-Box access point. Use `WifiNetworkSpecifier` with `ConnectivityManager.requestNetwork()` and bind sonar UDP sockets to the returned local-only `Network`. Do not replace this with legacy `WifiManager` connection APIs or `WifiNetworkSuggestion`.
- The Android NMEA server listens on `127.0.0.1:10110` over TCP. It emits `$SDDPT`, `$SDDBT`, and `$YXMTW`, including keepalive emissions no more than five seconds apart so Navionics does not clear an unchanged depth. This path is verified on the water.
- Preserve the separate `github` and `play` product flavors. `github` is for development and the final legacy migration update. Public downloads from both GitHub and Google Play use Google's verified Play-flavor universal APK. Neither flavor downloads APK updates itself. Keep legacy-only battery permission and ADB service access out of the Play flavor.

## Conventions and gotchas

- Request metres from the T-Box and convert values as needed for `$SDDBT`.
- T-Box authentication uses the master MAC returned by `REDYFX`, not the Android or ESP32 Wi-Fi MAC.
- Parse `REDYFC` by its tag and fixed field offsets, never by total packet length. Preserve the additive 16-bit little-endian `FC` checksum behavior documented in the protocol specification.
- Keep shared protocol and state-machine behavior aligned between the Kotlin Android client and the ESPHome C++ component when a change applies to both.
- Use Kotlin and coroutines for socket loops. Keep the core network path free of third-party networking dependencies.
- `minSdk` is 29 and `compileSdk`/`targetSdk` are 36. Do not target API 37 without first adding the `ACCESS_LOCAL_NETWORK` declaration, runtime request, denial handling, and validation, because the app depends on local-network access.
- Android Gradle Plugin 8.5.2 and Kotlin 1.9.24 are pinned; the reason was not recorded. Review them at the next upgrade pass (D-095) rather than bumping them in passing. Dependabot ignores both for the same reason.
- Build-tools stays at 35.0.0 because the pinned static arm64 `aapt2` build only goes up to 35.0.2 (see `android/docker/Dockerfile`).
- Use Material 3 views and `DynamicColors`, programmatic layouts, and the existing custom `Canvas` sonar view. Do not introduce Compose or XML layouts without an explicit architecture decision.
- The foreground service type is `connectedDevice`.
- Read and write settings only through `AppSettings` (CONF-8), and the T-Box passphrase only through `SonarPassphrase`, which encrypts it with AES/GCM under an Android Keystore key in its own file that backup excludes (SEC-2). Robolectric has no Keystore, so tests call `TestKeys.install()`.
- New dialogs use `AppDialog`; new rows use `ListRow`.
- Put any new web address in `AppLinks`, so a repository or policy move edits one file.
- About labels copy winui-app-kit's `kit.about.*` wording and translations (BRAND-8), and `about_tagline` must equal the Play short description (checked by `check:l10n`).
- ESPHome reads Wi-Fi settings from a git-ignored `esp32-client/secrets.yaml`; add any new key to `secrets.example.yaml` with a placeholder.
- For ESP32 changes, preserve the rendering and PSRAM constraints in `esp32-client/README.md`; the local `mipi_rgb` fork is intentional.
- The ESP32 Build workflow (`.github/workflows/esp32.yml`) compiles the firmware with ESPHome 2026.7.4 on every `esp32-client/` change. That version tracks the `mipi_rgb` fork's base; move both together.

## Commands

The root `package.json` holds the entry points. Gradle tasks run through `scripts/gradle`, which picks the build container and its memory cap; see **Checks** in `docs/development.md`.

| Command | Proves | Does not prove |
| --- | --- | --- |
| `npm run validate` | Everything below, as CI runs it on every push, pull request and release | Device behaviour or the protocol path |
| `npm run build:android` | The development APK compiles and packages | That it installs or connects to a T-Box |
| `npm run format:check` | Kotlin sources and Gradle scripts match the ktlint format (`npm run format` rewrites them) | Behaviour |
| `npm run lint` | Android lint passes for both debug variants | Runtime behaviour |
| `npm run test` | The Robolectric unit tests (both flavors) and the Play and release script tests pass | Device behaviour, or that a real Play upload succeeds |
| `npm run check:l10n` | Every locale has the same string keys and placeholders | That the translations read well |
| `npm run check:release-notes` | Release notes are complete for every catalog and fit Play's limit | That they read well |
| `npm run check:listings` | The Play listing has every locale and fits Play's limits | That a Console upload succeeds |

Test protocol or lifecycle changes on a phone against the relevant state transitions: `WIFI_WAIT`, `DISCOVER`, `RUN`, silence recovery, and reconnect after an AP power cycle. The validation checklist in `docs/development.md` lists what to observe.

## Release

Use the `release` skill. Notes are committed per version in `release-notes/` before the tag. Pushing a `vX.Y.Z` tag runs `release.yml`: the CI gate, then the Play bundle uploaded to production as a draft with those notes, then Google's signed universal APK attached to the GitHub Release. Sending the draft to review is a separate, deliberate `play-publish` run with `publish_draft` (REL-7). Summary and recovery steps are under **Releases & updates** in `docs/development.md`.

## Guidance map

| Guide, doc, or skill | Read or use when | Governs |
| --- | --- | --- |
| `docs/protocol.md` | Any protocol, parsing or checksum change | `Sp200a.kt`, `esp32-client/components/sp200a/` |
| `docs/development.md` | Building, installing, driving, validating or releasing the Android app | `android/`, `.github/workflows/`, `scripts/` |
| `docs/localization.md` | Adding or changing interface text or languages | `android/app/src/main/res/values*/`, `AppLanguage.kt` |
| `esp32-client/README.md` | Any ESP32 component, display or config change | `esp32-client/` |
| `store-assets/play/listings.md` | Changing store listing text, graphics or Console answers | `store-assets/`, the generated `fastlane/` tree |
| `build-android` skill | Building the development APK | Android builds |
| `deploy-android` skill | Installing the development APK on the test phone | ADB installs |
| `drive-android` skill | Starting, stopping or observing the bridge service over ADB | ADB testing |
| `find-phone` skill | ADB shows no device or the wireless-debugging port has rotated | Phone connection |
| `commit` skill | Creating a commit | Git commits |
| `release` skill | Releasing a new version | `release-notes/`, tags, `release.yml`, `play-publish.yml` |

## Keeping docs current

| What changed | Update |
| --- | --- |
| A Kotlin file, component or script added, moved or removed | The Architecture table above |
| Protocol fields, offsets, or checksum behaviour | `docs/protocol.md`, then both clients |
| Build, install, drive or release procedure | `docs/development.md` and the matching skill |
| Interface text or supported languages | `docs/localization.md`, every `values*/strings.xml` |
| A check, test suite or build command added | `package.json` (and `validate` when it is static), **Checks** in `docs/development.md`, Commands above |
| User-visible features | `README.md` |
| What the app stores, where, or whether it is backed up | **Data the app keeps** in `docs/development.md`, the README's Privacy section, the policy page in the company website repo |
| Listing text, store images or a claim the listing makes | `store-assets/play/listings.json` and `listings.md` (every locale) |
| ESP32 options, secrets keys, or hardware caveats | `esp32-client/README.md`, `esp32-client/secrets.example.yaml` |
