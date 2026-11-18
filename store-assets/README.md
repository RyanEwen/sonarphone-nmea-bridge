# Store screenshots

Scope: how the Play screenshots are captured, framed, checked and published (LIST-8, ASSET-9).
Governs: `store-assets/manifest.json`, `store-assets/raw/`, `store-assets/marketing/`, `store-assets/play/screenshots/`, `scripts/store-shots.mjs`.

`manifest.json` is the source for the order, the English headline and caption, and the alt text in every wording catalog. Each Play image is a real dark-mode capture from the released app, placed in a reusable CSS device frame on the saved studio backdrop, below its headline and caption. App pixels come only from the raw captures in `raw/android/`. The frame, backdrop and text are drawn around them, and nothing inside the screen is retouched. The approach follows persistent's `store-assets/README.md`.

Screenshots are captured once, in English, and serve every listing locale (D-098). The README shows the phone set's three portrait images, the same files as the listing.

## The set

| # | Scene | Phone | Tablet |
| --- | --- | --- | --- |
| 01 | Sonar tab, portrait, demo mode | 1080 x 1920 | 1200 x 1920 |
| 02 | Sonar tab, landscape, showing the navigation rail | 1920 x 1080 | 1920 x 1200 |
| 03 | Status tab, demo mode running | 1080 x 1920 | 1200 x 1920 |
| 04 | Settings tab, top of the page | 1080 x 1920 | 1200 x 1920 |

## Capture

Use the shared headless emulator (`/development/android-vm/README.md`), never a phone with personal data. It needs about 6 GiB: check `free -m`, start it only when no Windows VM is running, and keep 2.5 GiB free. Its memory cap can be lowered with a Compose override if the host is tight.

1. Install the released APK, not a development build, so the screens show the shipped version. Release runs keep Google's verified APK as a private workflow artifact for 90 days: `gh run download <release run id> -n sonarbridge-X.Y.Z-play-apk -D .artifacts/apks` (run **Export Play APK** for the tag first if it has expired), then `docker cp` it into the container. An installed debug build has a different signature; uninstall it first. Google's signed APK carries Play's license check, which sends a sideloaded install straight to the Play Store, so install it as if from Play: `adb install -i com.android.vending`. Grant `android.permission.POST_NOTIFICATIONS` with `pm grant` so the permission dialog does not cover the first screen.
2. Turn on dark mode (`cmd uimode night yes`, noting the earlier value) and the system UI demo mode for a clean status bar: `settings put global sysui_demo_allowed 1`, then the `com.android.systemui.demo` broadcasts `enter`, `clock` (`hhmm 1000`), full `battery` (not plugged), full `network` Wi-Fi and mobile, and hidden `notifications`. Other apps' ongoing notifications on the shared emulator still show an icon; hide them for the session with `cmd notification snooze --for 3600000 '<key>'` (keys from `dumpsys notification`), which expires by itself.
3. Set a timezone where local time is just before 10:00 (`settings put global auto_time_zone 0`, then `service call alarm 3 s16 <zone>`), so the Status tab's "Last received" time agrees with the 10:00 status bar.
4. Show the fully set-up app: exempt it from battery optimization (`dumpsys deviceidle whitelist +ca.dynamicsolutions.sonarbridge`) so the Status tab hides its battery-optimization button. In the app: keep the default settings, capture Settings first (top of the page), then turn on **Demo mode** and tap **Connect** on Status. Wait until the waterfall has filled the screen and shows fish and the bottom.
5. Capture each scene with `adb exec-out screencap -p` into `raw/android/phone/NN-subject-dark.png`. For the landscape scene, lock rotation (`settings put system accelerometer_rotation 0`, `user_rotation 1`).
6. For the tablet set, switch the same emulator to a 16:10 tablet display (`wm size 1600x2560`, `wm density 320`), reopen the app, and capture into `raw/android/tablet/`. Resizing restarts the status bar and can double its Wi-Fi and signal icons: send demo `exit`, then the step 2 broadcasts again, before capturing.
7. Restore the emulator: `wm size reset`, `wm density reset`, rotation back to auto (`user_rotation 0`, `accelerometer_rotation 1`), `cmd uimode night` and the timezone back to their earlier values with `auto_time_zone 1`, demo mode `exit`, and `sysui_demo_allowed 0`, and the battery exemption removed (`whitelist -ca.dynamicsolutions.sonarbridge`). Disconnect the app, and reinstall whatever SonarBridge build was there before. Stop the VM when done.

Check every capture before keeping it: a nonempty PNG, the app fully drawn (no loading frame or dialog), the clean status bar with one Wi-Fi and one signal icon, and no personal or network names.

## Render and check

```sh
npm run shots:backdrop   # only when marketing/backdrop.html changes
npm run shots:render
npm run shots:check
```

`shots:render` runs headless Chromium from the Playwright image in a disposable container capped at 1.5 GiB (`SONARBRIDGE_RENDER_MEMORY` overrides it). It writes the exports straight into `play/screenshots/phone/` and `play/screenshots/tablet/`, where the Play upload reads them. `shots:check` confirms each folder holds exactly the manifest's files, and that every export is an 8-bit RGB PNG at its layout's size, with its raw capture, a headline and caption that fit the layout, and alt text for every catalog.

The backdrop is drawn in CSS (`marketing/backdrop.html`) and saved as `marketing/studio-background.png`, so it can be regenerated exactly.

## Approval and publishing

New or changed screenshots are shown to Ryan and approved before they are committed or uploaded (LIST-8). After approval, commit the raw captures, manifest and exports together. The Publish to Google Play workflow uploads `play/screenshots/` with the next release, or on its own with `listing_only`.
