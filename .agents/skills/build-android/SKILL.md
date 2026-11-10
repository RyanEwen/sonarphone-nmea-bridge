---
name: build-android
description: Build the SonarBridge GitHub-flavor Android debug APK in the repository's Docker builder. Use for Android builds, APK generation, or build verification in this repository.
---

# Build the Android debug APK

Read the **Build** section of `android/README-dev.md` and follow it from the
repository root.

- Use the environment's configured container CLI when one is available.
- Reuse `sonarbridge-builder`; start it if it exists but is stopped, and create
  it only if it is absent.
- Run `./gradlew assembleGithubDebug` in the builder. Do not use the aggregate
  `assembleDebug` task when only the sideloadable development APK is needed.
- Do not hide the Gradle exit status behind a log-truncation pipeline.
- Report success only when Gradle succeeds and
  `android/app/build/outputs/apk/github/debug/app-github-debug.apk` exists with
  a newer modification time than before the build.
