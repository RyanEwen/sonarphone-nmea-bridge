# SP200A Bridge developer runbook

Scope: building, installing, driving, validating and releasing the Android app.
Governs: `android/`, `.github/workflows/`, `scripts/`.

The GitHub-flavor debug APK can be built and driven entirely over ADB. Its
package is `ca.dynamicsolutions.sonarbridge`. Run commands from the repository
root unless a section says otherwise.

## Checks

The root `package.json` holds the entry points; `npm run validate` is the gate CI runs on every push and pull request, and before every release.

| Command | Runs |
| --- | --- |
| `npm run validate` | `format:check`, `lint`, `test`, `check:l10n`, `check:release-notes` and `verify:android`, stopping at the first failure |
| `npm run format` / `format:check` | Spotless (ktlint) on Kotlin sources and Gradle scripts: rewrite, or fail on a difference |
| `npm run lint` | Android lint for both debug variants |
| `npm run test` | Robolectric unit tests for both flavors, then `node --test` for `scripts/*.test.mjs` |
| `npm run check:l10n` | `android/scripts/check-localization.py` |
| `npm run check:release-notes` | Every file in `release-notes/` is named correctly, complete for every catalog, and within Play's 500-character limit |
| `npm run verify:android` | Debug builds of both flavors |
| `npm run build:android` | The installable GitHub-flavor debug APK |
| `npm run install:android` | Installs that APK on the connected device with `adb install -r` |
| `npm run package:android` | The Play release bundle (signed only when the release signing variables are set) |

Gradle tasks go through `scripts/gradle`. In CI, or with `SONARBRIDGE_GRADLE=local`, it runs the wrapper directly. Otherwise it uses the running `sonarbridge-builder` container, or else a disposable container from the `sonarbridge-build` image capped at `SONARBRIDGE_GRADLE_MEMORY` (default `4g`, no swap) with one worker.

## Build

The long-lived `sonarbridge-builder` container builds natively on the host architecture (x86_64 or arm64) and keeps the
Gradle cache warm. Start it if it already exists but is stopped:

```sh
docker start sonarbridge-builder
docker exec sonarbridge-builder ./gradlew assembleGithubDebug
stat android/app/build/outputs/apk/github/debug/app-github-debug.apk
```

The Gradle command must finish successfully, and the APK modification time must
advance. Do not rely on the last output line alone because a failed build can
still end with a configuration-cache message.

If the builder does not exist, create it once:

```sh
docker build -t sonarbridge-build -f android/docker/Dockerfile android/docker
mkdir -p android/.gradle
# arm64 hosts only: use the image's static aapt2. On x86_64 skip this.
if [ "$(uname -m)" = aarch64 ]; then
  printf 'android.aapt2FromMavenOverride=/opt/android-tools/aapt2\n' \
    > android/.gradle/gradle.properties
fi
docker run -d --name sonarbridge-builder --restart unless-stopped \
  --memory 5g --memory-swap 5g \
  -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/project/.gradle \
  -e JAVA_TOOL_OPTIONS=-Duser.home=/tmp \
  -e ANDROID_USER_HOME=/tmp/.android \
  -v "$PWD/android:/project" -w /project \
  sonarbridge-build sleep infinity
```

The memory cap keeps a runaway build from exhausting the host: the Gradle daemon is limited to 2 GiB by `android/gradle.properties`, which leaves room for the Kotlin daemon and workers inside 5 GiB. Apply the same cap to an existing builder with `docker update --memory 5g --memory-swap 5g sonarbridge-builder`.

The writable Java and Android homes are required when the host numeric UID has
no passwd entry in the builder image; otherwise AGP tries to create `/.android`.

The AAPT2 override (arm64 only) is container-local and gitignored. Do not add it to the
committed `android/gradle.properties`, where it would break x86_64 builds.
The pinned debug keystore at `android/keystore/debug.keystore` keeps
`adb install -r` compatible across builds.

## Connect the phone

The Pixel 9 Pro already trusts the ADB key in the host's Android configuration.
Wireless debugging must still be enabled for its current network.

```sh
adb devices
python3 scripts/find-phone.py <phone-ip>
adb devices
```

The script remembers successful endpoints in `~/.android/adb-endpoint` and
rediscovers a rotated wireless-debugging port. If it has no usable saved
endpoint, get `<phone-ip>` from **Settings > Developer options > Wireless
debugging > IP address & Port**. Use the IP only.

Do not guess the phone from randomized MAC addresses or scan the whole subnet.
The Pixel does not reliably answer ICMP, other devices use randomized MACs,
and ADB mDNS does not cross WSL2 NAT.

## Install

```sh
adb install -r android/app/build/outputs/apk/github/debug/app-github-debug.apk
adb shell pm grant ca.dynamicsolutions.sonarbridge android.permission.POST_NOTIFICATIONS
adb shell dumpsys package ca.dynamicsolutions.sonarbridge | grep lastUpdateTime
```

Confirm that `lastUpdateTime` changed. A debug build and a release build use
different signing keys, so switching between them can require one uninstall
and will clear settings.

## Drive and observe

Start the service with the default `SonarPhone_` prefix and password:

```sh
adb shell am start-foreground-service \
  -n ca.dynamicsolutions.sonarbridge/.BridgeService \
  -a ca.dynamicsolutions.sonarbridge.START
```

Optional extras are `-e ssid X` for an exact SSID, `-e pattern PREFIX`,
`-e pass Y`, `-e lograw true`, `-e udp 2000`, and `-e demo true`.

Stop the service:

```sh
adb shell am start-foreground-service \
  -n ca.dynamicsolutions.sonarbridge/.BridgeService \
  -a ca.dynamicsolutions.sonarbridge.STOP
```

Observe logs and the NMEA stream:

```sh
adb logcat -s SonarBridge
adb forward tcp:10110 tcp:10110
nc 127.0.0.1 10110
```

Pull raw frames after starting with `-e lograw true`:

```sh
adb pull /sdcard/Android/data/ca.dynamicsolutions.sonarbridge/files/ ./frames/
```

The state machine is `WIFI_WAIT → DISCOVER (FX @1 Hz) → RUN (FC @10 s) →
DISCOVER` after 15 seconds of silence. `NEED_MASTER` means the T-Box is
factory-fresh; run the official SonarPhone app once. The first connection can
show Android's Wi-Fi approval dialog, which the user must accept on the phone.
If the T-Box is off, stop the service after testing so the dialog does not
reappear repeatedly.

### Emulator for demo mode and UI work

An Android emulator can run the app with the `demo` extra (`-e demo true`) and is suitable for UI work; point `adb` at it and use the commands above unchanged. Real bridge testing still needs a physical phone, because an emulator cannot join a SonarPhone T-Box Wi-Fi network.

## Watch it work

```sh
adb logcat -s SonarBridge          # STATE/REDYFX/FRAME/NMEA lines, ~1 Hz

# tap the NMEA stream from the dev box (same stream Navionics sees):
adb forward tcp:10110 tcp:10110
nc 127.0.0.1 10110                 # expect $SDDPT/$SDDBT/$YXMTW every ~1 s

# pull raw frame logs (when started with -e lograw true):
adb shell ls /sdcard/Android/data/ca.dynamicsolutions.sonarbridge/files/
adb pull /sdcard/Android/data/ca.dynamicsolutions.sonarbridge/files/ ./frames/
```

Raw log record format: `u64le wall-clock ms, u16le frame length, frame bytes`.

## Navionics pairing

Menu → Paired devices → **+** → Host `127.0.0.1`, Port `10110`, Protocol TCP.
Loopback TCP is verified with Navionics on Android. UDP on port 2000 remains a
legacy fallback.

## Validation checklist

1. `STATE DISCOVER` → `REDYFX serial=… masterMac=…` decodes sanely.
2. `STATE RUN`, `FRAME` lines: note packet size, depth/temp vs known water.
3. No-bottom-lock behavior: watch depth field with transducer out of water.
4. Stream survives on 10 s FC cadence; watchdog recovers after AP power-cycle.
5. Phone keeps internet while attached to T-Box AP (browse in another app).
6. Navionics pairs to 127.0.0.1:10110 and shows depth.
7. Screen off 10+ min: stream continues (check FRAME counter in logcat).
8. Enable Menu > SonarChart Live in Navionics while moving: our NMEA depth +
   phone GPS should draw live personal bathymetry contours (the bridge acts
   as a free Digital Yacht Sonar Server). Raw-sonar split view is NOT
   possible because Garmin removed third-party sonar rendering after v19.

## Releases & updates

The public APK on GitHub is the universal APK Google Play generates and signs; the app does not download or install updates itself. The full procedure is the `release` skill (`.agents/skills/release/SKILL.md`); in short:

1. Write the notes for the version in `release-notes/`: `<X.Y.Z>.md` (en-US) plus one `<X.Y.Z>.<identity>.md` for es-ES, fr-FR, de-DE, zh-CN, pt-BR and ja-JP. User-facing bullets only, no heading, at most 500 characters each; Canadian listings reuse en-US and fr-FR. `npm run check:release-notes` checks them. Get the notes approved, then commit them on `main`.
2. Create an annotated `vX.Y.Z` tag on that commit and push it. The tag is the version; the versionCode is `major*10000 + minor*100 + patch`, with minor and patch below 100.
3. The Release workflow runs the CI gate, uploads the Play bundle to **production as a draft** with each locale's notes, waits for Google's universal APK, verifies its package, version and signer, and publishes the GitHub Release "SonarBridge X.Y.Z" (marked Latest) with that APK. Missing or over-long notes fail the run before anything is built.
4. Check the draft in Play Console, then run **Publish to Google Play** with the tag and `publish_draft=true`. It sends that draft to review after checking its versionCode against the tag, without uploading anything.
5. Afterwards, run Play Check and confirm an installed copy updates.

`PLAY_SIGNING_CERTIFICATE_SHA256` (repository variable) is the **app-signing** SHA-256 fingerprint from Play Console, not the upload key; the APK export fails closed if it is missing or differs from Google's metadata or the APK's signer. Release signing credentials stay in GitHub secrets and the git-ignored `android/keystore/release.env`.

If the Play upload succeeded but the GitHub export failed, do not rebuild or re-upload that versionCode: run **Replace GitHub APK** (`play-apk.yml`) for the existing release. It never touches v0.2.4.

`play-publish` can also be run by hand: upload a tag to a testing track (`track`, `release_status`), sync only the listing (`listing_only`), or read tracks, codes and signer fingerprints without changing anything (`inspect_only`). Uploads first reject reused or older versionCodes.

Signing-key migration (finished): v0.2.4 was the final APK signed with the old upload key and v0.2.5 the first Play-signed one. v0.2.4 stays on its release for old installs, and `docs/android-migration-release-notes.md` is the user guide.

Verify the release scripts without accessing Play:

```sh
npm run test:scripts
```

Build verification:

```sh
npm run verify:android
npm run package:android
```
