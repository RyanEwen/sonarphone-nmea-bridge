---
description: Start/stop/observe the bridge service on the phone over ADB
---

Drive the installed bridge (package `ca.dynamicsolutions.sonarbridge`) without touching
the phone UI:

```sh
# start (defaults: pattern "SonarPhone_" prefix picker, pass 12345678)
adb shell am start-foreground-service \
  -n ca.dynamicsolutions.sonarbridge/.BridgeService -a ca.dynamicsolutions.sonarbridge.START
# extras: -e ssid X (exact) OR -e pattern "SonarPhone_" (prefix picker; default)
#         -e pass Y  -e lograw true  -e udp 2000  -e demo true (fake data, no T-Box)

# stop
adb shell am start-foreground-service \
  -n ca.dynamicsolutions.sonarbridge/.BridgeService -a ca.dynamicsolutions.sonarbridge.STOP

# observe (tag SonarBridge: STATE / REDYFX / FRAME / NMEA lines)
adb logcat -s SonarBridge

# tap the NMEA stream Navionics sees
adb forward tcp:10110 tcp:10110 && nc 127.0.0.1 10110

# pull raw REDYFC captures (when started with -e lograw true)
adb pull /sdcard/Android/data/ca.dynamicsolutions.sonarbridge/files/ ./frames/
```

State machine: `WIFI_WAIT → DISCOVER (FX @1 Hz) → RUN (FC @10 s) → DISCOVER
after 15 s silence`. `NEED_MASTER` = factory-reset T-Box; official app must
run once. First-ever start shows a system WiFi approval dialog on the phone —
Rewen must tap it. If the T-Box is off, STOP the service when done testing so
the WiFi dialog doesn't re-prompt every ~35 s.

Full checklist: android/README-dev.md §Validation.
