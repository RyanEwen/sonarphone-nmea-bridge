# Android localization: implementation record (2026-10-21)

A dated record of how the initial localization was verified. It is not kept current; the reusable rules are in [../localization.md](../localization.md).

## Verified implementation and limits

There are 98 source strings in ten complete resource sets: default English, explicit English (needed for ordered Android resource matching), Canadian English, Spanish, French, Canadian French, German, Simplified Chinese (Hans-qualified), Brazilian Portuguese wording and Japanese. Package language declarations and native picker labels retain the nine requested region identities. The default and Canadian wording pairs are validated for exact equality.

API 29, 32 and 33 run the same meaningful Robolectric checks in both flavors: 28 cases per flavor, 56 total, with zero failures. They exercise the real Material picker listeners and activity recreation, draft text such as `-` and `1.`, current tab, original persisted settings, ordinary saves after a no-op selection, both directions between Follow device and matching explicit English without forcing recreation, platform migration without replacing an existing preference, cleared/restored/malformed preferences, cold Service creation before any Activity, notification channel properties and ID, actual compiled-resource matching with ordered LocaleLists, Chinese scripts, regional time with seconds in both clock modes and stable NMEA formatting. They do not open sonar sockets or establish a real foreground-service/hardware connection. BridgeState identity and absence of start-service intents prove the tested locale paths do not request reconnection, rather than proving on-water continuity.

The locale picker suppresses Settings autosave only when the requested effective locale configuration differs; Bundle state restores the exact draft. Selecting a matching explicit language can persist the preference without recreation, so the picker button refreshes immediately and ordinary autosave remains enabled. Notification permission is not requested again during recreation. The existing ordinary tab-leave and pause save behavior is retained. There is no second notification channel, notification cancellation, foreground-service restart or sonar command in the locale path.

The public/native Android framework and resource matcher were exercised through Robolectric, not a physical device or emulator. Physical API 29/32/33+ lifecycle behavior, screen-reader operation, text clipping, long labels, font scaling, real notification updates, upgrade/restore across OS versions and uninterrupted sonar/Navionics operation remain unperformed. Initial AI-assisted translations require human language review. Android/system UI, remote content, product names, user SSIDs and raw diagnostic logs keep their supplied language. Chinese support is Simplified only.

Both debug APKs compiled with unchanged version `0.1-dev`/code `1`; both verify with the existing pinned Android Debug certificate. No release credentials, version bump, installs, phone or ADB operations were used. Reports, APKs, signatures, package language dumps, build output tail and per-file source hashes are under the gitignored `dist/android-localization/` directory. The source hash manifest covers all 45 Android source/test/resource and Gradle input files and matches the exact staged build. The SHA-256 of that manifest is `e92067a9f3c5f4e19090bb9002541def7885a06f6e29a86ddc070319df06bb5e`.

## Reproduce without touching the central checkout

The existing warm `sonarbridge-builder` on this host is x86_64. Inspect its architecture and `/project/.gradle/gradle.properties` before selecting AAPT2. This builder uses `/opt/android-sdk/build-tools/35.0.0/aapt2`; `/opt/android-tools/aapt2` in the older arm64 runbook does not exist here. The canonical laptop papercuts log records this verified correction. Do not replace/rebind the container or build this feature under its central `/project` source mount.

Copy the feature Android tree (excluding `.gradle`, `build`, `app/build` and `keystore`) into an owned, unique `/tmp` directory inside the existing container. Copy the pinned debug keystore from `/project/keystore/debug.keystore` only into that temporary stage, without printing its contents. Reuse `/project/.gradle` as the existing Gradle cache and the existing SDK. With the staged directory as the working directory, the verified command is:

```sh
./gradlew -Pandroid.aapt2FromMavenOverride=/opt/android-sdk/build-tools/35.0.0/aapt2 --max-workers=1 testGithubDebugUnitTest testPlayDebugUnitTest assembleGithubDebug assemblePlayDebug
```

Run `python3 android/scripts/check-localization.py` from the feature repository root for key, placeholder, picker/platform and Canadian parity. Both test dependencies were checked against current Maven metadata before addition: JUnit 4.13.2 and Robolectric 4.17. The test heap is 1 GiB per existing SDK-sandbox convention. Runtime dependencies are unchanged; Material 1.12.0 supplies AppCompat 1.6.1. Copy APKs and reports back to the feature worktree, compare staged source hashes, then clean only the owned temporary stage. Existing debug evidence may be reused for an unchanged source snapshot; production releases still use the tagged publishing workflow.

## Central WiFi permission patch integration

The central checkout contained an independent, uncommitted permission fix when this branch started. It remains outside this diff. Integration overlaps MainActivity imports, notification-permission gating, creation/save hooks, BridgeService and the main manifest; the independent test dependencies/configuration should be deduplicated. Retain `WifiConnectionPermissionRequest.restoreState`/`saveState`, `onPostResume` denial handling, the connection request wrapper, permissions and service preflight checks alongside this branch's tab/draft hooks and language-only save guard. Neither feature may replace the other's hooks. The central runbook is newer than the branch's tracked runbook; do not replace it with this worktree's older copy.

The central-only WiFi helper's Nearby devices denial title, explanation and Open settings action are not in this HEAD-based text inventory. Translate those first-party labels with the same resources during integration and run its existing pending-Connect permission/recreation tests together with this branch's localization cases. This branch cannot claim that unintegrated permission flow was tested.

## Independent review

A user-requested read-only GPT-6-Astra review at low reasoning found two P2 defects: matching-language selections could leave autosave suppressed when the platform did not recreate the Activity, and the regional timestamp format omitted seconds. The existing feature owner corrected both and added focused regressions. Astra accepted the corrections with no remaining actionable findings in them. The owner reran both flavors: 56 tests passed and both debug APKs built from the matching staged source. The review record and complete build log are in `dist/android-localization/astra-review/`; the main evidence folder contains the refreshed APKs, signatures, hashes and reports. Device and hardware limits above still apply.
