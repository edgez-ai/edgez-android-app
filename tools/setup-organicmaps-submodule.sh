#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_PATH="third_party/organicmaps"

cd "$ROOT_DIR"

git submodule update --init --depth 1 --filter=blob:none "$SUBMODULE_PATH"
git -C "$SUBMODULE_PATH" sparse-checkout init --no-cone
git -C "$SUBMODULE_PATH" sparse-checkout set --no-cone \
  /CMakeLists.txt \
  /3party/ \
  /android/sdk/ \
  /cmake/ \
  /libs/ \
  /platform/ \
  /tools/ \
  /generator/ \
  '/data/*.txt' \
  '/data/*.json' \
  '/data/*.csv' \
  '/data/*.html' \
  '/data/*.bin' \
  '/data/*.dat' \
  '/data/*.mwm' \
  /data/conf/ \
  /data/countries-strings/ \
  /data/fonts/ \
  /data/organic_maps_emoji/ \
  /data/sound-strings/ \
  /data/strings/ \
  /data/styles/ \
  /data/symbols/ \
  /data/symbols-svg/ \
  /data/vulkan_shaders/
