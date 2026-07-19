# Google Play publishing guide

Everything you need to publish SonarBridge to the **Internal testing** track,
plus copy to paste into each Console field. The build side is done — this is
the Console (web-only) side.

## The artifact to upload

- **File:** `dist/sonarbridge-0.2.2.aab` (Play requires an AAB, not an APK)
- **versionName** 0.2.2, **versionCode** 202
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
>   -e ANDROID_VERSION_NAME="0.2.3" -e ANDROID_VERSION_CODE="203" \
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
**Phone screenshots** (Play's phone slot; 4+ at ≥1080 px/side, these are
1440×3213 portrait / 3213×1440 landscape):
`docs/store/phone-1-sonar.png`, `docs/store/phone-2-sonar-landscape.png`,
`docs/store/phone-3-status.png`, `docs/store/phone-4-settings.png`

**Tablet screenshots** (Play's tablet slot; 1200×2000 portrait /
2000×1200 landscape): `docs/store/tablet-1-sonar.png`,
`docs/store/tablet-2-sonar-landscape.png`, `docs/store/tablet-3-status.png`,
`docs/store/tablet-4-settings.png`

Both include a landscape shot showing the left nav rail. All captured in demo
mode with a clean status bar.

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
4. **Upload** `dist/sonarbridge-0.2.2.aab`. Add release notes (e.g. "First
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

## CI auto-publish (Fastlane supply)

Once the app exists on Play (first release done manually), CI can upload the
AAB and sync the whole listing from the repo. The store content lives in
`fastlane/metadata/android/en-US/` (title, short/full description, changelog,
`images/` icon + feature graphic + `phoneScreenshots/` + `tenInchScreenshots/`).
Edit those files, push, and `.github/workflows/play-publish.yml` pushes them to
Play — no more clicking through the Console.

**Triggers:** a `v*` tag → full release (build AAB + upload to Internal +
sync listing). Manual **Run workflow** → pick the track, or tick
`listing_only` to sync just the text/graphics/screenshots with no new build.

**One-time setup (only you can do this):**

1. **Play Console → Setup → API access.** Create/link a Google Cloud project.
2. In that project (Google Cloud Console → IAM → Service Accounts), create a
   **service account**, then create a **JSON key** for it and download it.
3. Back in **Play Console → Users and permissions → Invite new users**, invite
   the service account's email and grant it access to this app with at least
   *Release to testing tracks* and *Manage store presence* (or Admin for
   simplicity).
4. In the GitHub repo → **Settings → Secrets and variables → Actions**, add a
   secret **`PLAY_SERVICE_ACCOUNT_JSON`** = the full contents of that JSON key.
   (The `ANDROID_KEYSTORE_*` secrets are already set from the sideload release.)

Until step 1's first *manual* release is live and the secret is set, the
workflow will fail — that's expected. After that, tagging `vX.Y.Z` publishes.

> versionCode is derived from the tag: `major*10000 + minor*100 + patch`
> (so `v0.2.3` → 203, continuing past the manual 200–202). Keep minor/patch
> under 100.

> Aspect-ratio note: Play caps screenshot side ratio at 2:1. The full-height
> phone shots are ~2.23:1; if the API rejects them, crop to ≤2:1 (e.g.
> 1440×2880) and re-push with `listing_only`.

## Going to Production later

New personal developer accounts must run a **closed test with 12+ testers for
14 days** before Production is unlocked. Internal testing above doesn't count
toward that, but it's the right place to shake the Play build out first. When
ready, promote a build from a closed-testing track and complete that
requirement, then submit for production review.
