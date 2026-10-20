# ZetaRun

An Android mental-arithmetic trainer for hands-free treadmill sessions, inspired by [Zetamac](https://arithmetic.zetamac.com/). This is an independent app and is not affiliated with Zetamac.

Solve a stream of arithmetic problems by speaking the answer. The default round lasts two minutes. Speech recognition runs continuously on the phone with [Vosk](https://alphacephei.com/vosk/); microphone audio is never sent to a speech service. Session history works without an account. Connecting your own Firebase project enables Google sign-in and progress sync.

## Build and run

You need Android Studio, JDK 17 or 21, Python 3 for model preparation, Android SDK Platform 36 and build tools, and a 64-bit Android 8.0+ device. The S24 Ultra is supported. The app targets Android 16 (API 36). The Gradle wrapper defines the build-tool version.

```bash
git clone https://github.com/jshao0919/zetaRun.git
cd zetaRun
./scripts/prepare-model.sh
./gradlew testDebugUnitTest assembleDebug
```

The model preparation script extracts the US English model from the official Vosk Android demo's pinned `2021-04` release archive and verifies its SHA-256 before extraction. It uses the model distributed in that release, rather than claiming to bundle the latest model. It is needed before the first build and does not require a Firebase project. Keep the generated model out of Git. Gradle, SDK, and model downloads require an Internet connection; recognition in an installed app does not.

Open the project in Android Studio, let Gradle sync, select your phone, and run the `app` configuration. Or install the generated APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Follow [S24 Ultra testing](docs/S24_ULTRA.md) for USB setup, installation, and a voice-validation checklist. Follow [Firebase setup](docs/FIREBASE.md) to enable Google login and cloud progress. The app remains usable locally until that setup is complete.

If you received a source ZIP, extract and open that project directly. Cloud workspace changes are not automatically pushed to GitHub. Once this code is pushed, the included GitHub Actions workflow runs the checks and produces a downloadable debug APK; the workflow itself has not been run on GitHub during development.

## Use the app

- **Train:** choose voice or keypad, a 1-, 2-, or 5-minute round, and arithmetic operations. The tune button opens custom durations and number ranges. During a voice round, say an answer, “skip,” or “pause.” Pause stops both the timer and microphone; resume from the screen.
- **Progress:** review solving pace, accuracy, and dated session history. The graph shows the latest 20 rounds. Compare rounds with similar arithmetic settings when judging improvement.
- **Account:** sign in or sync after Firebase setup, export progress as JSON, delete progress, or request account and cloud-data deletion. Cloud actions require connectivity and may require a fresh Google sign-in. Firebase integration still requires testing against your own project.

## Voice behavior

The app uses an English number vocabulary and short audio frames to avoid network round trips. It waits for a stable recognition candidate and a short quiet interval before considering a partial answer. Final recognition results provide a second route to submission. An utterance is consumed once so a repeated callback cannot answer the next problem.

Choose **Fast** (180 ms), **Balanced** (300 ms, default), or **Careful** (450 ms) on Train. These are configured silence checks, not measured total latency. Fast can feel more responsive but is more likely to split a slowly spoken number; Careful allows longer pauses between words. Start with Balanced and compare using [the voice benchmark](docs/VOICE_BENCHMARK.md).

These checks reduce premature acceptance of phrases such as “one hundred” and confusion between “two” and “twenty.” They cannot remove all recognition mistakes. Fan noise, breath, speech pace, phone placement, and Bluetooth routing affect accuracy and latency. A quiet interval trades some speed for fewer accidental answers. No end-to-end latency or S24 Ultra accuracy claim is made until measured on that device.

Use the manual answer control when speech cannot hear you reliably. Microphone permission is required only for voice input. Backgrounding the app automatically pauses both microphone capture and the timer; resume the session from the app when ready.

## Data and release

Only session metrics are retained: timing, score, attempts, response time, streak, arithmetic selection, and input mode. Audio and recognized phrases are not written to history or synced. See [PRIVACY.md](PRIVACY.md) for the data flow and production-policy requirements.

The code and debug APK are a starting point for device testing. [Play Store preparation](docs/PLAY_STORE.md) covers signing, private testing, account deletion, privacy disclosures, and release checks. Google/Firebase configuration and publishing must be completed in accounts you control.
