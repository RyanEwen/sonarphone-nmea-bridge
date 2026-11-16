---
name: find-phone
description: Connect the Android test phone over wireless ADB with this repository's discovery script. Use when ADB shows no device or the wireless-debugging port has rotated.
---

# Connect the Android test phone

Get an authorized ADB connection to the test phone, following **Connect the phone** in `docs/development.md`.

1. Check `adb devices` and reuse an authorized connection if one is present.
2. Run `python3 scripts/find-phone.py`. It reuses a saved endpoint and discovers the rotating port; pass the phone's IP when there is no usable saved endpoint.
3. If the IP is unknown, ask the user for the address shown on the phone's Wireless debugging screen.
4. Confirm that `adb devices` lists the endpoint with state `device`.

Report: the connected serial, or what blocked the connection.
