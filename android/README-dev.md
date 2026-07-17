# SP200A Bridge — dev APK runbook

Debug build, drivable entirely over ADB. Package: `com.rewen.sonarbridge`.

## Build (Docker, no host installs)

```sh
docker run --rm -u $(id -u):$(id -g) -e GRADLE_USER_HOME=/project/.gradle \
  -v "$PWD/android":/project -w /project \
  alvrme/alpine-android:android-34-jdk17 ./gradlew --no-daemon assembleDebug
# -> android/app/build/outputs/apk/debug/app-debug.apk
```

## Connect the phone (WSL2-friendly: wireless debugging)

The host `~/.android` holds the adb key the Pixel 9 Pro already trusts
(recovered from the `persistent-android-data` Docker volume), so no pairing is
needed — just turn on Wireless debugging and rediscover the rotated port:

```sh
# discovery script from the persistent project (tiered port scan; mDNS
# doesn't cross the WSL2 NAT). Seed it with the phone's current/last IP:
python3 ~/development/persistent/.devcontainer/adb-discover.py 192.168.2.98
adb devices    # e.g. 192.168.2.98:38875  device  model:Pixel_9_Pro
```

If the IP moved too: ping-sweep the /24, then check the *Windows* ARP table
(`/mnt/c/Windows/System32/ARP.EXE -a`) for locally-administered MACs (second
hex digit 2/6/a/e — Android per-network MAC randomization) and scan those.

## Install & drive

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk

# start (defaults: pattern "SonarPhone_" prefix picker, pass 12345678)
adb shell am start-foreground-service \
  -n com.rewen.sonarbridge/.BridgeService \
  -a com.rewen.sonarbridge.START \
  -e pass "12345678"   # add -e ssid "X" for exact, -e pattern "T-BOX-" for prefix

# optional extras:
#   -e lograw true   log raw REDYFC frames (timestamped) to app files dir
#   -e udp 2000      also emit NMEA via UDP to 127.0.0.1:2000 (Navionics fallback)

# stop
adb shell am start-foreground-service \
  -n com.rewen.sonarbridge/.BridgeService -a com.rewen.sonarbridge.STOP
```

First start shows the system WiFi-connect approval dialog on the phone —
accept once. Bridge state machine: `WIFI_WAIT → DISCOVER (FX @1 Hz) →
RUN (FC @10 s) → DISCOVER on 15 s silence`. `NEED_MASTER` means the T-Box is
factory-fresh (11:11… sentinel): run the official SonarPhone app once.

## Watch it work

```sh
adb logcat -s SonarBridge          # STATE/REDYFX/FRAME/NMEA lines, ~1 Hz

# tap the NMEA stream from the dev box (same stream Navionics sees):
adb forward tcp:10110 tcp:10110
nc 127.0.0.1 10110                 # expect $SDDPT/$SDDBT/$YXMTW every ~1 s

# pull raw frame logs (when started with -e lograw true):
adb shell ls /sdcard/Android/data/com.rewen.sonarbridge/files/
adb pull /sdcard/Android/data/com.rewen.sonarbridge/files/ ./frames/
```

Raw log record format: `u64le wall-clock ms, u16le frame length, frame bytes`.

## Navionics pairing (on the phone)

Menu → Paired devices → **+** → Host `127.0.0.1`, Port `10110`, Protocol TCP.
If it refuses loopback TCP, restart the bridge with `-e udp 2000` and try the
UDP option — this is the key risk to validate.

## Validation checklist (phase 0, now on-phone)

1. `STATE DISCOVER` → `REDYFX serial=… masterMac=…` decodes sanely.
2. `STATE RUN`, `FRAME` lines: note packet size, depth/temp vs known water.
3. No-bottom-lock behavior: watch depth field with transducer out of water.
4. Stream survives on 10 s FC cadence; watchdog recovers after AP power-cycle.
5. Phone keeps internet while attached to T-Box AP (browse in another app).
6. Navionics pairs to 127.0.0.1:10110 and shows depth — THE unverified risk.
7. Screen off 10+ min: stream continues (check FRAME counter in logcat).
8. Enable Menu > SonarChart Live in Navionics while moving: our NMEA depth +
   phone GPS should draw live personal bathymetry contours (the bridge acts
   as a free Digital Yacht Sonar Server). Raw-sonar split view is NOT
   possible — Garmin removed third-party sonar rendering after v19.

## Releases & updates

- Cut a release: `git tag v0.2.0 && git push origin v0.2.0` — GitHub Actions
  builds a signed APK and publishes a Release with a filtered changelog.
- The app checks the latest release on open/resume (3 h throttle) and offers
  the APK with the release notes; "Later" mutes that version.
- Release signing: keystore lives only in GitHub secrets + gitignored
  `android/keystore/release.keystore` (creds in `release.env` beside it).
- NOTE: release APKs are signed with the release key — a phone running a
  debug build must uninstall once before its first release install
  (signature mismatch; settings are lost that one time).
