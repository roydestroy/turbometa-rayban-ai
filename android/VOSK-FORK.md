# Free wake phrase Android fork

This fork keeps the existing TurboMeta Android features and replaces Picovoice
with on-device Vosk. Say **Hey Vision** to run the selected Quick Vision mode.
This phrase does not launch Live AI or provide a general voice command parser.
Live AI, camera screens and RTMP remain accessible through the app.
AI provider usage is separate and can incur provider charges.

## Setup

1. Pair the glasses in Meta AI and enable its developer/SDK mode as required by
   the upstream app. Complete TurboMeta registration/camera permission setup.
2. Configure your AI provider normally and test Quick Vision manually first.
3. Open Settings → Quick Vision → Glasses microphone and select your glasses.
   Without an explicit choice, only a single device with a recognizable Meta,
   Ray-Ban or Oakley name is automatically selected. The phone mic is never a fallback.
4. Enable Wake Word Detection and grant microphone/Nearby devices permissions.
5. Allow the initial ~40 MB English model download to finish. Subsequent wake
   detection is offline. Read the status under the switch for errors or readiness.
6. Say Hey Vision. Listening releases its recorder and Bluetooth route before
   Quick Vision uses the glasses, then resumes after the feature finishes.

The notification provides a Stop listening action. Disable battery optimization
if your phone suspends the foreground service. Continuous Bluetooth microphone
use consumes battery and temporarily interrupts other audio. Calls/other audio
apps can stop listening; enable it again afterward. There is no automatic
microphone restart following a killed process or reboot.

## Verification needed on the real phone/glasses

- Test first model download, offline restart, stop during download, denied permissions.
- Test glasses-only recording with the phone away; add a second headset and confirm selection.
- Test repeated Quick Vision, screen lock, Bluetooth disconnect/reconnect, and Stop.
- Enter/leave Live AI and each camera/streaming screen while wake listening is enabled.
- Receive a phone call and confirm the app yields audio.
- Check wake recognition in quiet/noisy surroundings and battery use over a session.

Unit tests cover exclusive ownership, cancellation and duplicate release of
the audio gate. These cannot substitute for actual hardware tests.

## Build

GitHub Actions builds and tests this fork using its normal repository token;
no personal token is needed for that build. Download the `TurboMeta-free-wake-debug`
artifact and install its ARM64 APK on a compatible ARM64 phone (Android 12+).

For a local build, use Java 17, Android SDK 35 and the committed Gradle wrapper. Configure a personal
GitHub token with read:packages in untracked android/local.properties as documented
upstream if GitHub Packages requests authentication. Never commit this token.

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

The ARM64 debug APK is suitable for ARM64 Android phones. It is signed with the
build machine's debug key. Android will reject updating an installed copy signed
by a different key: preserve any data/settings before uninstalling that copy.

Vosk Android and the small English model are Apache-2.0 components. Model source:
https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip

The downloaded archive is SHA-256 verified; model extraction is bounded and
path checked. Only a fully verified installation is reused.
