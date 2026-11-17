---
name: release
description: Release a new SonarBridge Android version: notes, tag, Play draft, GitHub Release, then the deliberate publish step. Use when the user asks to release, tag or ship a version. Do not use for ESP32 firmware, which is built on demand.
---

# Release SonarBridge

Ship one `vX.Y.Z` Android release from committed notes to a Play production draft and a GitHub Release, then send the draft to review. Invoking this skill authorises its own commit, tag and push; it still stops at the two points marked **Stop**. Background is under **Releases & updates** in `docs/development.md`.

1. **Stop** unless the working tree is clean and `HEAD` equals `origin/main` after `git fetch`. Report what is dirty or diverged.
2. Choose the version. Read the latest tag (`git describe --tags --abbrev=0`) and confirm with `bash scripts/release-version.sh vX.Y.Z` that the new tag is valid and its versionCode is higher.
3. Write `release-notes/X.Y.Z.md` (en-US) and the six siblings `X.Y.Z.<identity>.md` for es-ES, fr-FR, de-DE, zh-CN, pt-BR and ja-JP. User-facing bullets only, from the changes since the last tag; no heading and no disclosure line, which CI adds. Run `npm run check:release-notes`.
4. **Stop** and show the en-US notes and the translations for approval. Record who approved them.
5. Commit only the notes files with the subject `Add release notes for vX.Y.Z`. Android has no version file to bump; the tag is the version.
6. Create an annotated tag on that commit, `git tag -a vX.Y.Z -m "SonarBridge X.Y.Z"`, and push the branch and the tag together: `git push --atomic origin main vX.Y.Z`.
7. Watch the Release workflow for the tag (`gh run watch`). It runs the gate, uploads the bundle to Play production as a draft with every locale's notes, then publishes the GitHub Release "SonarBridge X.Y.Z" with the Play-signed APK. If the Play upload succeeded but the GitHub step failed, do not re-tag or re-upload; run **Replace GitHub APK** (`play-apk.yml`) for the tag.
8. Verify the GitHub Release (title, notes, `sonarbridge-X.Y.Z-play.apk`, marked Latest) and run **Play Check** to confirm the production draft holds the new versionCode.
9. Send the draft to review: `gh workflow run play-publish.yml -f version=vX.Y.Z -f publish_draft=true`, and watch it. It refuses a draft whose versionCode does not match the tag.
10. Post-release: confirm an installed copy updates from Play or the GitHub APK; update the product's website page or the promo catalog only when features, screenshots or the listing changed.

Report: the version and versionCode, who approved the notes, the commit and tag, the Release URL, the Play draft and publish run results, and anything left for the maintainer.
