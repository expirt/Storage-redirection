#!/usr/bin/env bash
# Builds StorageRedirect.apk without Gradle (Debian/Ubuntu):
#   sudo apt install openjdk-21-jdk-headless aapt apksigner dalvik-exchange \
#        libandroid-23-java zipalign
set -euo pipefail
cd "$(dirname "$0")"

AJ="${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}"
DX="${DX_JAR:-/usr/share/java/com.android.dx.jar}"
KS="${KEYSTORE:-keystore.jks}"
KS_PASS="${KEYSTORE_PASS:-android}"
OUT=build

rm -rf "$OUT"; mkdir -p "$OUT/stubcls" "$OUT/classes"

# Xposed API stubs: compile-time only, NOT packaged (the framework provides the real API).
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -d "$OUT/stubcls" \
  $(find stubs -name '*.java')
javac -source 8 -target 8 -Xlint:-options -bootclasspath "$AJ" -cp "$OUT/stubcls" \
  -d "$OUT/classes" $(find src -name '*.java')

java -cp "$DX" com.android.dx.command.Main --dex --min-sdk-version=21 \
  --output="$OUT/classes.dex" "$OUT/classes"

aapt package -f -M AndroidManifest.xml -S res -I "$AJ" -A assets -0 arsc -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KS" ]; then
  echo "No keystore found - generating a throwaway one at $KS (gitignored)"
  keytool -genkeypair -keystore "$KS" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=StorageRedirect"
fi
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version 21 --out "$OUT/StorageRedirect.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/StorageRedirect.apk"
echo "Built $OUT/StorageRedirect.apk"
