#!/bin/bash
# PVE Buddy Android — 一键构建 APK（零 Gradle：aapt2 → javac → d8 → zipalign → apksigner）
# 产物：dist/PVEBuddy-v${VERSION}-unsigned.apk（内置 debug 密钥签名，可直接安装）
set -euo pipefail
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-/tmp/jdk17/jdk-17.0.14+7/Contents/Home}"
SDK="${ANDROID_SDK:-/tmp/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION=$(grep -o 'VERSION_NAME = "[^"]*"' src/com/pvebuddy/android/BuildInfo.java | cut -d'"' -f2)
OUT=build
mkdir -p "$OUT" dist

echo "==> 1/5 编译资源 (aapt2)"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$PLATFORM" --manifest AndroidManifest.xml \
  --java src "$OUT/res.zip" \
  --min-sdk-version 24 --target-sdk-version 35 \
  --version-name "$VERSION" --version-code "$(grep -o 'BUILD_NUMBER = [0-9]*' src/com/pvebuddy/android/BuildInfo.java | grep -o '[0-9]*')"

echo "==> 2/5 编译 Java (javac)"
find src -name "*.java" > "$OUT/sources.txt"
mkdir -p "$OUT/classes"
javac -source 17 -target 17 \
  -classpath "$PLATFORM:libs/*" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt" 2> >(grep -v "^警告\|^Note\|^警告:" >&2 || true)

echo "==> 3/5 转 dex (d8，含 JSch)"
"$BT/d8" --release --min-api 24 \
  --lib "$PLATFORM" \
  --output "$OUT" \
  $(find "$OUT/classes" -name "*.class") \
  libs/jsch-0.1.55.jar

echo "==> 4/5 组装 APK"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
cd "$OUT" && zip -qj unsigned.apk classes.dex && cd ..
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> 5/5 签名 (debug 密钥，v1+v2+v3)"
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -storepass android -keypass android -keyalg RSA -keysize 2048 \
    -validity 10950 -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android \
  --key-pass pass:android --ks-key-alias androiddebugkey \
  --min-sdk-version 24 --out "dist/PVEBuddy-v${VERSION}-unsigned.apk" "$OUT/aligned.apk"

echo "==> 完成: dist/PVEBuddy-v${VERSION}-unsigned.apk"
"$BT/apksigner" verify --print-certs "dist/PVEBuddy-v${VERSION}-unsigned.apk" 2>/dev/null | head -3
ls -lh "dist/PVEBuddy-v${VERSION}-unsigned.apk"
