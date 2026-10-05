#!/usr/bin/env bash
# Emit user-facing changes for the exact tag; Play needs plain text capped at 500 characters.
set -euo pipefail
TAG=${1:?Usage: release-notes.sh vX.Y.Z [play]}
PREV=$(git describe --tags --abbrev=0 "${TAG}^" 2>/dev/null || true)
RANGE=$TAG
[ -n "$PREV" ] && RANGE="${PREV}..${TAG}"
EXCLUDE='^- (bump version|merge )|\b(docs?|documentation|readme|changelog|chore|ci|workflow|lint|typecheck|devcontainer|dockerfile|compose|gitignore|deps?|dependency|dependencies|refactor|rename|cleanup|clean up|tidy|reorganize|restructure|test|tests|spec|checklist|runbook|co-?authored?)\b'
# Fail if the tag cannot be read, rather than publishing notes from another checkout.
COMMITS=$(git log --no-merges --pretty=format:'- %s' "$RANGE")
CHANGES=$(printf '%s\n' "$COMMITS" | grep -viE "$EXCLUDE" || true)
[ -n "$CHANGES" ] || CHANGES='- Improvements and fixes.'
if [ "${2:-}" = play ]; then
  printf '%s\n' "${CHANGES:0:500}"
else
  printf "## What's changed\n\n%s\n" "$CHANGES"
  if [ -n "$PREV" ]; then
    printf '\n**Full changelog**: https://github.com/%s/compare/%s...%s\n' "${GITHUB_REPOSITORY:-RyanEwen/sonarphone-nmea-bridge}" "$PREV" "$TAG"
  fi
fi
