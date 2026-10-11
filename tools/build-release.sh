#!/bin/bash
# Builds every file of an Apogee release from the committed tree, into ~/Projects/apogee-release-VERSION.
# Usage: tools/build-release.sh 0.9.1
set -euo pipefail
V=${1:?usage: tools/build-release.sh VERSION}
REPO=$(cd "$(dirname "$0")/.." && pwd)
OUT=$HOME/Projects/apogee-release-$V
cd "$REPO"
[ -z "$(git status --porcelain | grep -v '^?? core\.')" ] || { echo "tree not clean"; exit 1; }
grep -q "versionName = \"$V\"" app/build.gradle.kts || { echo "version is not $V"; exit 1; }
mkdir -p "$OUT"

# Both APKs, from clean, signed with the release key.
heavy build ./gradlew clean :app:assembleRelease
cp app/build/outputs/apk/release/app-release.apk "$OUT/apogee-$V.apk"
heavy build ./gradlew :app:assembleRelease -Parm32
cp app/build/outputs/apk/release/app-release.apk "$OUT/apogee-$V-32bit.apk"

# The browser version. The F-Droid publisher builds its own from the tag; this is to see it builds.
heavy build ./gradlew :shared:wasmJsBrowserDistribution
rm -rf "${OUT:?}/web"
cp -r shared/build/dist/wasmJs/productionExecutable "$OUT/web"

# The desktop builds: Debian packages and AppImages for x86-64 and arm64, and Windows' installer
# and portable zip. Docker does the cross builds.
heavy build ./gradlew :desktop:debAmd64 :desktop:debArm64 :desktop:appImageAmd64 :desktop:appImageArm64 :desktop:windowsX64
cp desktop/build/deb/apogee_${V}_amd64.deb desktop/build/deb/apogee_${V}_arm64.deb "$OUT/"
cp desktop/build/appimage/apogee-$V-x86_64.AppImage desktop/build/appimage/apogee-$V-aarch64.AppImage "$OUT/"
cp desktop/build/windows/apogee-$V-setup.exe desktop/build/windows/apogee-$V-windows-x64.zip "$OUT/"

# Each APK's version, code and key, against what the release should be.
TOOLS=$(ls -d "$HOME"/Android/Sdk/build-tools/*/ | sort -V | tail -1)
CERT=1d4f11b03252fbdcd8867c799341416b0a9741509991708fac639d9807e93b11
for f in "$OUT/apogee-$V.apk" "$OUT/apogee-$V-32bit.apk"; do
    "${TOOLS}aapt2" dump badging "$f" | grep -E "^package|native-code"
    "${TOOLS}apksigner" verify --print-certs "$f" | grep -q "SHA-256 digest: $CERT" || { echo "$f: wrong key"; exit 1; }
done

cd "$OUT"
sha256sum "apogee-$V.apk" "apogee-$V-32bit.apk" "apogee_${V}_amd64.deb" "apogee_${V}_arm64.deb" \
    "apogee-$V-x86_64.AppImage" "apogee-$V-aarch64.AppImage" "apogee-$V-setup.exe" "apogee-$V-windows-x64.zip" > SHA256SUMS
cat SHA256SUMS

# Only the newest release folder is kept.
for d in "$HOME"/Projects/apogee-release-*; do
    v=${d##*-}
    [ "$v" != "$V" ] && [ "$(printf '%s\n%s\n' "$v" "$V" | sort -V | head -1)" = "$v" ] && rm -rf "${d:?}"
done
echo DONE
