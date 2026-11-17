# Google Play listing

Scope: what the Play listing needs beyond its text: limits, claims the text makes, Console-only answers, and open questions.
Governs: `store-assets/play/`, and the fastlane tree CI generates from it with `scripts/play-metadata.mjs`.

The listing text is `listings.json` (title, short and full description for all nine listing locales; `en-CA` and `fr-CA` copy `en-US` and `fr-FR`). The images are `icon.png`, `feature-graphic.png` and `screenshots/phone/` and `screenshots/tablet/`, one copy each. Edit them here, never in Play Console and never in a generated `fastlane/` tree. `npm run check:listings` validates the text; the Publish to Google Play workflow uploads it with every release or with `listing_only`. Release notes are not part of the listing; they live in `release-notes/` (see `docs/development.md`).

## Limits

| Field | Play limit | Longest now |
| --- | --- | --- |
| `title` | 30 characters | 11 (en-US) |
| `shortDescription` | 80 characters | 73 (fr-FR) |
| `fullDescription` | 4,000 characters | 1,845 (de-DE) |
| Release notes | 500 characters per locale | checked by `npm run check:release-notes` |

Graphics: icon 512 x 512, feature graphic 1024 x 500. Phone screenshots are 1440 x 3213 portrait and 3213 x 1440 landscape; tablet screenshots are 1200 x 2000 and 2000 x 1200. All are captured once, in English, in demo mode with a clean status bar, and serve every listing locale. Both sets include a landscape shot that shows the left navigation rail.

## Claims to keep true

When a change touches one of these, revisit the listing wording in every locale.

- **No data collected or shared:** sonar data and the NMEA feed stay on the phone; no analytics, no accounts.
- **Keeps mobile data working** while the phone is joined to the sonar's Wi-Fi (the local-only network request in `BridgeService`).
- **NMEA on `127.0.0.1` port `10110` (TCP)** for a chart app on the same phone.
- **Demo mode** exercises the whole app without hardware.
- **Supported sonar:** the SonarPhone SP200A; other models are not claimed.
- **Nine languages**, matching the app's interface; translations are an initial AI-assisted pass.
- **Independence:** not made by, affiliated with or endorsed by Vexilar, Garmin or Navionics; their names describe compatibility only.

## Console-only fields

- **App name and default language:** SonarBridge, English (United States).
- **Category:** Tools. **Price:** free.
- **Contact email:** the company contact (BRAND-9).
- **Privacy policy URL:** `https://www.dynamic-solutions.ca/privacy/sonarbridge`, served by the company website.
- **App access:** all functionality is available without special access. Reason: there is no login, and demo mode covers the whole app without the sonar.
- **Ads:** none.
- **Content rating:** Utility/Tools with no objectionable content; expect Everyone / PEGI 3.
- **Target audience:** 13+ or 18+; not designed for children, so answer "No" to "appeals to children". Reason: it is a boating utility.
- **Data safety:** no data collected and no data shared. Reason: nothing leaves the phone except the NMEA stream to another app on the same phone.
- **Government, financial or health features:** none.
- **Foreground service declaration** (`connectedDevice`), text to paste:
  > The foreground service maintains the Wi-Fi connection to the user's sonar device and streams its depth/sonar data while the app is in use on the water, including when the screen is off. It is user-initiated (Connect button) and stops when the user disconnects.

## Open questions

- Play caps a screenshot's side ratio at 2:1, and the full-height phone shots are about 2.23:1. They have been accepted so far; if the API ever rejects them, crop to 2:1 (for example 1440 x 2880) and re-run the workflow with `listing_only`.
- The privacy policy URL changes when the policy moves to the company site; update it here and in Play Console together.
- The listing translations still need a native-speaker review.
