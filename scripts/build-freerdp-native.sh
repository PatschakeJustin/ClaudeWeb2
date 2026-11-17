#!/usr/bin/env bash
# Builds FreeRDP (with OpenSSL) for Android into freerdp/src/main/jniLibs.
# Needs: Android NDK ($ANDROID_NDK or $ANDROID_NDK_LATEST_HOME), cmake, make, git, curl.
set -euo pipefail
FREERDP_TAG=3.17.2
ARCHS="${ARCHS:-armeabi-v7a arm64-v8a}"
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="${WORK:-$ROOT/build-freerdp}"
NDK="${ANDROID_NDK:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -d "$NDK" ] || { echo "Android NDK nicht gefunden"; exit 1; }

rm -rf "$WORK"; mkdir -p "$WORK"; cd "$WORK"
git clone -q --depth 1 --branch "$FREERDP_TAG" https://github.com/FreeRDP/FreeRDP.git src

# Our build configuration: OpenSSL only (no FFmpeg/H.264/Azure AD) to keep the APK small.
cat > src/scripts/android-build.conf <<CONF
WITH_OPENH264=0
WITH_OPENSSL=1
WITH_FFMPEG=0
WITH_AAD=0
BUILD_DEPS=1
DEPS_ONLY=0
NDK_TARGET=24
WITH_MEDIACODEC=0
OPENH264_TAG=v2.6.0
OPENH264_HASH=558544ad358283a7ab2930d69a9ceddf913f4a51ee9bf1bfb9e377322af81a69
OPENSSL_TAG=openssl-3.5.3
OPENSSL_HASH=c9489d2abcf943cdc8329a57092331c598a402938054dc3a22218aea8a8ec3bf
FFMPEG_TAG=n7.1.2
FFMPEG_HASH=8cb1bb8cfa9aeae13279b4da42ae8307ae6777456d4270f2e603c95aa08ca8ef
CJSON_TAG=v1.7.19
CJSON_HASH=7fa616e3046edfa7a28a32d5f9eacfd23f92900fe1f8ccd988c1662f30454562
SRC_DIR=\$SCRIPT_PATH/..
BUILD_DST=$WORK/out
BUILD_SRC=$WORK/build
CMAKE_BUILD_TYPE=Release
BUILD_ARCH="$ARCHS"
CONF

export CMAKE_PROGRAM=$(command -v cmake)
export CMAKE_BUILD_PARALLEL_LEVEL=$(nproc)
export MAKEFLAGS="-j$(nproc)"
bash src/scripts/android-build-freerdp.sh --ndk "$NDK" --sdk "${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"

DST="$ROOT/freerdp/src/main/jniLibs"
rm -rf "$DST"; mkdir -p "$DST"
for a in $ARCHS; do
  cp -r "$WORK/out/$a" "$DST/$a"
  # Keep only what the JNI build and the APK need: shared libraries and headers.
  find "$DST/$a" -name '*.a' -delete
  # Remove debug symbols (cuts the libraries to a fraction of their size).
  find "$DST/$a" -maxdepth 1 -name '*.so' -exec "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded {} \;
  ls -la "$DST/$a"/*.so
done
echo "FreeRDP $FREERDP_TAG für $ARCHS gebaut."
