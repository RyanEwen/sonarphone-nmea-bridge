---
description: Build the debug APK (persistent builder container, warm gradle daemon)
---

Build via the long-lived builder container — its Gradle daemon stays warm
between builds, which matters a lot here because the host is **arm64 WSL2**
and the amd64 SDK image runs under qemu (JVM startup alone costs ~1–2 min):

```sh
docker exec sonarbridge-builder ./gradlew assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.

Typical times: source-only edit 15–45 s; build-script edit ~1 min; first
build after daemon restart ~2 min. (One-shot `docker run --no-daemon` costs
4–7 min — don't.)

If the container is missing (`docker ps -a | grep sonarbridge-builder`),
recreate it:

```sh
docker run -d --name sonarbridge-builder --restart unless-stopped \
  -u $(id -u):$(id -g) -e HOME=/project/.home -e GRADLE_USER_HOME=/project/.gradle \
  -v ~/development/sonarphone-nmea-bridge/android:/project -w /project \
  mobiledevops/android-sdk-image:34.0.0-jdk17 sleep infinity
```

Notes:
- Signing is pinned to `android/keystore/debug.keystore` (`android` /
  `androiddebugkey` / `android`) so rebuilds stay `adb install -r`-compatible.
  Never let AGP regenerate a keystore (throwaway keystores →
  INSTALL_FAILED_UPDATE_INCOMPATIBLE).
- Don't use alpine-based SDK images (AAPT2 is glibc x86_64; no loader there).
- gradle.properties enables build cache + configuration cache + parallel.
- Future speedup if needed: arm64-native aapt2 via
  `android.aapt2FromMavenOverride` (third-party binary — evaluate before
  trusting) would eliminate qemu entirely.
