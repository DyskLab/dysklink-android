#!/bin/bash
# Builds a release APK signed with the DyskLink release key.
# The key lives outside the repository; its password is in the macOS Keychain.
#   First time: scripts/release-apk.sh --create-key
#   Each release: scripts/release-apk.sh   ->  dist/DyskLink-android-<version>.apk
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="${DYSKLINK_KEYSTORE:-$HOME/dysklink-release.jks}"
SERVICE="dysklink-android-release"
ALIAS="dysklink"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner | sort -V | tail -1)"

if [[ "${1:-}" == "--create-key" ]]; then
    if [[ -e "$KEYSTORE" ]]; then echo "A key already exists at $KEYSTORE. It is not replaced." >&2; exit 1; fi
    mkdir -p "$(dirname "$KEYSTORE")"
    chmod 700 "$(dirname "$KEYSTORE")"
    PASSWORD="$(openssl rand -base64 30 | tr -d '/+=' | cut -c1-32)"
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -alias "$ALIAS" \
        -keyalg RSA -keysize 4096 -validity 10950 \
        -dname "CN=DyskLink, O=DyskLab" -storepass "$PASSWORD" -keypass "$PASSWORD" >/dev/null 2>&1
    chmod 600 "$KEYSTORE"
    security add-generic-password -a "$USER" -s "$SERVICE" -w "$PASSWORD" -U
    echo "Key made at $KEYSTORE, password saved in the Keychain as \"$SERVICE\"."
    echo "Back up both. Without them, no update can install over this release."
    keytool -list -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" | grep -i "fingerprint"
    exit 0
fi

[[ -f "$KEYSTORE" ]] || { echo "No release key at $KEYSTORE. Run with --create-key first." >&2; exit 1; }
PASSWORD="$(security find-generic-password -a "$USER" -s "$SERVICE" -w)"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
DYSKLINK_KEYSTORE="$KEYSTORE" DYSKLINK_KEYSTORE_PASSWORD="$PASSWORD" DYSKLINK_KEY_ALIAS="$ALIAS" \
    ./gradlew -q :app:assembleRelease
./gradlew --stop -q || true
pkill -f "kotlin-build-tools|kotlin-daemon|GradleDaemon" || true

mkdir -p dist
OUT="dist/DyskLink-android-$VERSION.apk"
cp app/build/outputs/apk/release/app-release.apk "$OUT"
"$APKSIGNER" verify --print-certs "$OUT" | grep -E "Signer #1 certificate (DN|SHA-256)"
echo "$OUT"
