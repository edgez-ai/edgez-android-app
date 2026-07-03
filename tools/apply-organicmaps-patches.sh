#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORGANICMAPS_DIR="$ROOT_DIR/third_party/organicmaps"
PATCH_DIR="$ROOT_DIR/third_party/organicmaps-patches"

if [ ! -d "$ORGANICMAPS_DIR/.git" ]; then
  echo "error: $ORGANICMAPS_DIR is not initialized" >&2
  exit 1
fi

if [ ! -d "$PATCH_DIR" ]; then
  echo "No Organic Maps patches found at $PATCH_DIR"
  exit 0
fi

shopt -s nullglob
patches=("$PATCH_DIR"/*.patch)

if [ "${#patches[@]}" -eq 0 ]; then
  echo "No Organic Maps patches to apply."
  exit 0
fi

for patch in "${patches[@]}"; do
  patch_name="$(basename "$patch")"
  if git -C "$ORGANICMAPS_DIR" apply --reverse --check "$patch" >/dev/null 2>&1; then
    echo "Organic Maps patch already applied: $patch_name"
  elif git -C "$ORGANICMAPS_DIR" apply --check "$patch"; then
    git -C "$ORGANICMAPS_DIR" apply "$patch"
    echo "Applied Organic Maps patch: $patch_name"
  else
    echo "error: Organic Maps patch does not apply cleanly: $patch_name" >&2
    exit 1
  fi
done
