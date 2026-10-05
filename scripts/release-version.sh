#!/usr/bin/env bash
# Validate a release tag and print the shared package version for GitHub and Play.
set -euo pipefail
TAG=${1:?Usage: release-version.sh vX.Y.Z}
[[ "$TAG" =~ ^v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] || { echo 'Use vX.Y.Z with minor/patch below 100' >&2; exit 1; }
IFS='.' read -r MAJ MIN PAT <<< "${TAG#v}"
[ "$((10#$MAJ))" -le 210000 ] || exit 1
CODE=$((10#$MAJ*10000 + 10#$MIN*100 + 10#$PAT))
[ "$CODE" -gt 0 ] && [ "$CODE" -le 2100000000 ] || exit 1
printf 'tag=%s\nname=%s\ncode=%s\n' "$TAG" "${TAG#v}" "$CODE"
