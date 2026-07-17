---
description: Build the APK and install it on the phone
---

Deploy the current code to the Pixel:

1. Build: follow `/build` (background the docker gradle run).
2. Connect the phone: follow `/find-phone` if `adb devices` shows nothing.
3. Install and re-grant:
   ```sh
   adb install -r android/app/build/outputs/apk/debug/app-debug.apk
   adb shell pm grant com.rewen.sonarbridge android.permission.POST_NOTIFICATIONS
   ```
4. Verify the install actually landed — `install -r` can fail quietly in
   pipelines; always check the timestamp changed:
   ```sh
   adb shell dumpsys package com.rewen.sonarbridge | grep lastUpdateTime
   ```
