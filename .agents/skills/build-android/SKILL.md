---
name: build-android
description: Build the SonarBridge GitHub-flavor Android debug APK in the repository's Docker builder. Use for Android builds, APK generation, or build verification in this repository.
---

# Build the Android debug APK

Read the **Checks** and **Build** sections of `docs/development.md` and work from the repository root.

- Run `npm run build:android`. `scripts/gradle` uses `sonarbridge-builder` when it is running (start it if it exists but is stopped), and otherwise a disposable, memory-capped container from the `sonarbridge-build` image.
- Do not use `npm run verify:android` (both flavors) when only the sideloadable development APK is needed.
- Do not hide the Gradle exit status behind a log-truncation pipeline.
- Report success only when Gradle succeeds and
  `android/app/build/outputs/apk/github/debug/app-github-debug.apk` exists with
  a newer modification time than before the build.
