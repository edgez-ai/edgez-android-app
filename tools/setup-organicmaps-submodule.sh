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
  3party/glm \
  3party/glaze \
  3party/harfbuzz/harfbuzz \
  3party/icu/icu \
  3party/just_gtfs \
  3party/pugixml/pugixml \
  3party/utfcpp

BOOST_PATH="$SUBMODULE_PATH/3party/boost"
git -C "$BOOST_PATH" submodule update --init --depth 1 --filter=blob:none \
  libs/algorithm \
  libs/any \
  libs/array \
  libs/assert \
  libs/bind \
  libs/circular_buffer \
  libs/concept_check \
  libs/config \
  libs/container \
  libs/container_hash \
  libs/conversion \
  libs/core \
  libs/date_time \
  libs/detail \
  libs/exception \
  libs/function \
  libs/function_types \
  libs/functional \
  libs/geometry \
  libs/graph \
  libs/headers \
  libs/integer \
  libs/io \
  libs/iterator \
  libs/lexical_cast \
  libs/math \
  libs/move \
  libs/mp11 \
  libs/mpl \
  libs/multiprecision \
  libs/numeric/conversion \
  libs/optional \
  libs/phoenix \
  libs/polygon \
  libs/pool \
  libs/predef \
  libs/preprocessor \
  libs/proto \
  libs/qvm \
  libs/range \
  libs/rational \
  libs/regex \
  libs/serialization \
  libs/smart_ptr \
  libs/spirit \
  libs/static_assert \
  libs/throw_exception \
  libs/tokenizer \
  libs/tuple \
  libs/type_traits \
  libs/typeof \
  libs/unordered \
  libs/utility \
  libs/variant \
  libs/variant2

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
