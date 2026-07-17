# SP200A → NMEA 0183 Bridge — Reference Spec

Target: feed depth + water temperature from a Vexilar SonarPhone SP200A into the
Garmin Navionics Boating app (Android) via NMEA 0183, phone-only, with internet
preserved on the phone.

Protocol source: Jim McKeown, *SonarPhone WiFi Communication* (2022-12-23), and
`jim-mckeown/SP200A-Client` (ESP32, verified against a real SP200A as a
second/stand-alone master). Cross-checked against `scherererer/SonarPhony`
community findings.

---

## 1. Transport

- T-Box runs its own AP. SSID varies per unit/firmware: McKeown's unit was
  `T-BOX-720`; Rewen's SP200A broadcasts `SonarPhone_65C0` (hex suffix likely
  MAC-derived). Don't assume a pattern — treat the SSID as user input. Default
  WPA2 passphrase `12345678` unless changed.
- T-Box IP: `192.168.1.1`. Protocol: **UDP, port 5000**. Client sends requests;
  server replies to the client's source address/port. (Note: some older
  SonarPhony-era notes mention TCP port 5000 also answering; the verified
  SP200A path is UDP.)
- Multi-byte integers are **little-endian** unless noted.

## 2. Authentication model

The app-level password is *not* used on the wire. The T-Box stores the MAC
address of the master client (set once via the official app after factory
reset). Any client that presents that MAC inside an FC request is treated as
master. The T-Box *discloses* the stored master MAC to anyone who sends FX.
Therefore:

1. Send FX → read master MAC from REDYFX.
2. Echo that MAC in FC requests.
3. Done. Works alongside the official app (second master) or standalone.

After a factory reset, REDYFX reports MAC `11:11:11:11:11:11` — the official
app must establish a master once before the bridge can operate.

## 3. Messages

### 3.1 FX request (client → T-Box), 29 bytes, constant

```
46 58 15 00 00 00 00 00 00 00 00 00 00 00 00 00
00 00 00 B3 00 00 00 00 00 00 00 00 00
```
`46 58` = "FX". Byte 19 = 0xB3 = additive checksum of bytes 0–18 (constant
because payload is constant).

### 3.2 REDYFX response (T-Box → client), 32 bytes

| Offset | Meaning |
|---|---|
| 0–5 | Header `FF 00 FF 00 20 00` |
| 6–11 | ASCII `REDYFX` |
| 12–15 | `16 00 02 00` (const) |
| 16–25 | T-Box serial number, 10 ASCII digits |
| 26–31 | **Master MAC address**, 6 bytes, MSB→LSB |

### 3.3 BUSY response (T-Box → client), 10 bytes

`FF 00 FF 00 0A 00` + ASCII `BUSY`. May be sent in reply to any request when
data isn't ready; just retry.

### 3.4 FC request (client → T-Box), 29 bytes

| Offset | Meaning |
|---|---|
| 0–1 | ASCII `FC` (`46 43`) |
| 2–3 | `15 00` (const) |
| 4–5 | `F4 02` (const) |
| 6–7 | Min depth, whole units, LE (normally 0) |
| 8–9 | Max depth, whole units, LE; **0 = auto-range** |
| 10 | 0 (const) |
| 11 | Units: `00` = meters, `01` = feet |
| 12 | 0 (const) |
| 13 | Beam width: `08` = 20°, `02` = 40° |
| 14–18 | 0 (const) |
| 19–20 | **Checksum**: 16-bit LE, additive sum of bytes 0–18. *Note: the PDF's worked example is internally inconsistent — its byte column (max=0xF0) doesn't match its checksum (0x01A7, which balances for max=0x0A/feet/20°). The additive algorithm itself is confirmed by the FX (0xB3) / FV (0xB1) constants and the working ESP32 code.* |
| 21–26 | Master MAC (from REDYFX bytes 26–31, same order) |
| 27–28 | 0 (const) |

Recommended bridge settings: min=0, max=0 (auto), units=meters (see §5),
beam=0x08.

### 3.5 REDYFC response (T-Box → client), ~796 bytes, streamed

Sent repeatedly, as fast as the T-Box can, for **≥30 s after each FC**.
Re-send FC every ~10 s to keep the stream alive (McKeown cadence). Sending FC
more often than every 30 s does not increase data rate.

| Offset | Meaning |
|---|---|
| 0–5 | Header `FF 00 FF 00 1C 03` |
| 6–11 | ASCII `REDYFC` |
| 12–15 | `12 03 F4 02` (const) |
| 16–17 | Min range, whole units, LE |
| 18–19 | Max range, whole units, LE (0 = auto) |
| 20 | const |
| 21 | Units in effect: `00` = meters, `01` = feet |
| 22 | const |
| 23–24 | **Depth, whole units, LE** |
| 25 | **Depth, hundredths** (e.g. 0x33 = 0.51) |
| 26 | **Water temperature, whole °C** |
| 27–29 | const (`00 14 01`) |
| 30 | Battery volts, whole |
| 31 | Battery volts, hundredths |
| 32 | Beam width (`08`/`02`) |
| 33–37 | Master MAC bytes 2–6 (partial echo) |
| 38–795 | **758 echo-intensity samples** spanning the 80 m max range (≈0.10554 m/sample, ≈9.475 samples/m) |

`depth = u16le(23,24) + byte(25)/100`, in the units of byte 21.

Open items to verify on-unit (see §7): behavior when no bottom lock (expect
depth 0 or frozen), and whether packet size varies with beam/frequency (SP200
units reported 360-byte pings to SonarPhony vs 340 on T-POD, so parse by
`REDYFC` tag + offsets, never by packet length).

### 3.6 FV request/response — optional keep-alive

29-byte request `FV` (checksum 0xB1), 16-byte `REDYFV` reply. Confirmed
unnecessary for master operation; ignore.

## 4. NMEA 0183 output

Sentences (talker `SD` for sounder, `YX` for transducer temp):

- `$SDDPT,<depth_m>,0.0*hh` — depth in **meters**, transducer offset 0
- `$SDDBT,<ft>,f,<m>,M,<fa>,F*hh` — depth below transducer, all three units
- `$YXMTW,<temp_c>,C*hh` — water temperature

Checksum `hh`: XOR of all chars between `$` and `*`, two uppercase hex digits.
Terminate `\r\n`.

Rules learned from working setups:
- Request **meters** from the T-Box and convert; DPT is meters-only and DBT
  wants all three units anyway. Navionics displays in the user's chosen units
  regardless.
- **Re-emit at least every 5 s even if unchanged** — Navionics clears the
  depth readout when the feed goes quiet.
- Emit on every new REDYFC depth, rate-limited to ~1–2 Hz to be polite.

## 5. Android bridge architecture (phone-only, internet preserved)

```
[SP200A AP 192.168.1.1] ←UDP:5000→ [Bridge foreground service] ←TCP 127.0.0.1:10110→ [Navionics]
        (local-only WiFi, via WifiNetworkSpecifier)        (loopback)         (default route: cellular → internet)
```

- **WiFi attach:** `WifiNetworkSpecifier.Builder().setSsid("T-BOX-…")
  .setWpa2Passphrase(…)` → `ConnectivityManager.requestNetwork()` with a
  `NetworkCallback`. This is a *peer-to-peer, local-only* connection: it does
  **not** become the default route, so cellular keeps serving internet to the
  whole phone. The callback's `Network` object must be held for the connection
  to persist, and the UDP socket must be explicitly bound to it
  (`network.getSocketFactory()` / `network.bindSocket(...)`). One-time user
  approval dialog on connect; API 29+.
- **Service:** foreground service (`dataSync`/`connectedDevice` type), partial
  wakelock optional; state machine: `DISCOVER (FX loop @1 Hz) → RUN (FC every
  10 s, parse REDYFC stream) → back to DISCOVER on 15 s silence`.
- **NMEA server:** minimal TCP server on `127.0.0.1:10110` (multi-client, so
  you can attach a debug reader alongside Navionics). Optional secondary mode:
  UDP unicast datagrams to `127.0.0.1:2000` (Navionics' legacy
  GoFree/Digital-Yacht listening port) as a fallback if loopback TCP pairing
  misbehaves.
- **Navionics setup:** Menu → Paired Devices → + → Host `127.0.0.1`, Port
  `10110`, Protocol TCP.
- **MAC randomization:** irrelevant — the bridge echoes the *stored master's*
  MAC from REDYFX; its own WiFi MAC never matters.
- **Extras (optional):** log raw REDYFC frames (758-sample echo columns +
  battery voltage) to app storage as a binary/CSV log for later rendering or
  ReefMaster-style processing; surface battery voltage as a notification.

### Risk register

| Risk | Likelihood | Mitigation |
|---|---|---|
| Navionics-Android rejects `127.0.0.1` paired device | low-med | UDP→`127.0.0.1:2000` fallback; worst case ESP32 plan C |
| REDYFC offsets differ on this unit | low (spec is SP200A-verified) | Step-0 validator script confirms before app work |
| Doze/battery-optimization kills service on the water | med | Foreground service + battery-optimization exemption prompt |
| `requestNetwork` drops on screen-off (OEM quirks) | low-med | Hold callback in service; auto-reconnect loop |
| T-Box stops streaming despite FC refresh | low | Watchdog: silence >15 s → full FX re-handshake |

## 6. ESP32 plan C (only if phone-only fails)

McKeown's client already does WiFi+parse; replace the SeaTalk output with a
WiFi AP (`WIFI_AP_STA`) broadcasting UDP port 2000. Cost: phone must join the
ESP's internet-less AP and rely on Android's "keep mobile data when WiFi has
no internet" behavior — functional but less clean than the specifier approach.

## 7. Validation checklist (step 0, laptop on T-BOX WiFi)

1. FX → REDYFX: serial + master MAC decode correctly.
2. FC (meters, auto-range, 20°) → REDYFC stream arrives; note packet size(s).
3. Depth/temp offsets sane vs. known water depth; observe no-bottom behavior.
4. Confirm stream lasts ~30 s per FC; confirm 10 s FC cadence sustains it.
5. Optional: point phone Navionics at the laptop's NMEA output (TCP 10110) to
   prove the Navionics side end-to-end before writing any Android code.
