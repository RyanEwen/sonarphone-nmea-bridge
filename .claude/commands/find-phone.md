---
description: Find and connect Rewen's Pixel 9 Pro over wireless adb
---

Connect to the test phone (Pixel 9 Pro, home LAN 192.168.2.0/24). The trusted
adb key is already in host `~/.android` — no pairing needed, but Rewen must
have Wireless debugging toggled ON (it turns itself off; ask if unreachable).

1. Try discovery with the last known IP (check `adb devices` first — may
   already be connected):
   ```sh
   python3 ~/development/persistent/.devcontainer/adb-discover.py <last-ip>
   ```
   Last known: 192.168.2.98 (2026-07-16). The script tier-scans ports (they
   rotate every toggle) and persists last-good to `~/.android/adb-endpoint`.

2. If the IP moved: ping-sweep the /24, then read the **Windows** ARP table
   (`/mnt/c/Windows/System32/ARP.EXE -a`) and shortlist locally-administered
   MACs (second hex digit 2/6/a/e = Android MAC randomization). Feed each
   candidate to adb-discover.py. Phones may not answer ping — trust ARP over
   ping.

mDNS does NOT work here (multicast doesn't cross the WSL2 NAT) — don't waste
time on `adb mdns services`.
