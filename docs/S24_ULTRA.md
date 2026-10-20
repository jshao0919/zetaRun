# Test on a Samsung Galaxy S24 Ultra

## Install an already-built APK

No porting is required: ZetaRun is a native Android app. Copy `ZetaRun-debug.apk` (or the build's `app-debug.apk`) to the phone's Downloads folder, open it in My Files, and approve installation from that source when Android asks. Open ZetaRun and grant microphone permission when starting a voice round. You can revoke the install-source permission afterward.

The debug APK has offline voice built in. Google sign-in becomes available after configuring Firebase and rebuilding as described below. Updating with a differently signed APK requires uninstalling the old build, which deletes local-only history; export progress first.

## Install from Android Studio

1. Install Android Studio on your computer and open the `zetaRun` project. Let it install the SDK components requested by Gradle. Use the project's Gradle JDK setting; Android Studio's bundled compatible JDK is usually the easiest choice.
2. Install Python 3 if needed. From the project directory, run `./scripts/prepare-model.sh`, then sync Gradle. This verifies and extracts the English model from an official pinned Vosk release archive, so no recognition download is needed on the phone.
3. On the phone, open **Settings → About phone → Software information**. Tap **Build number** seven times and enter your PIN when asked.
4. Open **Settings → Developer options** and enable **USB debugging**. Connect a USB data cable and accept the phone's computer authorization prompt.
5. Select the S24 Ultra in Android Studio and press **Run** for `app`. Allow microphone access while using the app when prompted.

For command-line installation, add the SDK's `platform-tools` directory to your path:

```bash
./gradlew testDebugUnitTest assembleDebug
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`adb devices` should show `device`, not `unauthorized`. If it does not, unlock the phone, check the cable, and reconnect. If Samsung Auto Blocker explicitly blocks USB installation, review that setting for this trusted development install and restore your preferred protection afterward.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. You can copy it to your phone and open it in Files instead of using USB debugging; Android will ask you to allow that source to install apps. Remove that source's install permission afterward if you do not need it.

On Windows PowerShell, use `python scripts/prepare_model.py` for model preparation and `.\gradlew.bat testDebugUnitTest assembleDebug` for the build. Android Studio also builds and installs directly without Bash.

## First functional check

Start on a stationary phone in a quiet room before testing treadmill noise. On **Train**, choose **Hands-free voice**, **Balanced**, and a short round. Confirm that a spoken correct answer advances exactly one question. A wrong answer should leave the question available to retry. Try “skip” and “pause”; pausing must stop both the microphone and timer until you tap **Resume round**. Check the **Keypad** fallback, round results, and **Progress** after closing and reopening the app.

Try the following cases across several rounds:

| Case | Expected check |
| --- | --- |
| “Two,” “twenty,” “two hundred,” “one hundred and two” | A prefix must not prematurely answer a question when you are speaking a longer number. |
| Several problems with the same answer | Each requires a fresh utterance; an old recognition callback must not score again. |
| Repeat an answer, correct yourself, speak an unrelated phrase | No double score; ambiguous or irrelevant speech should not advance the question. |
| Pause between words, cough, breathe heavily, turn on a fan | Watch for accidental submissions and missed answers. |
| Deny microphone access | Manual input remains usable and the app explains how to enable voice. |
| Lock the phone, change apps, or receive a call | Microphone capture and the session timer pause; return and explicitly resume. |
| Enable airplane mode after installation | Voice and local progress continue without network access. |
| Sign in, finish a round, restart the app | With Firebase configured, history remains available and sync errors are visible rather than losing the local record. |
| Open Account → Export progress as JSON | Share or save a readable JSON export of the current history. |

Test deletion controls only with disposable data. **Account → Delete all progress** and, when signed in, **Delete account & cloud data** each ask for confirmation. Cloud deletion needs connectivity; read any reported error and retry as instructed. Google login, cloud sync, and cloud deletion must be validated with your own configured Firebase project.

Test the phone microphone first. Bluetooth earbuds can change microphone routing and audio quality; repeat the same checks with your actual headphones. Do not assume the earbud microphone is being used merely because the earbuds are connected.

## Measure the treadmill experience

Secure the phone where the question is readable, then repeat a two-minute round with the treadmill and fan at your usual settings. Keep the same arithmetic ranges while comparing runs. Compare **Fast** (180 ms silence check), **Balanced** (300 ms), and **Careful** (450 ms). These values tune the pause tolerance; they are not a promise of answer-to-screen latency.

Follow [the voice benchmark](VOICE_BENCHMARK.md) to measure answer-to-screen delay, missed answers, premature submissions, and noise-triggered advances. The UI's **Approx. endpoint delay** measures an internal silence/decoder interval; it excludes parts of capture, decoding, scheduling, and display. **Mean response time** in results includes solving time and is not a speech-recognition benchmark. The cloud build environment cannot establish S24 Ultra microphone accuracy or real-device latency.

## Google login

Follow [Firebase setup](FIREBASE.md). Register the debug signing certificate's SHA-1, enable Google authentication, and use the Web OAuth client ID described there. Rebuild after adding the project configuration. Installing a release build later requires that build's certificate to be registered too.

An APK signed with another key cannot update the existing installation. Uninstalling may remove local-only history, so export or sync your data before switching keys.

## Useful diagnostics

Use Android Studio's Logcat while the phone is connected. Filter to `com.zetarun.app` and reproduce the problem. Share the Android version, app version, microphone/headset used, whether the phone was offline, and the steps that triggered the issue. Do not share credentials, private account details, or unreviewed full logs.
