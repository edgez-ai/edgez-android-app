#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUBMODULE_PATH="third_party/organicmaps"

cd "$ROOT_DIR"

git submodule update --init --depth 1 --filter=blob:none "$SUBMODULE_PATH"
git -C "$SUBMODULE_PATH" sparse-checkout init --no-cone
git -C "$SUBMODULE_PATH" sparse-checkout set --no-cone \
  /.gitmodules \
  /CMakeLists.txt \
  /private.h \
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

git -C "$SUBMODULE_PATH" submodule update --init --depth 1 --filter=blob:none \
  3party/BLAKE3 \
  3party/Vulkan-Headers \
  3party/boost \
  3party/expat \
  3party/fast_float \
  3party/fast_obj \
  3party/freetype/freetype \
  3party/gflags \
  3party/glaze \
  3party/harfbuzz/harfbuzz \
  3party/icu/icu \
  3party/just_gtfs \
  3party/pugixml/pugixml \
  3party/utfcpp
