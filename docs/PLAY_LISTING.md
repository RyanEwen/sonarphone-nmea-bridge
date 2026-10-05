# Google Play publishing guide

Everything you need to publish SonarBridge to the **Internal testing** track,
plus copy to paste into each Console field. The build side is done — this is
the Console (web-only) side.

## Release state and migration (2026-10-05)

Live API inspection confirmed versionCode 203 (0.2.3) completed on production,
alpha and internal before this rollout. Production is available; the older
notes about a blocked production launch were stale.

The migration release v0.2.4 is signed with the existing legacy key and stays
GitHub's latest so older updaters can receive its notice. The Play-signed
v0.2.5 APK (versionCode 205) is published alongside it with direct download
links. Its bundle was uploaded once to internal and is promoted to production
using that existing upload, without changing testing tracks or rebuilding.

Read current tracks with `play-publish` using `inspect_only=true`; use its
`promote_version_code` input to promote an already completed testing release.
The production promotion validates the edit before committing it and rejects
missing, reused or older codes. Track status does not by itself prove when
Google's review or public-store propagation finishes.

## The artifact to upload (manual fallback)

- **File:** `dist/sonarbridge-0.2.3.aab` (Play requires an AAB, not an APK)
- **versionName** 0.2.3, **versionCode** 203
- It's the **play** flavor: no self-updater, no `REQUEST_INSTALL_PACKAGES`,
  no direct battery-optimization request, `targetSdk 36` (Play's target-API
  floor from Aug 30, 2026; `minSdk` stays 29, so older phones/tablets are
  unaffected).
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

**Triggers:** regular `v*` tags call this reusable Play upload lane from
`release.yml`, then publish the verified Google-signed APK on GitHub. The final
legacy migration tag is the exception and uploads no Play bundle. Manual
**Run workflow** takes an existing `vX.Y.Z` tag and chosen track, or use
`listing_only` to sync text/graphics/screenshots without a bundle.
See [the release runbook](../android/README-dev.md#releases--updates) for rollout
order and the required signing fingerprint variable. The default build/upload track remains internal testing. Promote the completed
bundle to production using `promote_version_code`, without re-uploading it.

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

**Done 2026-07-30.** Notes from doing it, in case the key ever needs replacing:
grant the service account **no** GCP IAM role (roles there govern GCP
resources, not Play); the app-level Console permissions are what matter (view
app info, manage store presence, release to testing tracks, release to
production). Grants take a few minutes to propagate, and a missing grant shows
up as `403 PERMISSION_DENIED` on `edits.insert` while token minting still
succeeds — a disabled Android Publisher API gives a different, explicit
"has not been used in project" error, so the two are easy to tell apart.

> versionCode is derived from the tag: `major*10000 + minor*100 + patch`
> (so `v0.2.3` → 203, continuing past the manual 200–202). Keep minor/patch
> under 100.

> Aspect-ratio note: Play caps screenshot side ratio at 2:1. The full-height
> phone shots are ~2.23:1; if the API rejects them, crop to ≤2:1 (e.g.
> 1440×2880) and re-push with `listing_only`.

## App signing and integrity (Console → Protected with Play)

The Console page once called *App integrity* is now *Protected with Play*. It
holds three unrelated features; only the first one is set up, deliberately.

**Play app signing: on since 2026-07-18**, accepted on the first 0.2.0 upload.
Google generated and holds the app signing key (its certificate is
`CN=Android, O=Google Inc.`, valid to 2056). You hold only the *upload* key.

| Key | Lives in | Signs |
|---|---|---|
| App signing (Google's) | Google, not exportable | what users install from Play |
| Upload (yours) | `android/keystore/release.keystore` | AABs sent to Play, and the final legacy migration APK |

Upload key: alias `sonarbridge`, `CN=SonarBridge, O=Rewen`, RSA 2048, created
2026-07-17, valid to 2056, SHA-256
`D9:BE:CA:DD:57:3C:DE:44:24:C7:CC:92:A0:88:F7:A7:4F:EC:23:17:61:D3:14:D1:ED:A2:60:1C:C4:BE:F3:C9`.

> **Back the upload key up.** The only copies are that gitignored file and the
> write-only `ANDROID_KEYSTORE_BASE64` GitHub secret, which cannot be read
> back. Lose the working tree and you are filing an upload-key reset request
> with Play support. Copy `release.keystore` **and** `release.env` somewhere
> durable.

**Current distribution decision:** publish Google's signed universal APK on
GitHub instead of building a second public APK. Both channels then have the
same package, signing certificate and versionCode. Downloading from GitHub
remains supported, with manual APK updates and no in-app APK installer.

The old GitHub APK uses the upload key with the same package ID, so Android
cannot update it to Google's signer or install the new app beside it. Prepare
one final legacy-signed APK with migration guidance first. It retains settings
when installed over old GitHub releases. Users then record settings and save
raw logs, disconnect, uninstall, install from GitHub or Play and reconfigure.
Uninstalling clears settings and app files. Existing Play installations do not
need this migration. Full steps are in the release runbook linked above.

**Trust configuration:** set GitHub repository variable
`PLAY_SIGNING_CERTIFICATE_SHA256` from the app-signing certificate fingerprint
on this Console page, or verify it against the authoritative metadata for an
existing bundle using `inspect-play-state`. The exporter checks that trusted fingerprint against
Google's generated-APK metadata and `apksigner` output, plus exact package,
versionCode and versionName. The upload-key fingerprint above is incompatible
and must not be used. A `.der` certificate file is unnecessary; the textual
SHA-256 fingerprint suffices. Do not derive the trust setting automatically
from the APK being downloaded.

**Play Integrity API: deliberately not enabled.** Do not relitigate this
without a backend. It issues a token that must be verified server-side, and
this app has no backend to verify integrity tokens. Client-side enforcement is cautioned against by
Google and is removable by the attacker it targets. There is also nothing to
protect: free app, no accounts, no purchases, no server API. Manual GitHub
installs are intentionally supported; enforcing Play licensing could prevent
those users from using the app. Revisit only if SonarBridge ever grows a
backend worth defending.

**Automatic protection: available, deliberately skipped.** It is one click
(release flow → *app bundle enhancements*, or the Protected with Play page),
needs no code, works offline, is reversible per release, and the
prerequisites already hold (Play App Signing, AAB, minSdk 29 over its floor
of 24). What it does is prompt users who obtained the app from an unofficial
source to install it from Play, which is worth little here: the official free
APK on GitHub is an intentional alternative download. Protection injected
into the Play artifact would also reach the GitHub APK now that both channels
use that artifact, conflicting with the supported manual-install flow.

## Production promotion

Production access is already enabled for this app. Use `promote_version_code`
in `play-publish` to promote the completed internal build without uploading it
again. Inspect the tracks afterward; distinguish the committed production
release from Google's review and public-store availability. Preserve alpha
and internal tracks unless a separate request calls for retiring them.
