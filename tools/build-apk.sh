#!/usr/bin/env bash
# Builds the APK without the Android Gradle Plugin (for environments where Google's Maven
# repository is unreachable). In Android Studio, just use Gradle instead.
#
# Needs: aapt, zipalign, apksigner, javac, kotlinc, D8 (r8lib.jar), an android.jar for API 34,
# and the Shizuku AARs (api, provider, aidl, shared 13.1.5) unpacked in $DEPS/<name>/.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_JAR="${ANDROID_JAR:-/opt/android-sdk/platforms/android-34/android.jar}"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kt/kotlinc}"
DEPS="${DEPS:-/opt/deps}"
D8_JAR="${D8_JAR:-/opt/r8-8.7.18.jar}"
OUT="$ROOT/build/manual"
PKG=dev.tabgestures
VERSION_CODE=1
VERSION_NAME=0.1.0

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

LIBS=""
for a in api provider aidl shared; do LIBS="$LIBS:$DEPS/$a/classes.jar"; done
LIBS="${LIBS#:}"

echo "== manifest + resources"
sed "s/\${applicationId}/$PKG/" "$ROOT/app/src/main/AndroidManifest.xml" |
    sed "s|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">|<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PKG\">|" \
    > "$OUT/AndroidManifest.xml"
aapt package -f -m -J "$OUT/gen" -M "$OUT/AndroidManifest.xml" -S "$ROOT/app/src/main/res" \
    -I "$ANDROID_JAR" --min-sdk-version 30 --target-sdk-version 34 \
    --version-code $VERSION_CODE --version-name $VERSION_NAME -F "$OUT/resources.apk"

cat > "$OUT/gen/BuildConfig.java" <<JAVA
package $PKG;
public final class BuildConfig {
    public static final boolean DEBUG = false;
    public static final String APPLICATION_ID = "$PKG";
    public static final int VERSION_CODE = $VERSION_CODE;
    public static final String VERSION_NAME = "$VERSION_NAME";
}
JAVA

echo "== javac (R, BuildConfig)"
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -d "$OUT/classes" $(find "$OUT/gen" -name '*.java')

echo "== kotlinc"
"$KOTLIN_HOME/bin/kotlinc" -no-jdk -no-reflect -jvm-target 1.8 -nowarn \
    -cp "$ANDROID_JAR:$OUT/classes:$LIBS" -d "$OUT/classes" "$ROOT/app/src/main/java"

echo "== dex"
(cd "$OUT/classes" && zip -qr "$OUT/app-classes.jar" .)
java -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 30 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" "$OUT/app-classes.jar" "$KOTLIN_HOME/lib/kotlin-stdlib.jar" ${LIBS//:/ }

echo "== package + sign"
cp "$OUT/resources.apk" "$OUT/unaligned.apk"
(cd "$OUT/dex" && aapt add -k "$OUT/unaligned.apk" classes.dex >/dev/null)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"
KS="$ROOT/tools/debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias androiddebugkey \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Tab Gestures Debug" >/dev/null 2>&1
fi
apksigner sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$ROOT/build/TabGestures-$VERSION_NAME.apk" "$OUT/aligned.apk"
apksigner verify "$ROOT/build/TabGestures-$VERSION_NAME.apk"
echo "Built $ROOT/build/TabGestures-$VERSION_NAME.apk"
