#!/usr/bin/env bash
# Prints the next beta tag after the newest v* release, e.g. v1.0.0-beta.5 -> v1.0.0-beta.6.
set -euo pipefail
last=$(gh release list --limit 50 --json tagName --jq '.[].tagName' | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+(-beta\.[0-9]+)?$' | sort -V | tail -1 || true)
if [[ -z "$last" ]]; then echo "v1.0.0-beta.1"; exit 0; fi
if [[ "$last" =~ ^(v[0-9]+\.[0-9]+\.[0-9]+)-beta\.([0-9]+)$ ]]; then
  echo "${BASH_REMATCH[1]}-beta.$(( BASH_REMATCH[2] + 1 ))"
else
  # last was a final release vX.Y.Z -> next patch beta
  IFS=. read -r major minor patch <<<"${last#v}"
  echo "v${major}.${minor}.$((patch + 1))-beta.1"
fi
