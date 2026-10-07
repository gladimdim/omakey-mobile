#!/bin/bash
# Copy the layout spec files the app bundles from the layout studio repo.
#
#   scripts/sync-spec.sh [path/to/omakey-layout-studio]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SPEC="${1:-$ROOT/../omakey-layout-studio}/spec"
ASSETS="$ROOT/android/app/src/main/assets"

[[ -f $SPEC/keycodes.json ]] || { echo "no spec at $SPEC" >&2; exit 1; }
mkdir -p "$ASSETS/layouts"
cp "$SPEC/keycodes.json" "$ASSETS/keycodes.json"
# Mirror, don't merge: a stock layout removed or renamed in the studio must
# not linger in the app as a built-in.
for f in "$ASSETS"/layouts/*.json; do
  [[ -e $f && ! -e $SPEC/layouts/$(basename "$f") ]] && { rm "$f"; echo "Removed stale $(basename "$f")"; }
done
cp "$SPEC"/layouts/*.json "$ASSETS/layouts/"
echo "Synced $(ls "$SPEC"/layouts/*.json | wc -l) layout(s) and keycodes.json from $SPEC"
