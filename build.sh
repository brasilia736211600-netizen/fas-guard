#!/usr/bin/env bash
# FAS Guard – dependency-free Android build (aapt2 + javac + d8 + apksigner).
# Works on Termux and on any Linux/CI machine with a JDK and an Android SDK.
set -euo pipefail
cd "$(dirname "$0")"

VERSION_NAME="${VERSION_NAME:-1.1.0}"
VERSION_CODE="${VERSION_CODE:-3}"
MIN_SDK="${MIN_SDK:-29}"
TARGET_SDK="${TARGET_SDK:-35}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
OUT="dist/FASGuard-${VERSION_NAME}.apk"

die() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
say() { printf '\033[36m▸\033[0m %s\n' "$*"; }

find_bt() {
    local tool="$1"
    if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
        local dir
        dir="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)" || true
        if [ -n "${dir:-}" ] && [ -x "${dir}${tool}" ]; then echo "${dir}${tool}"; return 0; fi
    fi
    command -v "$tool" 2>/dev/null || return 1
}

AAPT2="$(find_bt aapt2)" || die "aapt2 not found (install Android build-tools or run in CI)"
D8="$(find_bt d8)" || die "d8 not found"
ZIPALIGN="$(find_bt zipalign)" || die "zipalign not found"
APKSIGNER="$(find_bt apksigner)" || die "apksigner not found"
command -v javac >/dev/null || die "javac not found"
command -v keytool >/dev/null || die "keytool not found"

if [ -n "${ANDROID_JAR:-}" ]; then
    JAR="$ANDROID_JAR"
elif [ -n "$SDK" ]; then
    JAR="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)" || true
else
    JAR=""
fi
[ -n "${JAR:-}" ] && [ -f "$JAR" ] || die "android.jar not found – set ANDROID_JAR or install platforms;android-${TARGET_SDK}"

KEYSTORE="${KEYSTORE:-fasguard.keystore}"
KEY_ALIAS="${KEY_ALIAS:-fasguard}"
KEYSTORE_PASS="${KEYSTORE_PASS:-fasguard}"
KEY_PASS="${KEY_PASS:-fasguard}"
if [ ! -f "$KEYSTORE" ]; then
    say "Generating development keystore (override with KEYSTORE/KEYSTORE_PASS)"
    keytool -genkeypair -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
        -dname "CN=FAS Guard, O=FAS, C=US" >/dev/null 2>&1
fi

say "Cleaning"
rm -rf build/gen build/classes build/dex build/base.apk build/aligned.apk
mkdir -p build/gen build/classes build/dex dist

say "Compiling resources (API ${TARGET_SDK})"
"$AAPT2" compile --dir app/src/main/res -o build/res.zip
"$AAPT2" link -o build/base.apk -I "$JAR" \
    --manifest app/src/main/AndroidManifest.xml -R build/res.zip --auto-add-overlay \
    --java build/gen --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

say "Compiling Java"
mapfile -t SOURCES < <(find app/src/main/java build/gen -name '*.java')
javac -nowarn -source 11 -target 11 -encoding UTF-8 -cp "$JAR" -d build/classes "${SOURCES[@]}"

say "Dexing (min-api ${MIN_SDK})"
mapfile -t CLASSES < <(find build/classes -name '*.class')
"$D8" --lib "$JAR" --min-api "$MIN_SDK" --output build/dex "${CLASSES[@]}"

say "Packaging"
(cd build/dex && zip -q ../base.apk classes.dex)
"$ZIPALIGN" -f 4 build/base.apk build/aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:${KEYSTORE_PASS}" --key-pass "pass:${KEY_PASS}" \
    --out "$OUT" build/aligned.apk
"$APKSIGNER" verify "$OUT" >/dev/null

SIZE="$(wc -c < "$OUT" | tr -d ' ')"
say "Built $OUT (${SIZE} bytes)"
command -v sha256sum >/dev/null && sha256sum "$OUT" || true
