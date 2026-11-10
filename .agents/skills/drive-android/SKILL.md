---
name: drive-android
description: Start, stop, observe, or inspect the installed SonarBridge service over ADB. Use for bridge testing, demo mode, logcat, NMEA stream inspection, or raw-frame retrieval.
---

# Drive the Android bridge

Read the **Drive and observe** section of `android/README-dev.md` and use the
specific command needed for the request.

- Confirm an authorized device is connected before issuing ADB commands.
- The shared emulator also works for demo mode and UI work; see "Emulator for demo mode and UI work" in the README. Real bridge testing needs a physical phone.
- The app package is `ca.dynamicsolutions.sonarbridge`.
- Starting without extras selects a `SonarPhone_` SSID prefix and password
  `12345678`. Preserve user-supplied `ssid`, `pattern`, `pass`, `lograw`, `udp`,
  or `demo` extras.
- A first connection can require the user to accept Android's Wi-Fi approval
  dialog. This interaction cannot be completed through ADB.
- If testing without a powered T-Box, stop the service afterward so it does not
  keep presenting the Wi-Fi approval flow.
- When tapping the forwarded NMEA socket, establish the ADB forward before
  starting `nc` and report the observed sentence stream.
