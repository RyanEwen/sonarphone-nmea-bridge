---
description: Find and connect Rewen's Pixel over wireless adb
---

Connect to the test phone (Pixel 9 Pro) over wireless adb. The trusted adb key
is already in host `~/.android` — no pairing needed, but Rewen must have
**Wireless debugging ON for the current network** (it disables itself when the
phone changes networks).

Use the retry-hardened discovery script (lives in this repo):

```sh
python3 scripts/find-phone.py <phone-ip>
```

- Get `<phone-ip>` from the phone: Settings → Developer options → Wireless
  debugging → the **IP address & Port** line (use the IP; the port rotates and
  the script re-finds it). The IP is DHCP-stable per network, so once given it
  is saved to `~/.android/adb-endpoint` and future reconnects on that network
  need no argument.
- The script scans the host's ephemeral range, distinguishing *refused* (truly
  closed) from *timed-out* (retried, in case of a dropped SYN), then
  `adb connect`s the first port that speaks adb.

**Do NOT try to auto-guess the phone's IP by MAC.** Learned 2026-07-17 the hard
way: MAC-randomization is not unique to Android (an Apple device on the LAN also
had a locally-administered MAC), the wireless-debug port rotates every toggle,
and mDNS (`adb mdns services`) does not cross the WSL2 NAT. The phone's IP is
the one reliable seed — ask for it rather than scanning the whole /24.
