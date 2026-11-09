#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK with platform and build-tools 35}"
bt="$sdk/build-tools/35.0.0"
android="$sdk/platforms/android-35/android.jar"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
out="${1:-$PWD/RootRoute-arm64.apk}"
yaml="$build/snakeyaml.jar"
curl -fLsS https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.5/snakeyaml-2.5.jar -o "$yaml"
printf '%s  %s\n' 'e6682acf1ace77508ef13649cbf4f8d09d2cf5457bdb61d25ffb6ac0233d78dd' "$yaml" | sha256sum -c
printf '%s  %s\n' 'd597d33a9cb6d2182c9e110823d09298a2fd3d8bb5b72cc5c3679d545589fe8f' assets/mihomo.gz | sha256sum -c
mkdir "$build/classes" "$build/dex"
if command -v javac >/dev/null; then
    compiler=(javac -source 8 -target 8)
else
    ecj="$build/ecj.jar"
    curl -fLsS https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.42.0/ecj-3.42.0.jar -o "$ecj"
    compiler=(java -jar "$ecj" -proc:none -source 8 -target 8)
fi
"${compiler[@]}" -encoding UTF-8 -bootclasspath "$android:$bt/core-lambda-stubs.jar" -cp "$yaml" -d "$build/classes" src/route/root/MainActivity.java
(cd "$build/classes"; zip -qr "$build/classes.jar" .)
"$bt/d8" --min-api 26 --lib "$android" --output "$build/dex" "$build/classes.jar" "$yaml"
"$bt/aapt2" compile --dir res -o "$build/compiled-res.zip"
"$bt/aapt2" link -I "$android" --manifest AndroidManifest.xml -A assets "$build/compiled-res.zip" --version-code 18 --version-name 0.3 -o "$build/resources.apk"
(cd "$build/dex"; zip -q -j "$build/resources.apk" classes*.dex)
"$bt/zipalign" -f 4 "$build/resources.apk" "$build/aligned.apk"
# 签名：优先用 4 个 Secret 的命名规则（KEYSTORE_BASE64、KEYSTORE_PASSWORD、KEY_ALIAS、
# KEY_PASSWORD，与其他项目一致）；其次兼容旧的 ROOTROUTE_KEYSTORE / ROOTROUTE_STOREPASS；
# 都没给则现场生成一次性测试密钥。
sign_args=()
if [ -n "${KEYSTORE_BASE64:-}" ]; then
    : "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD for the supplied keystore}"
    : "${KEY_ALIAS:?Set KEY_ALIAS for the supplied keystore}"
    keystore="$build/keystore"
    printf '%s' "$KEYSTORE_BASE64" | base64 --decode > "$keystore"
    KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
    export KEYSTORE_PASSWORD KEY_PASSWORD
    sign_args=(--ks "$keystore" --ks-key-alias "$KEY_ALIAS" --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD)
elif [ -n "${ROOTROUTE_KEYSTORE:-}" ]; then
    : "${ROOTROUTE_STOREPASS:?Set ROOTROUTE_STOREPASS for the supplied keystore}"
    export ROOTROUTE_STOREPASS
    sign_args=(--ks "$ROOTROUTE_KEYSTORE" --ks-key-alias rootroute --ks-pass env:ROOTROUTE_STOREPASS)
else
    ROOTROUTE_KEYSTORE="$build/test.p12"
    ROOTROUTE_STOREPASS=android
    keytool -genkeypair -keystore "$ROOTROUTE_KEYSTORE" -storepass android -keypass android \
        -alias rootroute -dname "CN=RootRoute Test" -keyalg RSA -keysize 2048 -validity 3650
    export ROOTROUTE_STOREPASS
    sign_args=(--ks "$ROOTROUTE_KEYSTORE" --ks-key-alias rootroute --ks-pass env:ROOTROUTE_STOREPASS)
fi
"$bt/apksigner" sign "${sign_args[@]}" --out "$out" "$build/aligned.apk"
"$bt/apksigner" verify --verbose "$out"
rm -f "$out.idsig"
