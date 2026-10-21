# ZetaRun

An Android mental-arithmetic trainer for hands-free treadmill sessions, inspired by [Zetamac](https://arithmetic.zetamac.com/). This is an independent app and is not affiliated with Zetamac.

Solve a stream of arithmetic problems by speaking the answer. The default round lasts two minutes. Speech recognition runs continuously on the phone with [Vosk](https://alphacephei.com/vosk/); microphone audio is never sent to a speech service. Session history works without an account. Connecting your own Firebase project enables Google sign-in and progress sync.

## Build and run

For an already-built APK, open [GitHub Actions](https://github.com/jshao0919/zetaRun/actions/workflows/android.yml), select a successful run, and download **ZetaRun-debug** under **Artifacts**. Sign in to GitHub if prompted, extract the ZIP, and install `app-debug.apk` on your phone. If an artifact has expired, run the workflow again. [Download the source ZIP](https://github.com/jshao0919/zetaRun/archive/refs/heads/main.zip) to build locally.

You need Android Studio, JDK 17 or 21, Python 3 for model preparation, Android SDK Platform 36 and build tools, and a 64-bit Android 8.0+ device. The S24 Ultra is supported. The app targets Android 16 (API 36). The Gradle wrapper defines the build-tool version.

```bash
git clone https://github.com/jshao0919/zetaRun.git
cd zetaRun
./scripts/prepare-model.sh
./gradlew testDebugUnitTest assembleDebug
```

The model preparation script extracts the US English model from the official Vosk Android demo's pinned `2021-04` release archive and prepares the pinned Silero voice-activity model. Both downloads are checked against their SHA-256 values. The speech model is the one distributed in that release, rather than a claim to bundle the latest model. Model preparation is needed before the first build and does not require a Firebase project. Keep the generated models out of Git. Gradle, SDK, and model downloads require an Internet connection; recognition and speech detection in an installed app do not.

Open the project in Android Studio, let Gradle sync, select your phone, and run the `app` configuration. Or install the generated APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Follow [S24 Ultra testing](docs/S24_ULTRA.md) for USB setup, installation, and a voice-validation checklist. Follow [Firebase setup](docs/FIREBASE.md) to enable Google login and cloud progress. The app remains usable locally until that setup is complete.

If you received a source ZIP, extract and open that project directly. Cloud workspace changes are not automatically pushed to GitHub; the completed project was pushed after the owner authorized GitHub delivery. The included GitHub Actions workflow runs the checks and produces a downloadable debug APK.

## Use the app

- **Train:** choose voice or keypad, a 1-, 2-, or 5-minute round, and arithmetic operations. The tune button opens custom durations and number ranges. During a voice round, say an answer, “skip,” or “pause.” Pause stops both the timer and microphone; resume from the screen.
- **Progress:** review solving pace, accuracy, and dated session history. The graph shows the latest 20 rounds. Compare rounds with similar arithmetic settings when judging improvement.
- **Account:** sign in or sync after Firebase setup, export progress as JSON, delete progress, or request account and cloud-data deletion. Cloud actions require connectivity and may require a fresh Google sign-in. Firebase integration still requires testing against your own project.

## Voice behavior

The app uses an English number vocabulary and short audio frames to avoid network round trips. Automatic filtering combines an 80 Hz high-pass filter, Android noise suppression when supported by the device, and on-device Silero speech detection. A candidate needs sustained speech evidence—at least two strong 32 ms frames—before it can count as an answer or command. This evidence is collected while you speak; it is not another fixed wait added after a complete answer. Final recognition results also pass a word-confidence check.

The app waits for a stable recognition candidate and a short quiet interval before considering a partial answer. Final recognition results provide a second route to submission. An utterance is consumed once so a repeated callback cannot answer the next problem. The filters run automatically; no extra settings or online service are required, and audio is neither saved nor uploaded.

Choose **Fast** (180 ms), **Balanced** (300 ms, default), or **Careful** (450 ms) on Train. These are configured silence checks, not measured total latency. Fast can feel more responsive but is more likely to split a slowly spoken number; Careful allows longer pauses between words. Start with Balanced and compare using [the voice benchmark](docs/VOICE_BENCHMARK.md).

These checks are designed to keep breaths, fan rumble, and footsteps from becoming answers, incorrect attempts, or commands while preserving short spoken numbers. The quiet interval helps avoid accepting a prefix of “one hundred”; recognition can still confuse “two” and “twenty.” To prevent weak noise from keeping an old speech onset alive, acoustic proof expires after 1.2 seconds without renewed strong speech evidence; a very soft, long phrase may need repeating. Speech detection does not identify who is speaking, so another person's voice or spoken music can still matter. Check soft “two” and “six,” breathing after an answer, and normal treadmill noise with [the physical-device benchmark](docs/VOICE_BENCHMARK.md). No end-to-end latency or S24 Ultra accuracy claim is made until measured on that device.

The [synthetic noise comparison](docs/NOISE_VALIDATION.md) admitted all 84 speech clips and rejected all 11 noise-only clips at the speech-qualification stage. It also records recognition failures under heavy fan noise; these results are not a measurement of accepted answers on a treadmill.

Use the manual answer control when speech cannot hear you reliably. Microphone permission is required only for voice input. Backgrounding the app automatically pauses both microphone capture and the timer; resume the session from the app when ready.

## Data and release

Only session metrics are retained: timing, score, attempts, response time, streak, arithmetic selection, and input mode. Audio and recognized phrases are not written to history or synced. See [PRIVACY.md](PRIVACY.md) for the data flow and production-policy requirements.

The code and debug APK are a starting point for device testing. [Play Store preparation](docs/PLAY_STORE.md) covers signing, private testing, account deletion, privacy disclosures, and release checks. Google/Firebase configuration and publishing must be completed in accounts you control.
