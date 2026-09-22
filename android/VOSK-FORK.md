# Free wake phrase Android fork

Version **1.6.0-assistant-beta** keeps the existing TurboMeta features and replaces
Picovoice with on-device Vosk. Say **Hey Vision**, then ask a question in English.
The camera stays off unless the assistant needs it to answer a visual request.
Live AI, manual Quick Vision, camera screens and RTMP remain available in the app.
The new assistant uses the selected **Vision provider/model**, not the Live AI
provider. Choose a model that supports function calling and vision. Provider usage
can incur charges; wake detection and English speech recognition run locally.

## Assistant examples and limits

- “Hey Vision, what is the weather in Athens?”: fetches current conditions and a
  three-day forecast from [Open-Meteo](https://open-meteo.com/) (CC BY 4.0 data).
  There is no automatic GPS lookup in this beta: name a city, or it asks which city.
- “Hey Vision, read this sign”: waits for the glasses stream, requests a real SDK
  photo (including the shutter behavior), then analyzes it for your question.
- “Hey Vision, navigate to Syntagma Square in Athens”: opens Google Maps when
  TurboMeta is foregrounded; otherwise provides a notification you must tap.
  Allow notifications for this fallback. Maps handles GPS and route calculation.
  Set Maps to play voice over Bluetooth and select the glasses as media output.
  Wake listening pauses for navigation; enable it again in Settings afterward.
- General questions get a spoken answer without camera access.
- Say **Hey Vision again for each follow-up**. The last three completed exchanges
  remain in process memory for up to five minutes, allowing clarification and
  follow-up questions. “Hey Vision, stop listening” clears that history and returns
  to wake detection; use the notification or Settings switch to disable the mic.
- This is turn-based, not full-duplex: wait until the answer ends before speaking.
  There is no general web search, messaging, calendar control or native Meta unlock.
- Speak English for recognition in this version. Local recognition of Greek place
  names can be imperfect. Settings → Last assistant result shows both what was
  heard and the complete response/error.

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
6. Say Hey Vision, then your question (either together or with a brief pause).
   Listening waits up to 20 seconds for a complete question. It releases its
   recorder before the assistant starts, and resumes only after speech finishes.
   No camera session is opened for the wake phrase alone.

The notification provides a Stop listening action. Disable battery optimization
if your phone suspends the foreground service. Continuous Bluetooth microphone
use consumes battery and temporarily interrupts other audio. Calls/other audio
apps can stop listening; enable it again afterward. There is no automatic
microphone restart following a killed process or reboot.

## Verification needed on the real phone/glasses

- Test first model download, offline restart, stop during download, denied permissions.
- Test glasses-only recording with the phone away; add a second headset and confirm selection.
- Test repeated visual questions, screen lock, Bluetooth disconnect/reconnect, and Stop.
- Compare a manual Quick Vision capture with “Hey Vision, what am I looking at?”
  Both should now request an actual photo. Record any full error in Settings.
- Test a general question, weather for an explicitly named city, and a follow-up.
- Test navigation with the app open and backgrounded; verify the notification
  fallback and Maps audio through the glasses.
- Enter/leave Live AI and each camera/streaming screen while wake listening is enabled.
- Receive a phone call and confirm the app yields audio.
- Check wake recognition in quiet/noisy surroundings and battery use over a session.

Unit tests cover audio ownership/cancellation/duplicate release, wake-only input,
single-utterance questions, unrelated speech, and incomplete-command timeout.
These cannot substitute for hardware tests or a real request using your API key.

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
