# Google Play publishing guide

Everything you need to publish SonarBridge to the **Internal testing** track,
plus copy to paste into each Console field. The build side is done — this is
the Console (web-only) side.

## The artifact to upload

- **File:** `dist/sonarbridge-0.2.0.aab` (Play requires an AAB, not an APK)
- **versionName** `0.2.0`, **versionCode** `200`
- It's the **play** flavor: no self-updater, no `REQUEST_INSTALL_PACKAGES`,
  no direct battery-optimization request, `targetSdk 35`.
- Signed with the release/**upload** key. On first upload, accept **Play App
  Signing** (Google holds the real app-signing key; you keep uploading with
  this key). Rebuild any future AAB with a **higher versionCode**.

> Rebuild command (bump the two numbers each time):
> ```
> source android/keystore/release.env
> docker exec -e ANDROID_KEYSTORE_FILE=/project/keystore/release.keystore \
>   -e ANDROID_KEYSTORE_PASSWORD="$ANDROID_KEYSTORE_PASSWORD" \
>   -e ANDROID_KEY_ALIAS="$ANDROID_KEY_ALIAS" -e ANDROID_KEY_PASSWORD="$ANDROID_KEY_PASSWORD" \
>   -e ANDROID_VERSION_NAME="0.2.1" -e ANDROID_VERSION_CODE="201" \
>   sonarbridge-builder ./gradlew --no-daemon bundlePlayRelease
> # -> android/app/build/outputs/bundle/playRelease/app-play-release.aab
> ```

## Store listing copy

**App name** (30 char max)
```
SonarBridge
```

**Short description** (80 char max)
```
Turn your Vexilar SonarPhone into a modern fish finder and depth source.
```

**Full description** (4000 char max)
```
SonarBridge gives the Vexilar SonarPhone SP200A a modern fish-finder display
on your Android phone — and relays live depth and water temperature to the
marine chart app of your choice.

Connect straight to the SonarPhone's own Wi-Fi. SonarBridge keeps your phone's
mobile data working at the same time, so you can run charts and the sonar
together on one device.

FISH FINDER
• Scrolling waterfall with a clear bottom line and bottom-hardness shading
• Fish markers that flag strong mid-water targets
• Auto range that doesn't jump around, plus manual range control
• Live A-scope, adjustable gain, noise filter and surface clarity
• Feet or metres, Celsius or Fahrenheit, adjustable on-screen text size
• Optional "classic" colour scheme

CHART-APP BRIDGE
• Streams standard NMEA 0183 depth and temperature on your device
• Pair a compatible marine navigation app to 127.0.0.1 port 10110 (TCP) to
  show live depth — and build personal depth-contour maps as you go

MADE FOR THE WATER
• Big, readable numbers; screen stays on while open
• Shallow-water alarm
• Reconnects by itself if the signal drops
• Demo mode generates realistic sonar so you can try everything with no
  hardware

SonarBridge is an independent app. It is not made by, affiliated with, or
endorsed by Vexilar, Garmin, or Navionics. "Vexilar" and "SonarPhone" are
trademarks of their respective owners and are used here only to describe
compatibility.
```

**App icon:** `docs/store/icon-512.png` (512×512)
**Feature graphic:** `docs/store/feature-1024x500.png` (1024×500)
**Phone screenshots** (Play wants 4+ at ≥1080 px per side; these are
1440×3213): `docs/store/screenshot-1-sonar.png`,
`docs/store/screenshot-2-status.png`, `docs/store/screenshot-3-settings.png`,
`docs/store/screenshot-4-sonar.png`

**Category:** Tools (or Sports). **Contact email:** ryan.ewen@gmail.com
**Privacy policy URL:**
```
https://ryanewen.github.io/sonarphone-nmea-bridge/privacy.html
```

## App content answers (Console → App content)

- **Privacy policy:** the URL above.
- **App access:** "All functionality is available without special access."
  (No login. Demo mode exercises the whole app without the sonar hardware.)
- **Ads:** No.
- **Content rating:** run the questionnaire → category Utility/Tools, no
  objectionable content → expect **Everyone / PEGI 3**.
- **Target audience:** 18+ or 13+ (a boating utility); **not** designed for
  children. Answer "No" to the "appeals to children" follow-up.
- **Data safety:** **No data collected and no data shared.** Tick that the app
  doesn't collect any of the listed data types. (Sonar data and the NMEA feed
  stay on the device; there's no analytics or accounts.)
- **Government / financial / health:** No to all.
- **Foreground service (declaration):** the app declares a
  `connectedDevice` foreground service. Justification to paste:
  > The foreground service maintains the Wi-Fi connection to the user's sonar
  > device and streams its depth/sonar data while the app is in use on the
  > water, including when the screen is off. It is user-initiated (Connect
  > button) and stops when the user disconnects.

## Step-by-step (Internal testing)

1. **play.google.com/console → Create app.** Name `SonarBridge`, language
   English (US), type **App**, **Free**. Accept the developer-program and
   US-export declarations.
2. Left nav **Test and release → Testing → Internal testing → Create new
   release.**
3. When prompted, **opt in to Play App Signing** (recommended default).
4. **Upload** `dist/sonarbridge-0.2.0.aab`. Add release notes (e.g. "First
   internal test build."). Save.
5. Fill the **App content** section (left nav → *Monetisation setup* is skippable;
   *App content* is required) using the answers above. Also complete the
   **Store listing** (paste the copy + upload the three graphics) and set the
   app icon/feature graphic under **Store presence → Main store listing**.
6. Back in **Internal testing → Testers**, create an email list and add your
   own Google account (and anyone else). Copy the **join link**.
7. **Review release → Start rollout to Internal testing.** Internal testing
   goes live in minutes (little to no review).
8. On your phone, open the join link, accept, and install from Play.

## Going to Production later

New personal developer accounts must run a **closed test with 12+ testers for
14 days** before Production is unlocked. Internal testing above doesn't count
toward that, but it's the right place to shake the Play build out first. When
ready, promote a build from a closed-testing track and complete that
requirement, then submit for production review.
