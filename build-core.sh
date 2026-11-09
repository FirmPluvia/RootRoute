#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
project="$PWD"
ndk="${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to the Android NDK r29 directory}"
compiler="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android34-clang"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
unzip -q "$project/vendor/mihomo-v1.19.31-source.zip" -d "$build"
cd "$build/github.com/metacubex/mihomo@v1.19.31"
patch -s -p1 < "$project/vendor/mihomo-proxy-traffic.patch"
CC="$compiler" GOOS=android GOARCH=arm64 CGO_ENABLED=1 GOTOOLCHAIN=local \
    "${GO_BIN:-go}" build -buildvcs=false -tags with_gvisor -trimpath \
    -ldflags "-s -w -buildid= -X github.com/metacubex/mihomo/constant.Version=v1.19.31-rootroute" \
    -o "$build/mihomo" .
gzip -n -9 -c "$build/mihomo" > "$project/assets/mihomo.gz"
echo "Rebuilt core. Update the bundled-core SHA-256 in build.sh if needed."
sha256sum "$project/assets/mihomo.gz"
