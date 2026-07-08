#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${ROOT_DIR}/native/edgez-libp2p"
OUTPUT_DIR="${ROOT_DIR}/app/src/main/jniLibs"

if ! command -v go >/dev/null 2>&1; then
  echo "go must be available on PATH"
  exit 1
fi

find_ndk_dir() {
  if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    echo "${ANDROID_NDK_HOME}"
    return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" ]]; then
    echo "${ANDROID_NDK_ROOT}"
    return
  fi

  local sdk_dir=""
  if [[ -n "${ANDROID_HOME:-}" ]]; then
    sdk_dir="${ANDROID_HOME}"
  elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
    sdk_dir="${ANDROID_SDK_ROOT}"
  elif [[ -d "${HOME}/Library/Android/sdk" ]]; then
    sdk_dir="${HOME}/Library/Android/sdk"
  fi

  if [[ -n "${sdk_dir}" && -d "${sdk_dir}/ndk" ]]; then
    find "${sdk_dir}/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1
  fi
}

NDK_DIR="$(find_ndk_dir)"
if [[ -z "${NDK_DIR}" || ! -d "${NDK_DIR}" ]]; then
  echo "Unable to find Android NDK. Set ANDROID_NDK_HOME or install an NDK in the Android SDK."
  exit 1
fi

export GOCACHE="${GOCACHE:-${ROOT_DIR}/.gocache}"
export GOMODCACHE="${GOMODCACHE:-${ROOT_DIR}/.gomodcache}"
HOST_TAG="darwin-x86_64"
case "$(uname -s)" in
  Linux) HOST_TAG="linux-x86_64" ;;
esac

TOOLCHAIN="${NDK_DIR}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
if [[ ! -d "${TOOLCHAIN}" ]]; then
  echo "Android NDK toolchain not found: ${TOOLCHAIN}"
  exit 1
fi
MIN_SDK="26"
LD_FLAGS="-s -w -checklinkname=0"

build_one() {
  local abi="$1"
  local goarch="$2"
  local cc="$3"
  local goarm="${4:-}"
  local out_dir="${OUTPUT_DIR}/${abi}"
  mkdir -p "${out_dir}"

  echo "Building libedgezlibp2p.so for ${abi}"
  mkdir -p "${GOCACHE}" "${GOMODCACHE}" "${out_dir}"

  if [[ -n "${goarm}" ]]; then
    GOOS=android GOARCH="${goarch}" GOARM="${goarm}" CGO_ENABLED=1 CC="${TOOLCHAIN}/${cc}" \
      go build -buildmode=c-shared -trimpath -ldflags="${LD_FLAGS}" -o "${out_dir}/libedgezlibp2p.so" .
  else
    GOOS=android GOARCH="${goarch}" CGO_ENABLED=1 CC="${TOOLCHAIN}/${cc}" \
      go build -buildmode=c-shared -trimpath -ldflags="${LD_FLAGS}" -o "${out_dir}/libedgezlibp2p.so" .
  fi
}

pushd "${MODULE_DIR}" >/dev/null
go mod tidy
build_one "arm64-v8a" "arm64" "aarch64-linux-android${MIN_SDK}-clang"
build_one "armeabi-v7a" "arm" "armv7a-linux-androideabi${MIN_SDK}-clang" "7"
build_one "x86_64" "amd64" "x86_64-linux-android${MIN_SDK}-clang"
build_one "x86" "386" "i686-linux-android${MIN_SDK}-clang"
popd >/dev/null

echo "Done. libedgezlibp2p.so files are in ${OUTPUT_DIR}/<abi>/"
