#!/bin/bash
# Builds the iPhone remote bridge APK (FakeAtv.apk) from upstream atv-core plus atv-core.patch and, with "install",
# installs it on the TV. Usage: TV=192.168.1.50:5555 ./build.sh [install]
# Needs: git, JDK 17, Android SDK + NDK 27.2.12479018, rustup with the armv7/aarch64 Android targets, cargo-ndk.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
UPSTREAM=https://github.com/corvofeng/atv-core.git
COMMIT=8a4ada29bb7cad31e0315c3387b277eb9ab8c988
TV="${TV:-}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/27.2.12479018}"
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$JAVA_HOME/bin:$PATH"

# 1. Upstream source at the tested commit, with the patch applied (once).
if [ ! -d "$HERE/atv-core" ]; then
  git clone -q "$UPSTREAM" "$HERE/atv-core"
  git -C "$HERE/atv-core" checkout -q "$COMMIT"
  git -C "$HERE/atv-core" apply --whitespace=nowarn "$HERE/atv-core.patch"
fi

# 2. Build (unsigned release APK).
cd "$HERE/atv-core"
cargo test -p atv-core --release 2>&1 | grep "test result"
bash scripts/build_android.sh release /nonexistent.p12 x | tail -3

# 3. Sign with your own key. Keep keystore/ (git-ignored): updates must be signed with the same key.
KS="$HERE/keystore/fakeatv.p12"
if [ ! -f "$KS" ]; then
  mkdir -p "$HERE/keystore"
  head -c 18 /dev/urandom | base64 | tr -d '/+=' > "$HERE/keystore/password.txt"
  chmod 600 "$HERE/keystore/password.txt"
  keytool -genkeypair -keystore "$KS" -storetype pkcs12 -alias fakeatv -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$(cat "$HERE/keystore/password.txt")" -dname "CN=fakeatv" >/dev/null
fi
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign --ks "$KS" --ks-pass "file:$HERE/keystore/password.txt" \
  --out "$HERE/FakeAtv.apk" android-tv/app/build/outputs/apk/release/app-release-unsigned.apk
rm -f "$HERE/FakeAtv.apk.idsig"
shasum -a 256 "$HERE/FakeAtv.apk"

if [ "${1:-}" = "install" ]; then
  [ -n "$TV" ] || { echo "Set TV=<ip>:5555"; exit 1; }
  adb connect "$TV" >/dev/null
  adb -s "$TV" push "$HERE/FakeAtv.apk" /data/local/tmp/f.apk
  # -r keeps the app's data (pairing key, ADB key). Never force-stop the bridge.
  adb -s "$TV" shell 'pm install -r /data/local/tmp/f.apk && rm /data/local/tmp/f.apk'
fi
