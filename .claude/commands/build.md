---
description: Build the debug APK in Docker (no host installs)
---

Build the Android debug APK. The host is **arm64 WSL2** — the SDK image runs
under qemu, so expect 2–7 minutes; run it in the background.

```sh
docker run --rm -u $(id -u):$(id -g) \
  -e HOME=/project/.home -e GRADLE_USER_HOME=/project/.gradle \
  -v "$(pwd)/android":/project -w /project \
  mobiledevops/android-sdk-image:34.0.0-jdk17 \
  ./gradlew --no-daemon assembleDebug
```

Output: `android/app/build/outputs/apk/debug/app-debug.apk`.

Notes:
- Signing is pinned to `android/keystore/debug.keystore` (`android` /
  `androiddebugkey` / `android`) so rebuilds stay `adb install -r`-compatible.
  Never let AGP regenerate a keystore (containers make throwaways → 
  INSTALL_FAILED_UPDATE_INCOMPATIBLE).
- Don't use alpine-based SDK images (AAPT2 is glibc; fails under qemu+musl).
