#!/usr/bin/env bash
# Build the ASTRA Dialer release APK.
#
# Must run on a network that does not filter linphone.org. The Linphone SDK is
# only published on download.linphone.org, and some networks block that domain
# under a VoIP/telephony category rule — see android/README.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT="$REPO_ROOT/android/AssistantDialer"

# Android Gradle Plugin does not support JDK 25 (the system default here), so
# use the JBR that ships with Android Studio.
STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [[ -d "$STUDIO_JBR" ]]; then
    export JAVA_HOME="$STUDIO_JBR"
elif [[ -z "${JAVA_HOME:-}" ]]; then
    echo "error: Android Studio's JBR not found and JAVA_HOME is unset." >&2
    echo "       Install Android Studio, or point JAVA_HOME at a JDK 17 or 21." >&2
    exit 1
fi

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ ! -d "$ANDROID_HOME" ]]; then
    echo "error: Android SDK not found at $ANDROID_HOME" >&2
    exit 1
fi

echo "JAVA_HOME    $JAVA_HOME"
echo "ANDROID_HOME $ANDROID_HOME"

# Fail early with a useful message rather than a PKIX stack trace 6 seconds in.
echo "Checking access to the Linphone Maven repository..."
probe_url="https://download.linphone.org/maven_repository/org/linphone/no-video/linphone-sdk-android/maven-metadata.xml"
# Capture curl's exit status separately: with -w it still prints "000" on a
# transport failure, so appending a sentinel would corrupt the code.
curl_status=0
http_code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$probe_url" 2>/dev/null)" || curl_status=$?
if (( curl_status != 0 )); then
    http_code="transport-failure"
fi

case "$http_code" in
    200)
        echo "  reachable"
        ;;
    transport-failure)
        echo >&2
        echo "error: could not reach download.linphone.org (TLS or connection failure)." >&2
        echo "       This network is intercepting or blocking it. Build from a network" >&2
        echo "       that does not — a phone hotspot works. See android/README.md." >&2
        exit 1
        ;;
    *)
        echo >&2
        echo "error: download.linphone.org returned HTTP $http_code." >&2
        echo "       This network is blocking the domain. Build from a network that" >&2
        echo "       does not (a phone hotspot works). See android/README.md." >&2
        exit 1
        ;;
esac

cd "$PROJECT"
./gradlew assembleDebug lintDebug assembleRelease

debug_apk="$PROJECT/app/build/outputs/apk/debug/app-debug.apk"
release_apk="$PROJECT/app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ -f "$debug_apk" && -f "$release_apk" ]]; then
    echo
    echo "Installable debug APK: $debug_apk"
    echo "Size: $(du -h "$debug_apk" | cut -f1)"
    echo "Unsigned release APK: $release_apk"
    echo "Size: $(du -h "$release_apk" | cut -f1)"
    echo
    echo "Install with the Nothing 3a connected and USB debugging on:"
    echo "  \"\$ANDROID_HOME/platform-tools/adb\" install -r \"$debug_apk\""
    echo "Sign the release APK with your private Android signing key before distribution."
else
    echo "error: build reported success but one or more APK outputs are missing" >&2
    exit 1
fi
