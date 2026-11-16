# Android localization

Scope: how the Android app's interface text and Play listing are localized, and what must stay untranslated.
Governs: `android/app/src/main/res/values*/`, `android/app/src/main/res/xml/locales_config.xml`, `AppLanguage.kt`, `RegionalFormat.kt`, `android/scripts/check-localization.py`, `store-assets/play/listings.json`, `release-notes/`.

Android only. English (United States) is the complete source and fallback. Supported selections are en-US, en-CA, es-ES, fr-FR, fr-CA, de-DE, zh-CN (Simplified), pt-BR and ja-JP. Canadian selections reuse English/French wording: there is no `values-en`, `values-en-rCA` or `values-fr-rCA` folder, because Android treats the default catalog as English during locale negotiation and falls back from fr-CA to `values-fr` (D-068; `AppLanguageTest` covers both). Native labels identify the choices. Follow device clears the override and uses the ordered phone language list. Explicit Traditional Chinese scripts/regions must skip Simplified Chinese and try the next preference.

Use Android string resources, localeConfig and AppCompatDelegate with the existing AppCompat 1.6.1 supplied by Material 1.12.0. No dependency upgrade or XML layouts. On API 29-32, one private cfg.ui_language preference is restored before activity creation and also supplies a localized service resource context. On API 33+, platform applicationLocales owns the preference. Migrate the old key only when platform applicationLocales is empty, and remove it after publication; never overwrite a platform selection. Invalid or unknown stored values follow the device. Cold service startup must work without an Activity.

Language changes use activity recreation. Save current tab and draft controls in instance state and avoid requesting notification permission again on recreation. Never stop/restart BridgeService or send a sonar command for language changes. Refresh the existing notification and channel name while retaining channel ID, importance, user choices and foreground notification ID. Central has an independent pending Wi-Fi permission patch: integrate its restore/save hooks alongside the localization draft state; do not replace them.

Read-only numbers and time follow the phone regional locale independently of app wording. Numeric edit fields retain the existing dot-decimal formatter/parser and explain this limitation. Keep protocol Locale.US, NMEA bytes, checksums, state IDs, SSIDs, credentials, preference keys/values, paths, URLs and diagnostic logs stable. Map phase identity to text at display boundaries. Supplied user, operating-system, product and remote text retains its original wording.

Translate all first-party Android settings, status, notifications, Canvas labels, download/migration guidance and accessibility descriptions. Initial translations are AI-assisted and need human language review. No native-speaker, device, clipping or on-water validation is implied by automated checks.

Validation covers resource and placeholder parity, native resource fallback including Chinese scripts, selection/default/clear/restore/malformed aliases, cold service contexts, regional formatting, recreation drafts/current tab, both debug APK flavors. The initial implementation validation excluded phone, ADB, installs, launches, hardware connections and publishing. Releases follow the repository’s separate tagged publishing workflow.

References: [Android app languages](https://developer.android.com/guide/topics/resources/app-languages), [Android multilingual resources](https://developer.android.com/guide/topics/resources/multilingual-support). AppCompat on API 32 and earlier applies to Activity contexts; service resources need an explicit configuration context.

## Play store languages

The listing text for all nine identities is `store-assets/play/listings.json`; `en-CA` and `fr-CA` are `copyFrom` aliases of `en-US` and `fr-FR`, expanded when CI generates fastlane's tree. Release notes are one file per wording catalog in `release-notes/`, with the same aliases. Screenshots and graphics are English only and serve every listing locale. Listing translations are an initial AI-assisted pass.

`scripts/play-status.mjs` reads the live listing locales and prints normalized SHA-256 digests for each title, short description and full description; compare them with the source after publishing to confirm all nine listings were accepted. Its temporary edit is always discarded.

The verification record for the initial implementation (2026-10-21) is in [notes/2026-10-21-android-localization.md](notes/2026-10-21-android-localization.md).
