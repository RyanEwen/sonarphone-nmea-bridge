---
name: deploy-android
description: Build and install the SonarBridge debug APK on the Android test phone. Use for deploying, reinstalling, or updating the development app over ADB.
---

# Deploy the Android debug APK

Read the **Build**, **Connect the phone**, and **Install** sections of
`docs/development.md`, then complete them in that order.

- Finish and verify the build before installing; do not leave it running in the
  background while deployment continues.
- Use `scripts/find-phone.py` if no authorized device appears in `adb devices`.
  If the script has no saved working endpoint, ask the user for the phone IP
  shown on Android's Wireless debugging screen.
- For demo mode or UI work an emulator can be the install target; see "Emulator for demo mode and UI work" in `docs/development.md`.
- Install
  `android/app/build/outputs/apk/debug/app-debug.apk` with
  `adb install -r`.
- Re-grant `android.permission.POST_NOTIFICATIONS` to
  `ca.dynamicsolutions.sonarbridge`.
- Verify `lastUpdateTime` with `adb shell dumpsys package`; do not treat an
  unchecked pipeline or a stale installed package as a successful deployment.
