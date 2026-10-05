# SP200A Bridge developer runbook

The GitHub-flavor debug APK can be built and driven entirely over ADB. Its
package is `ca.dynamicsolutions.sonarbridge`. Run commands from the repository
root unless a section says otherwise.

## Build

The long-lived `sonarbridge-builder` container is arm64-native and keeps the
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
docker build -t sonarbridge-build:arm64 \
  -f android/docker/Dockerfile.arm64 android/docker
mkdir -p android/.gradle
printf 'android.aapt2FromMavenOverride=/opt/android-tools/aapt2\n' \
  > android/.gradle/gradle.properties
docker run -d --name sonarbridge-builder --restart unless-stopped \
  -u "$(id -u):$(id -g)" \
  -e GRADLE_USER_HOME=/project/.gradle \
  -e JAVA_TOOL_OPTIONS=-Duser.home=/tmp \
  -e ANDROID_USER_HOME=/tmp/.android \
  -v "$PWD/android:/project" -w /project \
  sonarbridge-build:arm64 sleep infinity
```

The writable Java and Android homes are required when the host numeric UID has
no passwd entry in the builder image; otherwise AGP tries to create `/.android`.

The AAPT2 override is container-local and gitignored. Do not add it to the
committed `android/gradle.properties`, where it would break non-arm64 builds.
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

The public APK on GitHub is now the universal APK generated and signed by
Google Play. Keep the GitHub flavor for development and one final legacy
migration release. The app no longer downloads or installs APK updates itself.

Before rollout, set these GitHub repository variables:

- `PLAY_SIGNING_CERTIFICATE_SHA256`: the **app-signing** SHA-256 fingerprint
  from Play Console, not the upload key. The `inspect-play-state` workflow can
  also read the authoritative signer from Google's existing bundle metadata
  without downloading or publishing anything. Export fails closed if it is missing
  or differs from either Google's metadata or the actual APK signer.
- `LEGACY_MIGRATION_TAG`: the exact tag chosen for the final old-key update.
  Set this **before pushing that tag**. That tag builds only the legacy APK;
  subsequent tags use the Play lane. Keep the variable as the historical tag
  so re-running the transition cannot accidentally upload it to Play.
- `KEEP_LEGACY_MIGRATION_LATEST=true`: keep the migration release as GitHub's
  latest while publishing the Play-signed release alongside it. Old updaters
  call `/releases/latest`; new users follow the direct Play-signed links in
  the README and migration guide. Set this to `false` only when the transition
  is finished and mark the intended Play-signed release as latest.

Rollout order:

1. Choose a fresh `vX.Y.Z` tag and set `LEGACY_MIGRATION_TAG` to it. The tag
   must contain this migration code and have a versionCode higher than all
   existing GitHub APKs. The shared scheme is `major*10000 + minor*100 + patch`,
   with minor/patch below 100. Review the prior published codes before tagging.
2. Push that tag. `release.yml` builds `assembleGithubRelease` with the existing
   upload/legacy signing key and publishes `sonarbridge-X.Y.Z.apk`. Existing
   apps can install it through their current updater, retaining settings.
   It shows the migration notice once and keeps it in Settings, with equal
   GitHub and Play links. Debug builds do not show the launch notice.
3. Publish the next higher tag alongside the migration release, keeping
   `KEEP_LEGACY_MIGRATION_LATEST=true` during the transition. It builds `bundlePlayRelease`, uploads to the
   existing **internal** Play track, waits for Google's universal APK, verifies
   package/version/signer, then publishes `sonarbridge-X.Y.Z-play.apk` to GitHub with `make_latest=false`.
   The chosen transition releases are v0.2.4 (legacy) and v0.2.5 (Play-signed).
   Update the direct download links in the README and migration guide for a
   later public release; do not point them at `/releases/latest` during migration.
   Production availability still depends on Play approval and tester eligibility;
   exporting an APK does not approve or promote the app to production.
4. Legacy users record settings and save app files before disconnecting,
   uninstalling and reinstalling once. Users who miss the final legacy update can follow the
   release-page instructions directly; their old updater cannot show the new
   notice and attempting an in-place Play APK update will fail. Check sonar readings, alarm/calibration,
   permissions and Navionics afterward. Existing Play users update normally.

Manual `release` runs build the exact named tag. `legacy_migration=true` is
an explicit escape hatch for the final old-key update; never use it for future
regular releases. Do not dispatch it concurrently with a tag release.

If Play upload succeeded but GitHub export failed, do not rebuild/re-upload
that versionCode. Run `replace-github-apk` for an existing GitHub release, or
create a reviewed GitHub release first and then run that workflow. It downloads
an existing Play version and uploads the verified replacement before deleting
the old asset. It preserves existing release notes, so review migration copy
separately. Do not replace the final migration release while it is still
needed by users running old APKs.

`play-publish` remains available for manual track selection and listing-only
syncs. A full build takes an existing `vX.Y.Z` tag; listing-only needs no tag. Full uploads first inspect current Play codes and
reject reused or older codes before building. `inspect-play-state` reports
tracks, bundle codes and signer fingerprints using a temporary edit that is
always discarded, never committed.
Promote an already uploaded bundle via Play Console, rather than uploading the
same versionCode a second time. Release signing credentials remain in GitHub
secrets and gitignored `android/keystore/release.env`; never change the legacy
key during migration.

Verify export policy without accessing Play:

```sh
node scripts/play-apk.test.mjs
node scripts/play-status.test.mjs
node scripts/release-version.test.mjs
```

Build verification uses the existing Docker builder:

```sh
docker exec sonarbridge-builder ./gradlew assembleGithubDebug assemblePlayDebug bundlePlayRelease
```

Debug APKs use the pinned debug key; they cannot prove migration from a legacy
release key or Google's real signer. Before rollout, test a signed final legacy
update over an old release with customized settings, then verify the manual
uninstall/reinstall flow and a later Play-signed APK update. Never uninstall a
user's app or clear their settings as part of routine validation.
