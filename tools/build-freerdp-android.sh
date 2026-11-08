#!/usr/bin/env bash
# Builds the FreeRDP native libraries (plus OpenSSL, cJSON, uriparser) for the
# Android app and drops them in app/src/main/jniLibs/<abi>/, where Gradle
# packages them into the APK. Uses FreeRDP's own Android CMake project, which
# downloads and builds its dependencies. Needs an NDK (ANDROID_NDK_ROOT) plus
# cmake, ninja, perl and pkg-config. Codecs that need extra libraries (H.264,
# FFmpeg, Opus, image formats) are left out: plain RDP graphics don't need them.
set -euo pipefail

FREERDP_REPO="https://github.com/FreeRDP/FreeRDP.git"
# Pinned: the Android CMake project and its JNI interface change between
# revisions, and app/src/main/java/com/freerdp/.../LibFreeRDP.java mirrors it.
FREERDP_COMMIT="8a312188d093d71fe6c54352eb2162a5bfceb929"
ABI="arm64-v8a"
API_LEVEL="29"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/build/freerdp"
SRC="$WORK/src"
BUILD="$WORK/build"
OUT="$ROOT/app/src/main/jniLibs/$ABI"
# Newest NDK first: r28+ links with 16 KB page alignment by default.
NDK="${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"

if [ -z "$NDK" ] || [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
  echo "Android NDK not found: set ANDROID_NDK_LATEST_HOME or ANDROID_NDK_ROOT" >&2
  exit 1
fi

if [ ! -d "$SRC/.git" ] || [ "$(git -C "$SRC" rev-parse HEAD)" != "$FREERDP_COMMIT" ]; then
  rm -rf "$SRC"
  git init -q "$SRC"
  git -C "$SRC" fetch -q --depth 1 "$FREERDP_REPO" "$FREERDP_COMMIT"
  git -C "$SRC" -c advice.detachedHead=false checkout -q FETCH_HEAD
fi

cmake -S "$SRC/client/Android/Studio/freeRDPCore/src/main/cpp" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ABI" \
  -DANDROID_PLATFORM="android-$API_LEVEL" \
  -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DWITH_OPENSSL=ON -DWITH_CJSON=ON \
  -DWITH_FFMPEG=OFF -DWITH_OPENH264=OFF -DWITH_OPUS=OFF \
  -DWITH_PNG=OFF -DWITH_WEBP=OFF -DWITH_JPEG=OFF
cmake --build "$BUILD"

# FreeRDP's project installs the dependency libraries next to its own
# jniLibs folder; the JNI bridge itself stays in the build tree.
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$SRC/client/Android/Studio/freeRDPCore/src/main/jniLibs/$ABI/"*.so "$OUT/"
find "$BUILD" -maxdepth 2 -name 'libfreerdp-android.so' -exec cp {} "$OUT/" \;
# The dependencies are linked against the shared C++ runtime; a Gradle build
# only adds it automatically for its own externalNativeBuild projects.
cp "$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "$OUT/"

test -f "$OUT/libfreerdp-android.so"
ls -l "$OUT"
