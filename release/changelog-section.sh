#!/usr/bin/env bash
# Prints the release notes for VERSION from CHANGELOG.md: the lines after the heading "## VERSION"
# (exactly, no "v"), up to the next line starting "## " ("###" subheadings stay in), with leading
# and trailing blank lines trimmed. Exits 1 if the heading is missing or the section is empty.
# Ferb's scripts/changelog-section.sh reads its CHANGELOG the same way.
# Usage: release/changelog-section.sh VERSION [CHANGELOG]
set -euo pipefail
VERSION=${1:?usage: changelog-section.sh VERSION [CHANGELOG]}
FILE=${2:-$(cd "$(dirname "$0")/.." && pwd)/CHANGELOG.md}
[ -f "$FILE" ] || { echo "No $FILE" >&2; exit 1; }
grep -qxF "## $VERSION" "$FILE" || { echo "No \"## $VERSION\" section in $FILE" >&2; exit 1; }
BODY=$(awk -v h="## $VERSION" '
  $0 == h { f = 1; next }
  f && /^## / { exit }
  f { line[++n] = $0 }
  END {
    first = 1; while (first <= n && line[first] ~ /^[[:space:]]*$/) first++
    last = n; while (last >= first && line[last] ~ /^[[:space:]]*$/) last--
    for (i = first; i <= last; i++) print line[i]
  }' "$FILE")
[ -n "$BODY" ] || { echo "The \"## $VERSION\" section in $FILE is empty" >&2; exit 1; }
printf '%s\n' "$BODY"
