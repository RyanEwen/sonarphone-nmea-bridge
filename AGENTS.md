# SonarBridge repository guidance

## Project shape

SonarBridge supports a Vexilar SonarPhone SP200A through two clients:

- `android/`: the primary Android app. It displays sonar data and sends NMEA
  0183 depth and temperature to Garmin Navionics on the same phone.
- `esp32-client/`: an ESPHome-based standalone display and NMEA bridge for an
  ESP32-8048S050 panel.

Read the source closest to the work before changing it:

- `sp200a-nmea-bridge-spec.md` is the byte-level protocol source of truth.
- `android/README-dev.md` is the Android build, deployment, and validation
  runbook.
- `esp32-client/README.md` documents the ESPHome component, local display-driver
  fork, performance constraints, and hardware caveats.

## Architecture invariants

- The Android app must keep the phone's normal internet route while connected
  to the T-Box access point. Use `WifiNetworkSpecifier` with
  `ConnectivityManager.requestNetwork()` and bind sonar UDP sockets to the
  returned local-only `Network`. Do not replace this with legacy
  `WifiManager` connection APIs or `WifiNetworkSuggestion`.
- The Android NMEA server listens on `127.0.0.1:10110` over TCP. It emits
  `$SDDPT`, `$SDDBT`, and `$YXMTW`, including keepalive emissions no more than
  five seconds apart so Navionics does not clear an unchanged depth. This path
  is verified on the water.
- Request metres from the T-Box and convert values as needed for `$SDDBT`.
- T-Box authentication uses the master MAC returned by `REDYFX`, not the
  Android or ESP32 Wi-Fi MAC.
- Parse `REDYFC` by its tag and fixed field offsets, never by total packet
  length. Preserve the additive 16-bit little-endian `FC` checksum behavior
  documented in the protocol specification.
- Keep shared protocol and state-machine behavior aligned between the Kotlin
  Android client and the ESPHome C++ component when a change applies to both.

## Android conventions

- Use Kotlin and coroutines for socket loops. Keep the core network path free
  of third-party networking dependencies.
- `minSdk` is 29 and `compileSdk`/`targetSdk` are 36. Do not target API 37
  without first adding the `ACCESS_LOCAL_NETWORK` declaration, runtime request,
  denial handling, and validation for this local-network-dependent app.
- Use Material 3 views and `DynamicColors`, programmatic layouts, and the
  existing custom `Canvas` sonar view. Do not introduce Compose or XML layouts
  without an explicit architecture decision.
- The foreground service type is `connectedDevice`.
- Preserve the separate `github` and `play` product flavors. `github` is for
  development and the final legacy migration update. Public downloads from
  both GitHub and Google Play use Google's verified Play-flavor universal APK.
  Neither flavor downloads APK updates itself. Keep legacy-only battery
  permission and ADB service access out of the Play flavor.

## Verification

- For Android build, phone discovery, deployment, and ADB operation, use the
  repository skills in `.agents/skills/` and the commands in
  `android/README-dev.md`.
- Test protocol or lifecycle changes against the relevant state transitions:
  `WIFI_WAIT`, `DISCOVER`, `RUN`, silence recovery, and reconnect after an AP
  power cycle.
- For ESP32 changes, preserve the rendering and PSRAM constraints in
  `esp32-client/README.md`; the local `mipi_rgb` fork is intentional.
