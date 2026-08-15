# ASTRA Dialer

Android SIP/Telecom client for ASTRA. The app contains no Gemini key and no
assistant logic; it registers with the Mac's Asterisk server, dials extension
700, and routes call audio through Asterisk AudioSocket to the same Gemini Live
session used by the browser and PSTN gateways.

## Build

```bash
cd android/AssistantDialer
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="$HOME/Library/Android/sdk" \
./gradlew assembleDebug lintDebug
```

The verified debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Release
builds are minified and restricted to `arm64-v8a`.

## Mac services

1. Start Asterisk using the LAN-only configuration under `docs/asterisk/`.
2. Start `.venv/bin/python -m app.main --sip` on `127.0.0.1:8090`.
3. Verify `pjsip show endpoints` and `pjsip show contacts` in Asterisk.
4. Dial 701 first for echo, then 700 for ASTRA.

## Device setup

1. Connect the phone to the same trusted LAN as the Mac.
2. Install the APK with `adb install -r app-debug.apk`.
3. Grant microphone, phone state/calls, notifications, and Bluetooth permissions.
4. In Settings enter the Mac LAN IP, SIP username/password, extension 700, and
   the Asterisk transport. Tap **Test registration** and wait for `REGISTERED`.
5. Enable **ASTRA (Call with)** under Android Calling accounts. The managed
   account is required for the system call UI and Bluetooth/watch mirroring.
6. Optionally grant the call-redirection role and create/sync the ASTRA contact.

The dialer queues a call while Linphone registers instead of failing the first
attempt. A registration failure is surfaced on Home and ends the Telecom call.
Call redirection compares only the configured ASTRA number; every other number
is explicitly passed to the cellular dialer unchanged.

## Security and device validation

The supplied Asterisk profile is LAN-only and does not provide internet-safe
SIP. Do not expose UDP 5060 publicly. Use a trusted VPN plus TLS/SRTP before
calling across untrusted networks.

Still requires a physical-device pass:

- SIP registration and two-way audio against the running Mac.
- Managed-account call UI and Bluetooth route.
- Yesido IO34 HFP dialing/audio, if the watch exposes the standard Phone Calls profile.
- Regression test that every non-ASTRA number remains cellular.
