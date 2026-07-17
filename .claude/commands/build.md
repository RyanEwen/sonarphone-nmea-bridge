---
description: Build the debug APK (arm64-native builder container, warm gradle daemon)
---

Build via the long-lived **arm64-native** builder container (no qemu):

```sh
set -o pipefail; docker exec sonarbridge-builder ./gradlew assembleDebug 2>&1 | tail -20
# ALWAYS check: "BUILD SUCCESSFUL" present AND the APK mtime advanced.
# A FAILED build also ends with "Configuration cache entry reused" — tail -1
# without pipefail reads as success and silently reinstalls the stale APK.
ls -l android/app/build/outputs/apk/debug/app-debug.apk
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.
Typical times: incremental ~6 s, clean build ~10 s, cold daemon ~1 min.

The image is built from `android/docker/Dockerfile.arm64`: arm64 temurin JDK
+ SDK platform 34 + a pinned arm64 `aapt2` static build
(lzhiyong/android-sdk-tools 35.0.2 — the one binary Google doesn't ship for
linux-arm64), wired in via `android.aapt2FromMavenOverride` in the
container-local `android/.gradle/gradle.properties` (gitignored — do not put
that override in the committed gradle.properties; it would break non-arm64
machines).

If the container is missing (`docker ps -a | grep sonarbridge-builder`),
recreate it:

```sh
docker build -t sonarbridge-build:arm64 \
  -f android/docker/Dockerfile.arm64 android/docker
printf 'android.aapt2FromMavenOverride=/opt/android-tools/aapt2\n' \
  > android/.gradle/gradle.properties
docker run -d --name sonarbridge-builder --restart unless-stopped \
  -u $(id -u):$(id -g) -e HOME=/project/.home -e GRADLE_USER_HOME=/project/.gradle \
  -v ~/development/sonarphone-nmea-bridge/android:/project -w /project \
  sonarbridge-build:arm64 sleep infinity
```

Notes:
- Signing is pinned to `android/keystore/debug.keystore` (`android` /
  `androiddebugkey` / `android`) so rebuilds stay `adb install -r`-compatible.
  Never let AGP regenerate a keystore (throwaway keystores →
  INSTALL_FAILED_UPDATE_INCOMPATIBLE).
- gradle.properties enables build cache + configuration cache + parallel.
- amd64 fallback (works anywhere, slow under qemu here):
  `mobiledevops/android-sdk-image:34.0.0-jdk17` with the same mounts, minus
  the aapt2 override. Don't use alpine-based SDK images (AAPT2 needs glibc).
