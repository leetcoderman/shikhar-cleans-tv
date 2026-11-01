#!/bin/bash
# Builds a static libcrypto (OpenSSL 3.5 LTS) for armeabi-v7a, API 30.
# Usage: scripts/build-openssl.sh /path/to/openssl-3.5.x-source
# Output: app/src/main/cpp/third_party/openssl/{include,lib/libcrypto.a}
set -euo pipefail
SRC="$1"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$HERE/app/src/main/cpp/third_party/openssl"
export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-/opt/homebrew/share/android-commandlinetools/ndk/27.2.12479018}"
export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
cd "$SRC"
make distclean >/dev/null 2>&1 || true
./Configure android-arm -D__ANDROID_API__=30 no-shared no-apps no-tests no-docs no-engine \
  no-dso no-ui-console no-quic no-ssl3 no-comp --prefix="$OUT" --libdir=lib
make -j"$(sysctl -n hw.ncpu)" build_libs
rm -rf "$OUT"; mkdir -p "$OUT/lib"
cp libcrypto.a "$OUT/lib/"
cp -R include "$OUT/include"
find "$OUT/include" -name '*.in' -delete
cp LICENSE.txt "$OUT/LICENSE.txt"
echo "OpenSSL built into $OUT"
