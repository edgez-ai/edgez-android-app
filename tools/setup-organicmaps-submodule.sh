#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORGANICMAPS_DIR="$ROOT_DIR/third_party/organicmaps"
BOOST_PATH="$ORGANICMAPS_DIR/3party/boost"

cd "$ROOT_DIR"

if [ ! -d "$ORGANICMAPS_DIR" ]; then
  echo "error: $ORGANICMAPS_DIR is missing" >&2
  echo "Organic Maps source is expected to be tracked by the root project." >&2
  exit 1
fi

submodules=(
  third_party/organicmaps/3party/BLAKE3 \
  third_party/organicmaps/3party/CMake-MetalShaderSupport \
  third_party/organicmaps/3party/Vulkan-Headers \
  third_party/organicmaps/3party/boost \
  third_party/organicmaps/3party/expat \
  third_party/organicmaps/3party/fast_float \
  third_party/organicmaps/3party/fast_obj \
  third_party/organicmaps/3party/freetype/freetype \
  third_party/organicmaps/3party/gflags \
  third_party/organicmaps/3party/glfw \
  third_party/organicmaps/3party/glm \
  third_party/organicmaps/3party/glaze \
  third_party/organicmaps/3party/googletest \
  third_party/organicmaps/3party/harfbuzz/harfbuzz \
  third_party/organicmaps/3party/icu/icu \
  third_party/organicmaps/3party/imgui/imgui \
  third_party/organicmaps/3party/just_gtfs \
  third_party/organicmaps/3party/pugixml/pugixml \
  third_party/organicmaps/3party/utfcpp \
  third_party/organicmaps/tools/kothic \
  third_party/organicmaps/tools/osmctools \
  third_party/organicmaps/tools/python/twine
)

git submodule sync --recursive || true
if ! git submodule update --init --recursive "${submodules[@]}"; then
  echo "warning: root-owned Organic Maps submodules are not available from HEAD yet." >&2
  echo "warning: falling back to recursive update inside already-present dependency checkouts." >&2
  for submodule in "${submodules[@]}"; do
    if git -C "$submodule" rev-parse --git-dir >/dev/null 2>&1; then
      git -C "$submodule" submodule update --init --recursive
    fi
  done
fi

if [ -d "$BOOST_PATH/libs/spirit/include/boost/spirit" ] && [ ! -f "$BOOST_PATH/libs/spirit/include/boost/spirit/include/qi.hpp" ]; then
  mkdir -p "$BOOST_PATH/libs/spirit/include/boost/spirit/include"
  cat >"$BOOST_PATH/libs/spirit/include/boost/spirit/include/qi.hpp" <<'EOF'
#include <boost/spirit/home/qi.hpp>
EOF
fi

rm -rf "$BOOST_PATH/boost"
mkdir -p "$BOOST_PATH/boost"
for INCLUDE_BOOST_DIR in "$BOOST_PATH"/libs/*/include/boost "$BOOST_PATH"/libs/numeric/*/include/boost; do
  [ -d "$INCLUDE_BOOST_DIR" ] || continue
  find "$INCLUDE_BOOST_DIR" -mindepth 1 -maxdepth 1 -exec sh -c '
    boost_path="$1"
    shift
    for entry do
      target="$0/boost/$(basename "$entry")"
      if [ -d "$entry" ]; then
        mkdir -p "$target"
        find "$entry" -mindepth 1 -maxdepth 1 -exec sh -c '"'"'
          boost_path="$1"
          target_dir="$2"
          shift 2
          for child do
            child_target="$target_dir/$(basename "$child")"
            [ -e "$child_target" ] || ln -s "../../${child#"$boost_path"/}" "$child_target"
          done
        '"'"' sh "$boost_path" "$target" {} +
      elif [ ! -e "$target" ]; then
        ln -s "../${entry#"$0"/}" "$target"
      fi
    done
  ' "$BOOST_PATH" "$BOOST_PATH" {} +
done
