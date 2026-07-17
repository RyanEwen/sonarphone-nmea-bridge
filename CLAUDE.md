# SP200A → Navionics NMEA Bridge

## What this project is

A phone-only Android bridge that lets a discontinued Vexilar SonarPhone SP200A
fish finder feed depth + water temperature into the Garmin Navionics Boating
app (which dropped native SonarPhone support after v19 in favor of generic
NMEA 0183 over the network).

Owner context: personal use, single developer, Android only. AGPL-derived code
is acceptable. ESP32 hardware is on hand as a plan C but the goal is
phone-only.

## Architecture (decided — do not relitigate without cause)

```
[SP200A AP 192.168.1.1] ←UDP:5000→ [Bridge foreground service] ←TCP 127.0.0.1:10110→ [Navionics]
     local-only WiFi via WifiNetworkSpecifier              loopback        default route stays on cellular
```

- **Hard requirement:** the phone must keep internet while attached to the
  T-Box AP. This is why the sonar-side WiFi uses
  `WifiNetworkSpecifier` + `ConnectivityManager.requestNetwork()` (local-only
  peer network; does not become default route; UDP socket must be explicitly
  bound to the returned `Network`). Do NOT use `WifiManager` legacy connect or
  `WifiNetworkSuggestion` — those change/contend for the default route.
- NMEA side: loopback TCP server on 127.0.0.1:10110 emitting `$SDDPT`,
  `$SDDBT`, `$YXMTW`; re-emit ≤5 s intervals even when unchanged (Navionics
  clears depth on a quiet feed). Fallback if Navionics rejects loopback TCP
  pairing: UDP to 127.0.0.1:2000 (its legacy GoFree listening convention).
- Request meters from the T-Box; convert for DBT.
- The T-Box authenticates by master MAC only. The MAC is *read from the T-Box
  itself* (REDYFX reply) and echoed back — the bridge's own WiFi MAC and
  Android MAC randomization are irrelevant.

## Protocol

Full byte-level spec: `sp200a-nmea-bridge-spec.md` (in this repo).
Primary sources (also in repo): `SP200AProtocol_20221223.pdf` and
`SonarPhone_V1.00.ino` from https://github.com/jim-mckeown/SP200A-Client
(verified against real SP200A hardware). Community background:
https://github.com/scherererer/SonarPhony/issues/1

Summary: UDP to 192.168.1.1:5000. Send constant 29-byte `FX` → `REDYFX`
returns serial + master MAC. Build `FC` (settings + MAC + additive 16-bit LE
checksum of bytes 0–18) → T-Box streams ~796-byte `REDYFC` frames for ≥30 s;
re-send FC every 10 s. REDYFC offsets: depth u16le@23 + hundredths@25,
temp °C@26, battery@30–31, units@21, 758 echo-intensity samples @38+ (raw
sonar column — log it, Navionics can't use it but future in-app rendering
can).

**Known gotcha:** the PDF's worked FC example has an inconsistent checksum
column (0x01A7 balances for max=0x0A/feet/20°, not the 0xF0 shown). The
additive algorithm is confirmed by FX (0xB3) / FV (0xB1) and the ESP32 code.
Parse frames by `REDYFC` tag + fixed offsets, never by packet length (sizes
vary across models/modes). `FV` keep-alive is unnecessary — ignore it.

## Phases

- **Phase 0 (validate — REVISED 2026-07-16, now on-phone):** laptop validation
  was skipped (WSL2 NAT breaks the phone-facing paths; owner doesn't want to
  lug a laptop). Instead, a minimal dev APK (`android/`, drivable over ADB —
  see `android/README-dev.md`) performs the same validation checklist on the
  phone itself, which also exercises the two riskiest unknowns
  (WifiNetworkSpecifier behavior, Navionics loopback pairing) that a laptop
  never could. `sp200a_validate.py` is kept as protocol reference/desktop
  fallback; its two known bugs (keepalive dies on stream silence; trusts
  requested instead of reported units byte) are fixed in the Kotlin code but
  NOT in the script.
- **Phase 1 (Android app):** Kotlin, foreground service
  (dataSync/connectedDevice type). State machine:
  `DISCOVER (FX @1 Hz) → RUN (FC @10 s, parse stream) → DISCOVER on 15 s
  silence`. Hold the NetworkCallback for the WiFi connection's lifetime;
  auto-reconnect. Minimal UI: SSID/password entry, connection state, live
  depth/temp/battery, copyable Navionics pairing instructions
  (127.0.0.1:10110 TCP). Prompt for battery-optimization exemption.
- **Phase 2 (nice-to-have):** raw REDYFC logging (echo columns) to app
  storage; battery-voltage notification; own flasher/scroll sonar rendering
  from the 758-sample columns (replaces the broken official app entirely).

## Risk register (from design review)

| Risk | Mitigation |
|---|---|
| ~~Navionics-Android rejects 127.0.0.1 paired device~~ **RETIRED 2026-07-17: Navionics pairs to 127.0.0.1:10110 TCP and displays depth (verified against the bridge's demo feed).** Note: Navionics shows "connected" only once NMEA sentences actually flow — a silent socket reads as not-connected | n/a — verified working |
| Doze/OEM kills service on the water | foreground service + battery exemption + reconnect loop |
| requestNetwork drops on screen-off | hold callback in service, watchdog re-handshake |
| Unit-specific REDYFC deviations | phase 0 validator gates all app work |

## Conventions

- Validator/tooling: Python 3.8+, stdlib only.
- App: Kotlin, minSdk 29 (WifiNetworkSpecifier requirement), coroutines for
  socket loops, no third-party networking deps for the core path.
- UI: Material 3 views + DynamicColors (Material You on API 31+), programmatic
  layouts (no XML layouts, no Compose), custom Canvas view for sonar rendering.
